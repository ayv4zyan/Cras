import { describe, it, expect, beforeEach, vi } from "vitest";
import fs from "fs";
import path from "path";

describe("Service Worker Seam (Issue #58)", () => {
  let swScope: {
    location: { origin: string };
    addEventListener: ReturnType<typeof vi.fn>;
    skipWaiting: ReturnType<typeof vi.fn>;
    clients: {
      claim: ReturnType<typeof vi.fn>;
      matchAll: ReturnType<typeof vi.fn>;
      openWindow: ReturnType<typeof vi.fn>;
    };
    registration: {
      showNotification: ReturnType<typeof vi.fn>;
      pushManager: {
        subscribe: ReturnType<typeof vi.fn>;
      };
    };
  };

  let listeners: Record<string, ((event: unknown) => void)[]>;
  let mockCaches: {
    open: ReturnType<typeof vi.fn>;
    match: ReturnType<typeof vi.fn>;
    keys: ReturnType<typeof vi.fn>;
    delete: ReturnType<typeof vi.fn>;
  };
  let mockCacheInstance: {
    addAll: ReturnType<typeof vi.fn>;
    put: ReturnType<typeof vi.fn>;
    match: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    listeners = {};
    mockCacheInstance = {
      addAll: vi.fn().mockResolvedValue(undefined),
      put: vi.fn().mockResolvedValue(undefined),
      match: vi.fn().mockResolvedValue(null),
    };

    mockCaches = {
      open: vi.fn().mockResolvedValue(mockCacheInstance),
      match: vi.fn().mockResolvedValue(null),
      keys: vi
        .fn()
        .mockResolvedValue(["cras-offline-shell-v1", "old-cache-v0"]),
      delete: vi.fn().mockResolvedValue(true),
    };

    swScope = {
      location: { origin: "https://cras.example.com" },
      addEventListener: vi
        .fn()
        .mockImplementation((event: string, handler: (e: unknown) => void) => {
          if (!listeners[event]) listeners[event] = [];
          listeners[event].push(handler);
        }),
      skipWaiting: vi.fn().mockResolvedValue(undefined),
      clients: {
        claim: vi.fn().mockResolvedValue(undefined),
        matchAll: vi.fn().mockResolvedValue([]),
        openWindow: vi.fn().mockResolvedValue(undefined),
      },
      registration: {
        showNotification: vi.fn().mockResolvedValue(undefined),
        pushManager: {
          subscribe: vi
            .fn()
            .mockResolvedValue({ toJSON: () => ({ endpoint: "new" }) }),
        },
      },
    };

    // Evaluate sw.js in simulated worker context
    const swPath = path.resolve(__dirname, "../../public/sw.js");
    const swContent = fs.readFileSync(swPath, "utf-8");
    const executeSw = new Function("self", "caches", "URL", swContent);
    executeSw(swScope, mockCaches, URL);
  });

  it("registers install, activate, message, fetch, and push event listeners", () => {
    expect(listeners["install"]).toBeDefined();
    expect(listeners["activate"]).toBeDefined();
    expect(listeners["message"]).toBeDefined();
    expect(listeners["fetch"]).toBeDefined();
    expect(listeners["push"]).toBeDefined();
    expect(listeners["notificationclick"]).toBeDefined();
    expect(listeners["pushsubscriptionchange"]).toBeDefined();
  });

  it("precaches minimal static offline shell on install and does not skip waiting automatically", async () => {
    const installHandler = listeners["install"][0];
    let waitUntilPromise: Promise<unknown> | null = null;
    const mockInstallEvent = {
      waitUntil: vi.fn().mockImplementation((p: Promise<unknown>) => {
        waitUntilPromise = p;
      }),
    };

    installHandler(mockInstallEvent);
    expect(mockInstallEvent.waitUntil).toHaveBeenCalled();
    expect(mockCaches.open).toHaveBeenCalledWith(
      expect.stringContaining("cras-offline-shell"),
    );

    await waitUntilPromise;
    expect(mockCacheInstance.addAll).toHaveBeenCalledWith(
      expect.arrayContaining(["/", "/index.html", "/favicon.svg"]),
    );
    // Should NOT call skipWaiting on install
    expect(swScope.skipWaiting).not.toHaveBeenCalled();
  });

  it("cleans up outdated caches and claims clients on activate", async () => {
    const activateHandler = listeners["activate"][0];
    let waitUntilPromise: Promise<unknown> | null = null;
    const mockActivateEvent = {
      waitUntil: vi.fn().mockImplementation((p: Promise<unknown>) => {
        waitUntilPromise = p;
      }),
    };

    activateHandler(mockActivateEvent);
    expect(mockActivateEvent.waitUntil).toHaveBeenCalled();

    await waitUntilPromise;
    expect(mockCaches.delete).toHaveBeenCalledWith("old-cache-v0");
    expect(mockCaches.delete).not.toHaveBeenCalledWith("cras-offline-shell-v1");
    expect(swScope.clients.claim).toHaveBeenCalled();
  });

  it("calls skipWaiting when receiving SKIP_WAITING message", () => {
    const messageHandler = listeners["message"][0];
    messageHandler({ data: { type: "SKIP_WAITING" } });
    expect(swScope.skipWaiting).toHaveBeenCalled();
  });

  it("ignores non-SKIP_WAITING messages", () => {
    const messageHandler = listeners["message"][0];
    messageHandler({ data: { type: "OTHER_MESSAGE" } });
    expect(swScope.skipWaiting).not.toHaveBeenCalled();
  });

  describe("Fetch Handler - Minimal Static Offline Shell Strategy", () => {
    it("bypasses non-GET requests without intercepting", () => {
      const fetchHandler = listeners["fetch"][0];
      const mockEvent = {
        request: {
          method: "POST",
          url: "https://cras.example.com/api/rpc",
          mode: "cors",
        },
        respondWith: vi.fn(),
      };

      fetchHandler(mockEvent);
      expect(mockEvent.respondWith).not.toHaveBeenCalled();
    });

    it("bypasses Supabase API, auth, and external requests without caching", () => {
      const fetchHandler = listeners["fetch"][0];
      const mockEvent1 = {
        request: {
          method: "GET",
          url: "https://cras.example.com/rest/v1/tasks",
          mode: "cors",
        },
        respondWith: vi.fn(),
      };
      fetchHandler(mockEvent1);
      expect(mockEvent1.respondWith).not.toHaveBeenCalled();

      const mockEvent2 = {
        request: {
          method: "GET",
          url: "https://other-domain.supabase.co/rest/v1/tasks",
          mode: "cors",
        },
        respondWith: vi.fn(),
      };
      fetchHandler(mockEvent2);
      expect(mockEvent2.respondWith).not.toHaveBeenCalled();
    });

    it("serves cached offline shell index.html when navigation request fails offline", async () => {
      const fetchHandler = listeners["fetch"][0];
      const mockCachedResponse = {
        status: 200,
        body: "<html>Offline Shell</html>",
      };
      mockCaches.match.mockResolvedValue(mockCachedResponse);

      let responsePromise: Promise<unknown> | null = null;
      const mockEvent = {
        request: {
          method: "GET",
          url: "https://cras.example.com/today",
          mode: "navigate",
          destination: "document",
        },
        respondWith: vi.fn().mockImplementation((p: Promise<unknown>) => {
          responsePromise = p;
        }),
      };

      // In Node environment, global fetch is mocked or fails
      const originalFetch = globalThis.fetch;
      globalThis.fetch = vi.fn().mockRejectedValue(new Error("Network failed"));

      try {
        fetchHandler(mockEvent);
        expect(mockEvent.respondWith).toHaveBeenCalled();

        const response = await responsePromise;
        expect(response).toBe(mockCachedResponse);
        expect(mockCaches.match).toHaveBeenCalledWith("/index.html");
      } finally {
        globalThis.fetch = originalFetch;
      }
    });

    it("serves static assets from cache when available and revalidates in background", async () => {
      const fetchHandler = listeners["fetch"][0];
      const mockAssetResponse = { status: 200, body: "/* css */" };
      mockCaches.match.mockResolvedValue(mockAssetResponse);

      const mockRevalidatedResponse = {
        status: 200,
        clone: vi.fn().mockReturnValue({ status: 200, body: "/* new css */" }),
      };
      const originalFetch = globalThis.fetch;
      const fetchMock = vi.fn().mockResolvedValue(mockRevalidatedResponse);
      globalThis.fetch = fetchMock;

      let responsePromise: Promise<unknown> | null = null;
      const mockRequest = {
        method: "GET",
        url: "https://cras.example.com/assets/index.css",
        mode: "cors",
        destination: "style",
      };
      const mockEvent = {
        request: mockRequest,
        respondWith: vi.fn().mockImplementation((p: Promise<unknown>) => {
          responsePromise = p;
        }),
      };

      try {
        fetchHandler(mockEvent);
        expect(mockEvent.respondWith).toHaveBeenCalled();

        const response = await responsePromise;
        expect(response).toBe(mockAssetResponse);

        // Allow background revalidation promise microtasks to run
        await Promise.resolve();
        await Promise.resolve();

        expect(fetchMock).toHaveBeenCalledWith(mockRequest);
        expect(mockCacheInstance.put).toHaveBeenCalledWith(
          mockRequest,
          expect.objectContaining({ status: 200, body: "/* new css */" }),
        );
      } finally {
        globalThis.fetch = originalFetch;
      }
    });
  });

  describe("Web Push Notifications Coexistence", () => {
    it("handles push event and displays notification", async () => {
      const pushHandler = listeners["push"][0];
      let waitUntilPromise: Promise<unknown> | null = null;
      const mockEvent = {
        data: {
          json: () => ({
            title: "Task Plan Notification",
            taskId: "task-123",
            occurrenceKey: "occ-456",
          }),
        },
        waitUntil: vi.fn().mockImplementation((p: Promise<unknown>) => {
          waitUntilPromise = p;
        }),
      };

      pushHandler(mockEvent);
      expect(mockEvent.waitUntil).toHaveBeenCalled();

      await waitUntilPromise;
      expect(swScope.registration.showNotification).toHaveBeenCalledWith(
        "Task Plan Notification",
        expect.objectContaining({
          tag: "occ-456",
          data: { taskId: "task-123", occurrenceKey: "occ-456" },
          icon: "/favicon.svg",
        }),
      );
    });
  });
});
