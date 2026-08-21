import { describe, it, expect, beforeEach, vi } from "vitest";
import {
  BEST_EFFORT_RELIABILITY_COPY,
  deriveInstallationStatus,
  getOrCreateInstallationId,
  getLocalNotificationsEnabled,
  setLocalNotificationsEnabled,
  hasExplainedPermission,
  setExplainedPermission,
  syncInstallationWithServer,
  deactivateInstallation,
  getObservedTimezone,
  urlBase64ToUint8Array,
  arrayBufferToBase64,
} from "./notificationService";
import type { SupabaseClient } from "@supabase/supabase-js";

describe("Notification Service Seam", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
  });

  describe("Exact Best-Effort Reliability Copy", () => {
    it("matches the exact canonical specification copy", () => {
      expect(BEST_EFFORT_RELIABILITY_COPY).toBe(
        "Notifications are best effort. They may be delayed or missed when your device is offline, system notifications are blocked, the app or browser is restricted, or Cras is unavailable.",
      );
    });
  });

  describe("deriveInstallationStatus (4 Required States)", () => {
    it("distinguishes Enabled state", () => {
      const status = deriveInstallationStatus({
        localEnabled: true,
        permission: "granted",
        hasEndpoint: true,
      });
      expect(status).toBe("enabled");
    });

    it("distinguishes Disabled locally state when local control is off", () => {
      const status = deriveInstallationStatus({
        localEnabled: false,
        permission: "granted",
        hasEndpoint: true,
      });
      expect(status).toBe("disabled_locally");
    });

    it("distinguishes Blocked by system permission state when permission is denied", () => {
      const status = deriveInstallationStatus({
        localEnabled: true,
        permission: "denied",
        hasEndpoint: true,
      });
      expect(status).toBe("blocked");
    });

    it("distinguishes Endpoint unavailable state when permission is granted but no endpoint", () => {
      const status = deriveInstallationStatus({
        localEnabled: true,
        permission: "granted",
        hasEndpoint: false,
      });
      expect(status).toBe("endpoint_unavailable");
    });

    it("distinguishes Endpoint unavailable state when permission is default/prompt", () => {
      const status = deriveInstallationStatus({
        localEnabled: true,
        permission: "default",
        hasEndpoint: false,
      });
      expect(status).toBe("endpoint_unavailable");
    });
  });

  describe("Local Storage State Management", () => {
    it("generates and persists installation ID", () => {
      const id1 = getOrCreateInstallationId();
      expect(id1).toBeDefined();
      expect(typeof id1).toBe("string");
      const id2 = getOrCreateInstallationId();
      expect(id2).toBe(id1);
    });

    it("persists local notifications enabled flag", () => {
      expect(getLocalNotificationsEnabled()).toBe(true);
      setLocalNotificationsEnabled(false);
      expect(getLocalNotificationsEnabled()).toBe(false);
      setLocalNotificationsEnabled(true);
      expect(getLocalNotificationsEnabled()).toBe(true);
    });

    it("persists permission explained flag", () => {
      expect(hasExplainedPermission()).toBe(false);
      setExplainedPermission(true);
      expect(hasExplainedPermission()).toBe(true);
    });

    it("retrieves observed IANA timezone", () => {
      const tz = getObservedTimezone();
      expect(typeof tz).toBe("string");
      expect(tz.length).toBeGreaterThan(0);
    });
  });

  describe("Server Synchronization & Deactivation", () => {
    it("calls register_or_update_installation RPC with appropriate parameters", async () => {
      const mockRpc = vi.fn().mockResolvedValue({
        data: { id: "test-inst-id", is_active: true },
        error: null,
      });
      const client = {
        rpc: mockRpc,
        schema: vi.fn().mockReturnValue({ rpc: mockRpc }),
      } as unknown as SupabaseClient;

      await syncInstallationWithServer(client, {
        localEnabled: true,
        permissionState: "granted",
        endpoint: "https://push.example.com/test",
        p256dh: "key-p256",
        auth: "key-auth",
        installationTimezone: "America/New_York",
      });

      expect(mockRpc).toHaveBeenCalledWith(
        "register_or_update_installation",
        expect.objectContaining({
          p_platform: "web",
          p_local_enabled: true,
          p_permission_state: "granted",
          p_endpoint: "https://push.example.com/test",
          p_p256dh: "key-p256",
          p_auth: "key-auth",
          p_installation_timezone: "America/New_York",
          p_clear_subscription: false,
        }),
      );
    });

    it("sends p_clear_subscription = true when endpoint is explicitly null or clearSubscription is true", async () => {
      const mockRpc = vi.fn().mockResolvedValue({
        data: { id: "test-inst-id", is_active: true },
        error: null,
      });
      const client = {
        rpc: mockRpc,
        schema: vi.fn().mockReturnValue({ rpc: mockRpc }),
      } as unknown as SupabaseClient;

      await syncInstallationWithServer(client, {
        endpoint: null,
      });

      expect(mockRpc).toHaveBeenCalledWith(
        "register_or_update_installation",
        expect.objectContaining({
          p_endpoint: null,
          p_p256dh: null,
          p_auth: null,
          p_clear_subscription: true,
        }),
      );
    });

    it("sends p_clear_subscription = false and preserves existing fields when options are omitted", async () => {
      const mockRpc = vi.fn().mockResolvedValue({
        data: { id: "test-inst-id", is_active: true },
        error: null,
      });
      const client = {
        rpc: mockRpc,
        schema: vi.fn().mockReturnValue({ rpc: mockRpc }),
      } as unknown as SupabaseClient;

      await syncInstallationWithServer(client);

      expect(mockRpc).toHaveBeenCalledWith(
        "register_or_update_installation",
        expect.objectContaining({
          p_endpoint: null,
          p_p256dh: null,
          p_auth: null,
          p_clear_subscription: false,
        }),
      );
    });

    it("calls deactivate_installation on sign-out", async () => {
      const mockRpc = vi.fn().mockResolvedValue({ data: true, error: null });
      const client = {
        rpc: mockRpc,
        schema: vi.fn().mockReturnValue({ rpc: mockRpc }),
      } as unknown as SupabaseClient;

      await deactivateInstallation(client);

      expect(mockRpc).toHaveBeenCalledWith(
        "deactivate_installation",
        expect.objectContaining({
          p_id: expect.any(String),
        }),
      );
    });
  });

  describe("Key Conversion Utilities", () => {
    it("converts url-safe base64 to Uint8Array and back", () => {
      // "ABCD" -> base64 for bytes [0x00, 0x10, 0x83]
      const testBase64 = "ABCD";
      const u8 = urlBase64ToUint8Array(testBase64);
      expect(u8).toBeInstanceOf(Uint8Array);
      expect(Array.from(u8)).toEqual([0, 16, 131]);
      const converted = arrayBufferToBase64(u8.buffer);
      expect(converted).toBe("ABCD");
    });

    it("handles null buffer safely", () => {
      expect(arrayBufferToBase64(null)).toBeNull();
    });
  });
});
