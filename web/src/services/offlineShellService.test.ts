import { describe, it, expect, beforeEach, vi } from "vitest";
import {
  getIsOnline,
  subscribeNetworkStatus,
  saveDraftTaskInput,
  loadDraftTaskInput,
  clearDraftTaskInput,
  registerOfflineShell,
  activateWaitingWorker,
  setupControllerChangeReload,
} from "./offlineShellService";

describe("Offline Shell Service Seam (Issue #58)", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    vi.clearAllMocks();
  });

  describe("Network Status Detection", () => {
    it("returns true when navigator.onLine is true", () => {
      Object.defineProperty(window.navigator, "onLine", {
        value: true,
        configurable: true,
      });
      expect(getIsOnline()).toBe(true);
    });

    it("returns false when navigator.onLine is false", () => {
      Object.defineProperty(window.navigator, "onLine", {
        value: false,
        configurable: true,
      });
      expect(getIsOnline()).toBe(false);
    });

    it("notifies listeners on online and offline window events", () => {
      const listener = vi.fn();
      const unsubscribe = subscribeNetworkStatus(listener);

      // Simulate offline event
      Object.defineProperty(window.navigator, "onLine", {
        value: false,
        configurable: true,
      });
      window.dispatchEvent(new Event("offline"));
      expect(listener).toHaveBeenCalledWith(false);

      // Simulate online event
      Object.defineProperty(window.navigator, "onLine", {
        value: true,
        configurable: true,
      });
      window.dispatchEvent(new Event("online"));
      expect(listener).toHaveBeenCalledWith(true);

      unsubscribe();
      window.dispatchEvent(new Event("offline"));
      expect(listener).toHaveBeenCalledTimes(2);
    });
  });

  describe("Draft In-Progress State Preservation", () => {
    it("saves, loads, and clears draft task input in session storage", () => {
      expect(loadDraftTaskInput()).toBeNull();

      const draft = {
        title: "Unsaved Draft Task",
        description: "Draft notes in progress",
      };
      saveDraftTaskInput(draft);
      expect(loadDraftTaskInput()).toEqual(draft);

      clearDraftTaskInput();
      expect(loadDraftTaskInput()).toBeNull();
    });

    it("handles storage errors safely", () => {
      const draft = { title: "Draft" };
      saveDraftTaskInput(draft);
      expect(loadDraftTaskInput()).toEqual(draft);
    });
  });

  describe("Service Worker Update Lifecycle", () => {
    it("detects already-waiting service worker during registration", async () => {
      const mockWaitingWorker = {
        postMessage: vi.fn(),
        state: "installed",
      } as unknown as ServiceWorker;

      const mockRegistration = {
        scope: "/",
        waiting: mockWaitingWorker,
        installing: null,
        addEventListener: vi.fn(),
      } as unknown as ServiceWorkerRegistration;

      const registerSpy = vi.fn().mockResolvedValue(mockRegistration);
      Object.defineProperty(navigator, "serviceWorker", {
        value: {
          register: registerSpy,
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
        },
        configurable: true,
      });

      const onUpdateAvailable = vi.fn();
      const reg = await registerOfflineShell({ onUpdateAvailable });

      expect(reg).toBe(mockRegistration);
      expect(onUpdateAvailable).toHaveBeenCalledWith(mockWaitingWorker);
    });

    it("detects newly installing worker transitioning to installed state", async () => {
      const stateChangeHolder: { handler?: () => void } = {};
      const mockInstallingWorker: {
        state: ServiceWorkerState;
        addEventListener: (event: string, handler: () => void) => void;
      } = {
        state: "installing",
        addEventListener: vi.fn().mockImplementation((event, handler) => {
          if (event === "statechange") stateChangeHolder.handler = handler;
        }),
      };

      const mockRegistration = {
        scope: "/",
        waiting: null,
        installing: mockInstallingWorker as unknown as ServiceWorker,
        addEventListener: vi.fn().mockImplementation((event, handler) => {
          if (event === "updatefound") {
            handler();
          }
        }),
      } as unknown as ServiceWorkerRegistration;

      const registerSpy = vi.fn().mockResolvedValue(mockRegistration);
      Object.defineProperty(navigator, "serviceWorker", {
        value: {
          register: registerSpy,
          controller: {}, // Existing controller active
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
        },
        configurable: true,
      });

      const onUpdateAvailable = vi.fn();
      await registerOfflineShell({ onUpdateAvailable });

      // Installing worker finishes installing
      mockInstallingWorker.state = "installed";
      stateChangeHolder.handler?.();

      expect(onUpdateAvailable).toHaveBeenCalledWith(
        mockInstallingWorker as unknown as ServiceWorker,
      );
    });

    it("sends SKIP_WAITING message to waiting worker when activating", () => {
      const mockWorker = {
        postMessage: vi.fn(),
      } as unknown as ServiceWorker;

      activateWaitingWorker(mockWorker);
      expect(mockWorker.postMessage).toHaveBeenCalledWith({
        type: "SKIP_WAITING",
      });
    });

    it("reloads page when service worker controller changes", () => {
      const controllerChangeHolder: { handler?: () => void } = {};
      Object.defineProperty(navigator, "serviceWorker", {
        value: {
          addEventListener: vi.fn().mockImplementation((event, handler) => {
            if (event === "controllerchange")
              controllerChangeHolder.handler = handler;
          }),
          removeEventListener: vi.fn(),
        },
        configurable: true,
      });

      const reloadMock = vi.fn();
      const originalLocation = window.location;
      // @ts-expect-error mock window.location
      delete window.location;
      window.location = { ...originalLocation, reload: reloadMock } as Location;

      try {
        const cleanup = setupControllerChangeReload();
        expect(controllerChangeHolder.handler).toBeDefined();

        controllerChangeHolder.handler?.();
        expect(reloadMock).toHaveBeenCalled();

        cleanup();
      } finally {
        window.location = originalLocation;
      }
    });
  });
});
