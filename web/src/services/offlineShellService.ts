export interface UnsubmittedTaskInput {
  readonly title?: string;
  readonly description?: string;
}

export interface RegisterOfflineShellOptions {
  readonly onUpdateAvailable?: (waitingWorker: ServiceWorker) => void;
}

const UNSUBMITTED_TASK_STORAGE_KEY = "cras_unsubmitted_task_input";

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

function safeSessionStorage(): Storage | null {
  try {
    if (typeof sessionStorage !== "undefined") {
      return sessionStorage;
    }
  } catch {
    // Ignore storage unavailability
  }
  return null;
}

/**
 * Saves in-progress unsubmitted task text to sessionStorage so it survives
 * service worker updates / reloads.
 */
export function saveUnsubmittedTaskInput(input: UnsubmittedTaskInput): void {
  try {
    const storage = safeSessionStorage();
    if (storage) {
      storage.setItem(UNSUBMITTED_TASK_STORAGE_KEY, JSON.stringify(input));
    }
  } catch {
    // Ignore storage errors
  }
}

/**
 * Loads in-progress unsubmitted task text from sessionStorage.
 */
export function loadUnsubmittedTaskInput(): UnsubmittedTaskInput | null {
  try {
    const storage = safeSessionStorage();
    if (storage) {
      const serialized = storage.getItem(UNSUBMITTED_TASK_STORAGE_KEY);
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
 * Clears saved in-progress unsubmitted task text.
 */
export function clearUnsubmittedTaskInput(): void {
  try {
    const storage = safeSessionStorage();
    if (storage) {
      storage.removeItem(UNSUBMITTED_TASK_STORAGE_KEY);
    }
  } catch {
    // Ignore storage errors
  }
}

/**
 * Registers the Service Worker and configures update discovery.
 */
export async function registerOfflineShell(
  options?: RegisterOfflineShellOptions,
): Promise<ServiceWorkerRegistration | null> {
  if (
    typeof window === "undefined" ||
    !("serviceWorker" in navigator) ||
    !navigator.serviceWorker ||
    typeof navigator.serviceWorker.register !== "function"
  ) {
    return null;
  }

  try {
    const registration = await navigator.serviceWorker.register("/sw.js", {
      scope: "/",
    });

    const checkWaitingWorker = (worker: ServiceWorker) => {
      if (options?.onUpdateAvailable) {
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
