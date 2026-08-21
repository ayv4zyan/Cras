import type { SupabaseClient } from "@supabase/supabase-js";

export const BEST_EFFORT_RELIABILITY_COPY =
  "Notifications are best effort. They may be delayed or missed when your device is offline, system notifications are blocked, the app or browser is restricted, or Cras is unavailable.";

export type InstallationStatus =
  "enabled" | "disabled_locally" | "blocked" | "endpoint_unavailable";

export interface InstallationRecord {
  readonly id: string;
  readonly platform: "web" | "android";
  readonly localEnabled: boolean;
  readonly permissionState: "granted" | "denied" | "prompt" | "default";
  readonly endpoint: string | null;
  readonly installationTimezone: string;
  readonly timezoneObservedAt: string;
  readonly isActive: boolean;
}

const INSTALLATION_ID_STORAGE_KEY = "cras_installation_id";
const LOCAL_ENABLED_STORAGE_KEY = "cras_notifications_local_enabled";
const PERMISSION_EXPLAINED_STORAGE_KEY =
  "cras_notifications_permission_explained";

// In-memory fallbacks
let inMemoryInstallationId: string | null = null;
let inMemoryLocalEnabled = true;
let inMemoryPermissionExplained = false;

function generateUUID(): string {
  if (
    typeof crypto !== "undefined" &&
    typeof crypto.randomUUID === "function"
  ) {
    return crypto.randomUUID();
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function getOrCreateInstallationId(): string {
  try {
    if (typeof localStorage !== "undefined") {
      let id = localStorage.getItem(INSTALLATION_ID_STORAGE_KEY);
      if (!id) {
        id = generateUUID();
        localStorage.setItem(INSTALLATION_ID_STORAGE_KEY, id);
      }
      return id;
    }
  } catch {
    // Ignore storage errors
  }

  if (!inMemoryInstallationId) {
    inMemoryInstallationId = generateUUID();
  }
  return inMemoryInstallationId;
}

export function getLocalNotificationsEnabled(): boolean {
  try {
    if (typeof localStorage !== "undefined") {
      const val = localStorage.getItem(LOCAL_ENABLED_STORAGE_KEY);
      if (val !== null) {
        return val === "true";
      }
    }
  } catch {
    // Local storage access error
  }
  return inMemoryLocalEnabled;
}

export function setLocalNotificationsEnabled(enabled: boolean): void {
  inMemoryLocalEnabled = enabled;
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(LOCAL_ENABLED_STORAGE_KEY, String(enabled));
    }
  } catch {
    // Ignore
  }
}

export function hasExplainedPermission(): boolean {
  try {
    if (typeof localStorage !== "undefined") {
      return localStorage.getItem(PERMISSION_EXPLAINED_STORAGE_KEY) === "true";
    }
  } catch {
    // Ignore
  }
  return inMemoryPermissionExplained;
}

export function setExplainedPermission(explained: boolean): void {
  inMemoryPermissionExplained = explained;
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(PERMISSION_EXPLAINED_STORAGE_KEY, String(explained));
    }
  } catch {
    // Ignore
  }
}

export function getObservedTimezone(): string {
  try {
    if (typeof Intl !== "undefined" && Intl.DateTimeFormat) {
      return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
    }
  } catch {
    // Ignore
  }
  return "UTC";
}

export function getBrowserPermissionState():
  NotificationPermission | "unsupported" {
  if (typeof window === "undefined" || typeof Notification === "undefined") {
    return "unsupported";
  }
  return Notification.permission;
}

/**
 * Distinguishes the 4 required installation states:
 * 1. Enabled: localEnabled is true, permission is granted, and push endpoint is available.
 * 2. Disabled locally: localEnabled is false.
 * 3. Blocked by system permission: localEnabled is true, but permission is denied.
 * 4. Endpoint unavailable: localEnabled is true, but endpoint or push service is unavailable.
 */
export function deriveInstallationStatus(params: {
  localEnabled: boolean;
  permission: NotificationPermission | "unsupported";
  hasEndpoint: boolean;
}): InstallationStatus {
  if (!params.localEnabled) {
    return "disabled_locally";
  }
  if (params.permission === "denied") {
    return "blocked";
  }
  if (params.permission === "granted" && params.hasEndpoint) {
    return "enabled";
  }
  return "endpoint_unavailable";
}

export function urlBase64ToUint8Array(base64String: string): Uint8Array {
  const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
  const rawData = window.atob(base64);
  const outputArray = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; ++i) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray;
}

export function arrayBufferToBase64(buffer: ArrayBuffer | null): string | null {
  if (!buffer) return null;
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return window.btoa(binary);
}

export async function registerServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (
    typeof window === "undefined" ||
    !("serviceWorker" in navigator) ||
    typeof window.navigator.serviceWorker.register !== "function"
  ) {
    return null;
  }

  try {
    const reg = await navigator.serviceWorker.register("/sw.js", {
      scope: "/",
    });
    return reg;
  } catch {
    return null;
  }
}

export async function getExistingPushSubscription(
  registration?: ServiceWorkerRegistration | null,
): Promise<PushSubscription | null> {
  if (!registration || !("pushManager" in registration)) {
    return null;
  }
  try {
    return await registration.pushManager.getSubscription();
  } catch {
    return null;
  }
}

export async function subscribeToPush(
  registration: ServiceWorkerRegistration,
  vapidPublicKey?: string,
): Promise<PushSubscription | null> {
  if (!("pushManager" in registration)) {
    return null;
  }

  try {
    const options: PushSubscriptionOptionsInit = {
      userVisibleOnly: true,
    };
    if (vapidPublicKey) {
      options.applicationServerKey = urlBase64ToUint8Array(vapidPublicKey);
    }
    return await registration.pushManager.subscribe(options);
  } catch {
    return null;
  }
}

export async function syncInstallationWithServer(
  client: SupabaseClient,
  options?: {
    localEnabled?: boolean;
    permissionState?: string;
    endpoint?: string | null;
    p256dh?: string | null;
    auth?: string | null;
    installationTimezone?: string;
  },
): Promise<InstallationRecord | null> {
  const installationId = getOrCreateInstallationId();
  const localEnabled = options?.localEnabled ?? getLocalNotificationsEnabled();
  const rawPerm = options?.permissionState ?? getBrowserPermissionState();
  const permissionState =
    rawPerm === "granted"
      ? "granted"
      : rawPerm === "denied"
        ? "denied"
        : "prompt";
  const tz = options?.installationTimezone ?? getObservedTimezone();

  const { data, error } = await client.rpc("register_or_update_installation", {
    id: installationId,
    platform: "web",
    local_enabled: localEnabled,
    permission_state: permissionState,
    endpoint: options?.endpoint ?? null,
    p256dh: options?.p256dh ?? null,
    auth: options?.auth ?? null,
    installation_timezone: tz,
  });

  if (error) {
    throw new Error(`Failed to sync installation: ${error.message}`);
  }

  return data as InstallationRecord | null;
}

export async function deactivateInstallation(
  client: SupabaseClient,
): Promise<void> {
  const installationId = getOrCreateInstallationId();
  try {
    await client.rpc("deactivate_installation", { id: installationId });
  } catch {
    // Best-effort deactivation
  }
}
