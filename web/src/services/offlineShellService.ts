export interface DraftTaskInput {
  readonly title?: string;
  readonly description?: string;
}

export interface RegisterOfflineShellOptions {
  readonly onUpdateAvailable?: (waitingWorker: ServiceWorker) => void;
}

const DRAFT_TASK_STORAGE_KEY = "cras_draft_task_input";

/**
 * Returns true if the browser is currently online, false otherwise.
 */
export function getIsOnline(): boolean {
  if (
    typeof navigator !== "undefined" &&
    typeof navigator.onLine === "boolean"
  ) {
    return navigator.onLine;
  }
  return true;
}

/**
 * Subscribes to window online/offline events.
 */
export function subscribeNetworkStatus(
  callback: (isOnline: boolean) => void,
): () => void {
  if (typeof window === "undefined") {
    return () => {};
  }

  const handleOnline = () => callback(true);
  const handleOffline = () => callback(false);

  window.addEventListener("online", handleOnline);
  window.addEventListener("offline", handleOffline);

  return () => {
    window.removeEventListener("online", handleOnline);
    window.removeEventListener("offline", handleOffline);
  };
}

/**
 * Saves in-progress draft task text to sessionStorage so it survives
 * service worker updates / reloads.
 */
export function saveDraftTaskInput(draft: DraftTaskInput): void {
  try {
    if (typeof sessionStorage !== "undefined") {
      sessionStorage.setItem(DRAFT_TASK_STORAGE_KEY, JSON.stringify(draft));
    }
  } catch {
    // Ignore storage errors
  }
}

/**
 * Loads in-progress draft task text from sessionStorage.
 */
export function loadDraftTaskInput(): DraftTaskInput | null {
  try {
    if (typeof sessionStorage !== "undefined") {
      const serialized = sessionStorage.getItem(DRAFT_TASK_STORAGE_KEY);
      if (serialized) {
        return JSON.parse(serialized);
      }
    }
  } catch {
    // Ignore storage errors
  }
  return null;
}

/**
 * Clears saved in-progress draft task text.
 */
export function clearDraftTaskInput(): void {
  try {
    if (typeof sessionStorage !== "undefined") {
      sessionStorage.removeItem(DRAFT_TASK_STORAGE_KEY);
    }
  } catch {
    // Ignore storage errors
  }
}

/**
 * Registers the Service Worker offline shell and monitors for waiting updates.
 */
export async function registerOfflineShell(
  options?: RegisterOfflineShellOptions,
): Promise<ServiceWorkerRegistration | null> {
  if (
    typeof window === "undefined" ||
    !("serviceWorker" in navigator) ||
    typeof window.navigator.serviceWorker?.register !== "function"
  ) {
    return null;
  }

  try {
    const registration = await navigator.serviceWorker.register("/sw.js", {
      scope: "/",
    });

    const checkWaitingWorker = (worker: ServiceWorker | null) => {
      if (worker && options?.onUpdateAvailable) {
        options.onUpdateAvailable(worker);
      }
    };

    // 1. If an updated worker is already waiting
    if (registration.waiting) {
      checkWaitingWorker(registration.waiting);
    }

    // 2. If an update is in progress, monitor for when it finishes installing
    registration.addEventListener("updatefound", () => {
      const newWorker = registration.installing;
      if (!newWorker) return;

      newWorker.addEventListener("statechange", () => {
        if (
          newWorker.state === "installed" &&
          navigator.serviceWorker.controller
        ) {
          checkWaitingWorker(newWorker);
        }
      });
    });

    return registration;
  } catch {
    return null;
  }
}

/**
 * Sends SKIP_WAITING to a waiting Service Worker to trigger activation.
 */
export function activateWaitingWorker(worker: ServiceWorker): void {
  worker.postMessage({ type: "SKIP_WAITING" });
}

/**
 * Listens for controllerchange events on navigator.serviceWorker and reloads the window.
 */
export function setupControllerChangeReload(): () => void {
  if (
    typeof window === "undefined" ||
    !("serviceWorker" in navigator) ||
    !navigator.serviceWorker
  ) {
    return () => {};
  }

  let refreshing = false;
  const handleControllerChange = () => {
    if (refreshing) return;
    refreshing = true;
    window.location.reload();
  };

  navigator.serviceWorker.addEventListener(
    "controllerchange",
    handleControllerChange,
  );

  return () => {
    navigator.serviceWorker.removeEventListener(
      "controllerchange",
      handleControllerChange,
    );
  };
}
