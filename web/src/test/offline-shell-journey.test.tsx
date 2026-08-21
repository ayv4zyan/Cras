import { describe, it, expect, beforeEach, vi } from "vitest";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";
import { CrasApp } from "../App";
import { AuthProvider } from "../contexts/AuthContext";
import { getOutbox, clearOutbox } from "../services/outboxService";
import {
  clearDraftTaskInput,
  loadDraftTaskInput,
} from "../services/offlineShellService";
import type { Priority, Task } from "../contracts/task";
import type {
  SupabaseClient,
  Session,
  User,
  RealtimeChannel,
} from "@supabase/supabase-js";

describe("Web Deliberate Offline Shell Journey (Issue #58)", () => {
  const operatorUser: User = {
    id: "550e8400-e29b-41d4-a716-446655440099",
    email: "operator@example.com",
  } as User;

  const authSession: Session = {
    user: operatorUser,
    access_token: "operator-token",
  } as Session;

  const mockChannel = {
    on: vi.fn().mockReturnThis(),
    subscribe: vi.fn().mockImplementation((cb: (status: string) => void) => {
      cb("SUBSCRIBED");
      return mockChannel;
    }),
    unsubscribe: vi.fn().mockResolvedValue("ok"),
  } as unknown as RealtimeChannel;

  let mockWaitingWorker: { postMessage: ReturnType<typeof vi.fn> };

  function createMockClient(
    options: { isOffline?: boolean; initialTasks?: Task[] } = {},
  ) {
    const { isOffline = false, initialTasks = [] } = options;

    return {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: { session: authSession },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockReturnValue({
          data: { subscription: { unsubscribe: vi.fn() } },
        }),
        signInWithOAuth: vi.fn(),
        signOut: vi.fn(),
      },
      channel: vi.fn().mockReturnValue(mockChannel),
      removeChannel: vi.fn().mockResolvedValue("ok"),
      schema: vi.fn().mockReturnValue({
        rpc: vi
          .fn()
          .mockImplementation(
            (fnName: string, params: Record<string, unknown>) => {
              if (isOffline) {
                return Promise.reject(new Error("Failed to fetch"));
              }
              if (fnName === "create_task") {
                const newTask: Task = {
                  id: (params.id as string) || "task-id-1",
                  title: params.title as string,
                  description: (params.description as string) || null,
                  priority: (params.priority as Priority) || 4,
                  plan: null,
                  labels: [],
                  parentId: null,
                  completedAt: null,
                  createdAt: new Date().toISOString(),
                  updatedAt: new Date().toISOString(),
                  version: 1,
                };
                return Promise.resolve({ data: newTask, error: null });
              }
              if (fnName === "complete_task") {
                return Promise.resolve({
                  data: {
                    id: params.task_id,
                    completedAt: params.completed_at,
                    version: (params.expected_version as number) + 1,
                  },
                  error: null,
                });
              }
              if (fnName === "register_or_update_installation") {
                return Promise.resolve({
                  data: { id: "inst-1", is_active: true },
                  error: null,
                });
              }
              if (fnName === "deactivate_installation") {
                return Promise.resolve({ data: true, error: null });
              }
              return Promise.resolve({ data: null, error: null });
            },
          ),
        from: vi.fn().mockImplementation((table: string) => {
          if (table === "tasks") {
            return {
              select: vi.fn().mockImplementation(() => {
                if (isOffline) {
                  return Promise.reject(new Error("Failed to fetch"));
                }
                const p = Promise.resolve({ data: initialTasks, error: null });
                return Object.assign(p, {
                  eq: vi
                    .fn()
                    .mockImplementation((_col: string, id: string) => ({
                      single: vi.fn().mockImplementation(async () => {
                        const found = initialTasks.find(
                          (t: Task) => t.id === id,
                        );
                        return { data: found || null, error: null };
                      }),
                    })),
                });
              }),
            };
          }
          return {
            select: vi.fn().mockReturnValue({
              order: vi.fn().mockResolvedValue({ data: [], error: null }),
              maybeSingle: vi
                .fn()
                .mockResolvedValue({ data: null, error: null }),
            }),
          };
        }),
      }),
      from: vi.fn().mockImplementation(() => {
        if (isOffline) {
          return {
            select: vi.fn().mockReturnValue({
              order: vi.fn().mockRejectedValue(new Error("Failed to fetch")),
              maybeSingle: vi
                .fn()
                .mockRejectedValue(new Error("Failed to fetch")),
              eq: vi.fn().mockReturnValue({
                maybeSingle: vi
                  .fn()
                  .mockRejectedValue(new Error("Failed to fetch")),
              }),
            }),
          };
        }
        return {
          select: vi.fn().mockReturnValue({
            order: vi.fn().mockResolvedValue({ data: [], error: null }),
            maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
            eq: vi.fn().mockReturnValue({
              maybeSingle: vi
                .fn()
                .mockResolvedValue({ data: null, error: null }),
            }),
          }),
        };
      }),
    } as unknown as SupabaseClient;
  }

  let swListeners: Record<string, ((event: unknown) => void)[]> = {};

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    clearOutbox(operatorUser.id);
    clearDraftTaskInput();
    vi.clearAllMocks();

    mockWaitingWorker = {
      postMessage: vi.fn(),
    };
    swListeners = {};

    Object.defineProperty(window.navigator, "onLine", {
      value: true,
      configurable: true,
      writable: true,
    });

    Object.defineProperty(navigator, "serviceWorker", {
      value: {
        register: vi.fn().mockImplementation(async () => {
          return {
            scope: "/",
            waiting: null,
            installing: null,
            addEventListener: vi.fn(),
          };
        }),
        addEventListener: vi
          .fn()
          .mockImplementation(
            (event: string, handler: (e: unknown) => void) => {
              if (!swListeners[event]) swListeners[event] = [];
              swListeners[event].push(handler);
            },
          ),
        removeEventListener: vi.fn(),
      },
      configurable: true,
    });
  });

  describe("Intentional Offline State & Resilience", () => {
    it("displays deliberate intentional offline banner when network is unavailable", async () => {
      Object.defineProperty(window.navigator, "onLine", {
        value: false,
        configurable: true,
      });

      const mockClient = createMockClient({ isOffline: true });
      render(
        <AuthProvider client={mockClient}>
          <CrasApp client={mockClient} />
        </AuthProvider>,
      );

      await waitFor(() => {
        expect(
          screen.getByText(/offline — changes are saved locally to outbox/i),
        ).toBeInTheDocument();
      });

      // Does not show broken page or generic error banner
      expect(
        screen.queryByText(/failed to load tasks/i),
      ).not.toBeInTheDocument();
      expect(
        screen.getByRole("heading", { level: 2, name: /inbox/i }),
      ).toBeInTheDocument();
    });

    it("accepts offline creates, overlays them in UI, and clears offline banner on reconnect", async () => {
      Object.defineProperty(window.navigator, "onLine", {
        value: false,
        configurable: true,
      });

      let offline = true;
      const serverCreatedTasks: Task[] = [];
      const mockClient = {
        auth: {
          getSession: vi.fn().mockResolvedValue({
            data: { session: authSession },
            error: null,
          }),
          onAuthStateChange: vi.fn().mockReturnValue({
            data: { subscription: { unsubscribe: vi.fn() } },
          }),
          signInWithOAuth: vi.fn(),
          signOut: vi.fn(),
        },
        channel: vi.fn().mockReturnValue(mockChannel),
        removeChannel: vi.fn().mockResolvedValue("ok"),
        schema: vi.fn().mockReturnValue({
          rpc: vi.fn().mockImplementation((fnName, params) => {
            if (offline) return Promise.reject(new Error("Failed to fetch"));
            if (fnName === "create_task") {
              const t: Task = {
                id: params.id,
                title: params.title,
                description: null,
                priority: 4,
                plan: null,
                labels: [],
                parentId: null,
                completedAt: null,
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString(),
                version: 1,
              };
              serverCreatedTasks.push(t);
              return Promise.resolve({ data: t, error: null });
            }
            if (fnName === "register_or_update_installation") {
              return Promise.resolve({ data: { id: "inst-1" }, error: null });
            }
            return Promise.resolve({ data: null, error: null });
          }),
          from: vi.fn().mockReturnValue({
            select: vi.fn().mockImplementation(() => {
              if (offline) return Promise.reject(new Error("Failed to fetch"));
              return Promise.resolve({ data: serverCreatedTasks, error: null });
            }),
          }),
        }),
        from: vi.fn().mockReturnValue({
          select: vi.fn().mockReturnValue({
            order: vi.fn().mockResolvedValue({ data: [], error: null }),
            maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
          }),
        }),
      } as unknown as SupabaseClient;

      render(
        <AuthProvider client={mockClient}>
          <CrasApp client={mockClient} />
        </AuthProvider>,
      );

      // Verify offline banner
      await waitFor(() => {
        expect(
          screen.getByText(/offline — changes are saved locally to outbox/i),
        ).toBeInTheDocument();
      });

      // Create a task while offline
      const input = screen.getByPlaceholderText(/create a task in inbox/i);
      fireEvent.change(input, {
        target: { value: "Task Created While Offline" },
      });
      fireEvent.submit(input.closest("form")!);

      // Shows immediately in UI
      expect(
        screen.getByText("Task Created While Offline"),
      ).toBeInTheDocument();
      expect(getOutbox(operatorUser.id)).toHaveLength(1);

      // Network comes back online
      offline = false;
      Object.defineProperty(window.navigator, "onLine", {
        value: true,
        configurable: true,
      });

      act(() => {
        window.dispatchEvent(new Event("online"));
      });

      // Banner clears and outbox drains
      await waitFor(() => {
        expect(
          screen.queryByText(/offline — changes are saved locally to outbox/i),
        ).not.toBeInTheDocument();
        expect(getOutbox(operatorUser.id)).toHaveLength(0);
      });
    });
  });

  describe("Service Worker Update Prompting & In-Progress Preservation", () => {
    it("prompts the Operator when a Service Worker update is waiting", async () => {
      const registerMock = navigator.serviceWorker
        .register as unknown as ReturnType<typeof vi.fn>;
      registerMock.mockImplementation(async () => {
        return {
          scope: "/",
          waiting: mockWaitingWorker,
          installing: null,
          addEventListener: vi.fn(),
        };
      });

      const mockClient = createMockClient();
      render(
        <AuthProvider client={mockClient}>
          <CrasApp client={mockClient} />
        </AuthProvider>,
      );

      await waitFor(() => {
        expect(
          screen.getByText(/a new version of cras is available/i),
        ).toBeInTheDocument();
        expect(
          screen.getByRole("button", { name: /update now/i }),
        ).toBeInTheDocument();
        expect(
          screen.getByRole("button", { name: /later/i }),
        ).toBeInTheDocument();
      });
    });

    it("activates waiting worker when Operator clicks Update Now", async () => {
      const registerMock = navigator.serviceWorker
        .register as unknown as ReturnType<typeof vi.fn>;
      registerMock.mockImplementation(async () => {
        return {
          scope: "/",
          waiting: mockWaitingWorker,
          installing: null,
          addEventListener: vi.fn(),
        };
      });

      const mockClient = createMockClient();
      render(
        <AuthProvider client={mockClient}>
          <CrasApp client={mockClient} />
        </AuthProvider>,
      );

      const updateBtn = await screen.findByRole("button", {
        name: /update now/i,
      });
      fireEvent.click(updateBtn);

      expect(mockWaitingWorker.postMessage).toHaveBeenCalledWith({
        type: "SKIP_WAITING",
      });
    });

    it("dismisses prompt when Operator clicks Later without activating", async () => {
      const registerMock = navigator.serviceWorker
        .register as unknown as ReturnType<typeof vi.fn>;
      registerMock.mockImplementation(async () => {
        return {
          scope: "/",
          waiting: mockWaitingWorker,
          installing: null,
          addEventListener: vi.fn(),
        };
      });

      const mockClient = createMockClient();
      render(
        <AuthProvider client={mockClient}>
          <CrasApp client={mockClient} />
        </AuthProvider>,
      );

      const laterBtn = await screen.findByRole("button", { name: /later/i });
      fireEvent.click(laterBtn);

      await waitFor(() => {
        expect(
          screen.queryByText(/a new version of cras is available/i),
        ).not.toBeInTheDocument();
      });
      expect(mockWaitingWorker.postMessage).not.toHaveBeenCalled();
    });

    it("preserves in-progress unsubmitted task draft across update / reload", async () => {
      const registerMock = navigator.serviceWorker
        .register as unknown as ReturnType<typeof vi.fn>;
      registerMock.mockImplementation(async () => {
        return {
          scope: "/",
          waiting: mockWaitingWorker,
          installing: null,
          addEventListener: vi.fn(),
        };
      });

      const mockClient = createMockClient();
      const { unmount } = render(
        <AuthProvider client={mockClient}>
          <CrasApp client={mockClient} />
        </AuthProvider>,
      );

      // Operator types draft before updating
      const input = await screen.findByPlaceholderText(
        /create a task in inbox/i,
      );
      fireEvent.change(input, {
        target: { value: "Draft Unsaved Task Title" },
      });

      // Click Update now
      const updateBtn = screen.getByRole("button", { name: /update now/i });
      fireEvent.click(updateBtn);

      // Verify draft was saved in sessionStorage
      expect(loadDraftTaskInput()?.title).toBe("Draft Unsaved Task Title");

      // Unmount & simulate page reload
      unmount();

      render(
        <AuthProvider client={mockClient}>
          <CrasApp client={mockClient} />
        </AuthProvider>,
      );

      // Draft is restored
      const restoredInput = (await screen.findByPlaceholderText(
        /create a task in inbox/i,
      )) as HTMLInputElement;
      expect(restoredInput.value).toBe("Draft Unsaved Task Title");
    });
  });

  describe("PWA Anti-Presentation & Browser Tab Behavior", () => {
    it("prevents browser default PWA install prompt", () => {
      const mockClient = createMockClient();
      render(
        <AuthProvider client={mockClient}>
          <CrasApp client={mockClient} />
        </AuthProvider>,
      );

      const preventDefaultSpy = vi.fn();
      const installEvent = new Event("beforeinstallprompt");
      installEvent.preventDefault = preventDefaultSpy;

      window.dispatchEvent(installEvent);

      expect(preventDefaultSpy).toHaveBeenCalled();
      // No install button or PWA prompt in UI
      expect(screen.queryByText(/install app/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/add to home screen/i)).not.toBeInTheDocument();
    });

    it("receives CRAS_OPEN_TASK message from service worker and opens target task detail", async () => {
      const targetTask: Task = {
        id: "550e8400-e29b-41d4-a716-446655449999",
        title: "Opened from Notification",
        description: "Task body",
        priority: 2,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        version: 1,
      };

      const mockClient = createMockClient({ initialTasks: [targetTask] });
      render(
        <AuthProvider client={mockClient}>
          <CrasApp client={mockClient} />
        </AuthProvider>,
      );

      await waitFor(() => {
        expect(
          screen.getByText("Opened from Notification"),
        ).toBeInTheDocument();
      });

      // Simulate Service Worker message
      act(() => {
        const handler = swListeners["message"]?.[0];
        handler?.({
          data: {
            type: "CRAS_OPEN_TASK",
            taskId: targetTask.id,
          },
        } as MessageEvent);
      });

      await waitFor(() => {
        // Modal is opened with the target task
        expect(
          screen.getByRole("dialog", { name: /task details/i }),
        ).toBeInTheDocument();
      });
    });
  });
});
