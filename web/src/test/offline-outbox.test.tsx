import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  render,
  screen,
  waitFor,
  fireEvent,
  act,
} from "@testing-library/react";
import { CrasApp } from "../App";
import { AuthProvider } from "../contexts/AuthContext";
import {
  getOutbox,
  clearOutbox,
  type OutboxItem,
} from "../services/outboxService";
import {
  setCachedEffectiveTimedPlanType,
  clearCachedEffectiveTimedPlanType,
} from "../services/settingsService";
import { createPlanFromInputs } from "../services/temporalService";
import type { Task } from "../contracts/task";
import type {
  SupabaseClient,
  Session,
  User,
  RealtimeChannel,
} from "@supabase/supabase-js";

describe("Web Offline Outbox Seam (Issue #51)", () => {
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

  function createStandardMockClient(
    options: {
      rpcHandler?: (
        fnName: string,
        params: Record<string, unknown>,
      ) => Promise<unknown>;
      tasksProvider?: () => Promise<{ data: Task[] | null; error: unknown }>;
      labelsProvider?: () => Promise<{
        data: unknown[] | null;
        error: unknown;
      }>;
      isOffline?: boolean;
    } = {},
  ) {
    const {
      rpcHandler = vi.fn().mockResolvedValue({ data: null, error: null }),
      tasksProvider = vi.fn().mockResolvedValue({ data: [], error: null }),
      labelsProvider = vi.fn().mockResolvedValue({ data: [], error: null }),
      isOffline = false,
    } = options;

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
        rpc: rpcHandler,
        from: vi.fn().mockImplementation((table: string) => {
          if (table === "tasks") {
            return {
              select: vi.fn().mockImplementation(() => {
                const p = tasksProvider();
                p.catch(() => {});
                return Object.assign(p, {
                  eq: vi
                    .fn()
                    .mockImplementation((_col: string, id: string) => ({
                      single: vi.fn().mockImplementation(async () => {
                        const res = await tasksProvider();
                        const found = (res.data || []).find(
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
            select: vi.fn().mockResolvedValue({ data: [], error: null }),
          };
        }),
      }),
      from: vi.fn().mockImplementation((table: string) => {
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
        if (table === "labels") {
          return {
            select: vi.fn().mockReturnValue({
              order: labelsProvider,
            }),
          };
        }
        if (table === "settings" || table === "deployment_config") {
          return {
            select: vi.fn().mockReturnValue({
              maybeSingle: vi
                .fn()
                .mockResolvedValue({ data: null, error: null }),
              eq: vi.fn().mockReturnValue({
                maybeSingle: vi
                  .fn()
                  .mockResolvedValue({ data: null, error: null }),
              }),
            }),
          };
        }
        return {
          select: vi.fn().mockReturnValue({
            order: vi.fn().mockResolvedValue({ data: [], error: null }),
            eq: vi.fn().mockReturnValue({
              maybeSingle: vi
                .fn()
                .mockResolvedValue({ data: null, error: null }),
            }),
            maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
          }),
        };
      }),
    } as unknown as SupabaseClient;
  }

  beforeEach(() => {
    clearOutbox(operatorUser.id);
    clearCachedEffectiveTimedPlanType();
    localStorage.clear();
  });

  it("enters persistent Outbox before network acknowledgement for task create and complete", async () => {
    let outboxStateBeforeAck: OutboxItem[] = [];

    const createdTasks: Task[] = [];
    const mockRpc = vi
      .fn()
      .mockImplementation(
        async (fnName: string, params: Record<string, unknown>) => {
          if (fnName === "create_task") {
            // Verify outbox has the item during in-flight network call
            outboxStateBeforeAck = getOutbox(operatorUser.id);

            const newTask: Task = {
              id:
                (params.id as string) || "550e8400-e29b-41d4-a716-446655440001",
              title: params.title as string,
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
            createdTasks.push(newTask);
            return { data: newTask, error: null };
          }
          return { data: null, error: null };
        },
      );

    const mockClient = createStandardMockClient({
      rpcHandler: mockRpc,
      tasksProvider: () => Promise.resolve({ data: createdTasks, error: null }),
    });

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    const input = await screen.findByPlaceholderText(/create a task in inbox/i);
    fireEvent.change(input, { target: { value: "Task entering outbox" } });
    fireEvent.submit(input.closest("form")!);

    // Optimistic item is displayed immediately
    expect(screen.getByText("Task entering outbox")).toBeInTheDocument();

    await waitFor(() => {
      expect(mockRpc).toHaveBeenCalledWith(
        "create_task",
        expect.objectContaining({ title: "Task entering outbox" }),
      );
      // Persistent outbox contained the item before network acknowledgement
      expect(outboxStateBeforeAck.length).toBeGreaterThan(0);
      expect(outboxStateBeforeAck[0].type).toBe("create");
      // Drained after acknowledgement
      expect(getOutbox(operatorUser.id)).toEqual([]);
    });
  });

  it("retains two independently accepted equal-title creates with distinct IDs without collapsing", async () => {
    const createdIds: string[] = [];

    const mockRpc = vi
      .fn()
      .mockImplementation((fnName: string, params: Record<string, unknown>) => {
        if (fnName === "create_task") {
          const taskId = params.id as string;
          createdIds.push(taskId);
          const newTask: Task = {
            id: taskId,
            title: params.title as string,
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
          return Promise.resolve({ data: newTask, error: null });
        }
        return Promise.resolve({ data: null, error: null });
      });

    const mockClient = createStandardMockClient({
      rpcHandler: mockRpc,
    });

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    const input = await screen.findByPlaceholderText(/create a task in inbox/i);

    // Create first "Identical Title"
    fireEvent.change(input, { target: { value: "Identical Title" } });
    fireEvent.submit(input.closest("form")!);

    await waitFor(() => {
      expect(screen.getAllByText("Identical Title")).toHaveLength(1);
    });

    // Create second "Identical Title"
    fireEvent.change(input, { target: { value: "Identical Title" } });
    fireEvent.submit(input.closest("form")!);

    await waitFor(() => {
      expect(screen.getAllByText("Identical Title")).toHaveLength(2);
      expect(createdIds).toHaveLength(2);
      expect(createdIds[0]).not.toBe(createdIds[1]);
    });
  });

  it("survives page reload with queued outbox work and drains upon reconnect", async () => {
    let networkOnline = false;
    const serverTasks: Task[] = [];

    const mockRpc = vi
      .fn()
      .mockImplementation((fnName: string, params: Record<string, unknown>) => {
        if (!networkOnline) {
          return Promise.reject(new Error("Failed to fetch"));
        }
        if (fnName === "create_task") {
          const newTask: Task = {
            id: params.id as string,
            title: params.title as string,
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
          serverTasks.push(newTask);
          return Promise.resolve({ data: newTask, error: null });
        }
        return Promise.resolve({ data: null, error: null });
      });

    const createClient = () =>
      createStandardMockClient({
        rpcHandler: mockRpc,
        tasksProvider: () =>
          networkOnline
            ? Promise.resolve({ data: serverTasks, error: null })
            : Promise.reject(new Error("Failed to fetch")),
        isOffline: !networkOnline,
      });

    // 1. Initial render while offline
    const client1 = createClient();
    const { unmount } = render(
      <AuthProvider client={client1}>
        <CrasApp client={client1} />
      </AuthProvider>,
    );

    const input = await screen.findByPlaceholderText(/create a task in inbox/i);
    fireEvent.change(input, { target: { value: "Offline Queued Task" } });
    fireEvent.submit(input.closest("form")!);

    // Visible in UI immediately
    expect(screen.getByText("Offline Queued Task")).toBeInTheDocument();

    // Queued in persistent Outbox
    await waitFor(() => {
      const outbox = getOutbox(operatorUser.id);
      expect(outbox.length).toBe(1);
      const item = outbox[0];
      expect(item.type).toBe("create");
      if (item.type === "create") {
        expect(item.task.title).toBe("Offline Queued Task");
      }
    });

    // 2. Simulate page reload / remount while still offline
    unmount();

    const client2 = createClient();
    render(
      <AuthProvider client={client2}>
        <CrasApp client={client2} />
      </AuthProvider>,
    );

    // Survives reload: visible immediately from outbox overlay
    await waitFor(() => {
      expect(screen.getByText("Offline Queued Task")).toBeInTheDocument();
    });

    // 3. Network reconnects
    networkOnline = true;
    act(() => {
      window.dispatchEvent(new Event("online"));
    });

    // Drains after reconnect
    await waitFor(() => {
      expect(getOutbox(operatorUser.id)).toEqual([]);
      expect(serverTasks.some((t) => t.title === "Offline Queued Task")).toBe(
        true,
      );
    });
  });

  it("uses cached effective default or Instant fallback deterministically for timed offline create", async () => {
    setCachedEffectiveTimedPlanType("floating");

    const mockRpc = vi.fn().mockImplementation(() => {
      return Promise.reject(new Error("Failed to fetch"));
    });

    const mockClient = createStandardMockClient({
      rpcHandler: mockRpc,
      isOffline: true,
    });

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    const input = await screen.findByPlaceholderText(/create a task in inbox/i);
    fireEvent.change(input, { target: { value: "Timed Offline Task" } });

    // Expand details
    const expandBtn = screen.getByRole("button", { name: /add details/i });
    fireEvent.click(expandBtn);

    // Set date and time
    const dateInput = screen.getByLabelText("Plan Date");
    fireEvent.change(dateInput, { target: { value: "2026-08-25" } });

    const timeInput = screen.getByLabelText("Plan Time");
    fireEvent.change(timeInput, { target: { value: "14:30" } });

    fireEvent.submit(input.closest("form")!);

    // Verify task entered outbox with deterministic floating plan type (from cached effective default)
    await waitFor(() => {
      const outbox = getOutbox(operatorUser.id);
      expect(outbox.length).toBe(1);
      const createdItem = outbox[0];
      expect(createdItem.type).toBe("create");
      if (createdItem.type === "create") {
        expect(createdItem.task.plan).toEqual({
          type: "floating",
          date: "2026-08-25",
          time: "14:30",
        });
      }
    });
  });

  it("falls back to instant plan type deterministically when cached effective default is absent for timed offline create", async () => {
    clearCachedEffectiveTimedPlanType();

    const mockRpc = vi.fn().mockImplementation(() => {
      return Promise.reject(new Error("Failed to fetch"));
    });

    const mockClient = createStandardMockClient({
      rpcHandler: mockRpc,
      isOffline: true,
    });

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    const input = await screen.findByPlaceholderText(/create a task in inbox/i);
    fireEvent.change(input, {
      target: { value: "Timed Offline Task Instant Fallback" },
    });

    // Expand details
    const expandBtn = screen.getByRole("button", { name: /add details/i });
    fireEvent.click(expandBtn);

    // Set date and time
    const dateInput = screen.getByLabelText("Plan Date");
    fireEvent.change(dateInput, { target: { value: "2026-08-25" } });

    const timeInput = screen.getByLabelText("Plan Time");
    fireEvent.change(timeInput, { target: { value: "14:30" } });

    fireEvent.submit(input.closest("form")!);

    // Verify task entered outbox with deterministic instant plan type fallback
    await waitFor(() => {
      const outbox = getOutbox(operatorUser.id);
      expect(outbox.length).toBe(1);
      const createdItem = outbox[0];
      expect(createdItem.type).toBe("create");
      if (createdItem.type === "create") {
        expect(createdItem.task.plan).toEqual(
          createPlanFromInputs({
            date: "2026-08-25",
            time: "14:30",
            effectiveDefault: "instant",
          }),
        );
      }
    });
  });

  it("reports version conflict on completion retry rather than merging silently", async () => {
    let callCount = 0;
    let canonicalVersion = 1;
    let canonicalTitle = "Task to complete offline";

    const baseTask: Task = {
      id: "550e8400-e29b-41d4-a716-446655440001",
      title: canonicalTitle,
      description: null,
      priority: 4,
      plan: null,
      labels: [],
      parentId: null,
      completedAt: null,
      createdAt: "2026-08-21T10:00:00.000Z",
      updatedAt: "2026-08-21T10:00:00.000Z",
      version: 1,
    };

    const mockRpc = vi.fn().mockImplementation((fnName: string) => {
      if (fnName === "complete_task") {
        callCount += 1;
        if (callCount === 1) {
          // First attempt: network error while offline
          return Promise.reject(new Error("Failed to fetch"));
        }
        // Second attempt on reconnect: server task was modified in another session (version 2)
        return Promise.resolve({
          data: null,
          error: {
            code: "P0003",
            message: "Task version conflict: expected 1, found 2",
          },
        });
      }
      return Promise.resolve({ data: null, error: null });
    });

    const mockClient = createStandardMockClient({
      rpcHandler: mockRpc,
      tasksProvider: () =>
        Promise.resolve({
          data: [
            { ...baseTask, version: canonicalVersion, title: canonicalTitle },
          ],
          error: null,
        }),
    });

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    // Wait for initial task
    await waitFor(() => {
      expect(screen.getByText("Task to complete offline")).toBeInTheDocument();
    });

    // Complete while offline
    const completeBtn = screen.getByRole("button", {
      name: /complete task task to complete offline/i,
    });
    fireEvent.click(completeBtn);

    // Completion is stored in outbox
    await waitFor(() => {
      const outbox = getOutbox(operatorUser.id);
      expect(outbox.length).toBe(1);
      expect(outbox[0].type).toBe("complete");
    });

    // Another session modified the task on server to version 2
    canonicalVersion = 2;
    canonicalTitle = "Modified in another session";

    // Simulate reconnect event
    act(() => {
      window.dispatchEvent(new Event("online"));
    });

    // Conflicts are reported rather than merged silently
    await waitFor(() => {
      expect(screen.getByText(/task version conflict/i)).toBeInTheDocument();
      // Outbox item is cleared after conflict resolution
      expect(getOutbox(operatorUser.id)).toEqual([]);
    });
  });

  it("serializes startup draining after initial load so drained created tasks are preserved in state", async () => {
    const createdServerTask: Task = {
      id: "550e8400-e29b-41d4-a716-446655440001",
      title: "Task From Startup Drain",
      description: null,
      priority: 4,
      plan: null,
      labels: [],
      parentId: null,
      completedAt: null,
      createdAt: "2026-08-21T10:00:00.000Z",
      updatedAt: "2026-08-21T10:00:00.000Z",
      version: 1,
    };

    // Pre-populate outbox before App mounts
    localStorage.setItem(
      `cras_outbox_${operatorUser.id}`,
      JSON.stringify([
        {
          id: "550e8400-e29b-41d4-a716-446655440001",
          type: "create",
          task: createdServerTask,
          params: {
            id: "550e8400-e29b-41d4-a716-446655440001",
            title: "Task From Startup Drain",
          },
          createdAt: "2026-08-21T10:00:00.000Z",
        },
      ]),
    );

    const mockRpc = vi.fn().mockImplementation((fnName: string) => {
      if (fnName === "create_task") {
        return Promise.resolve({ data: createdServerTask, error: null });
      }
      return Promise.resolve({ data: null, error: null });
    });

    // Initial fetchTasks resolves with empty list (from before drain executed)
    const mockClient = createStandardMockClient({
      rpcHandler: mockRpc,
      tasksProvider: () => Promise.resolve({ data: [], error: null }),
    });

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Task From Startup Drain")).toBeInTheDocument();
      expect(mockRpc).toHaveBeenCalledWith("create_task", expect.anything());
      expect(getOutbox(operatorUser.id)).toEqual([]);
    });
  });

  it("removes optimistic task and displays error message on permanent startup create drain failure", async () => {
    const failedCreateTask: Task = {
      id: "550e8400-e29b-41d4-a716-446655440001",
      title: "Rejected Create Task",
      description: null,
      priority: 4,
      plan: null,
      labels: [],
      parentId: null,
      completedAt: null,
      createdAt: "2026-08-21T10:00:00.000Z",
      updatedAt: "2026-08-21T10:00:00.000Z",
      version: 1,
    };

    localStorage.setItem(
      `cras_outbox_${operatorUser.id}`,
      JSON.stringify([
        {
          id: "550e8400-e29b-41d4-a716-446655440001",
          type: "create",
          task: failedCreateTask,
          params: {
            id: "550e8400-e29b-41d4-a716-446655440001",
            title: "Rejected Create Task",
          },
          createdAt: "2026-08-21T10:00:00.000Z",
        },
      ]),
    );

    // Non-network permanent rejection (e.g. permission or policy violation)
    const mockRpc = vi.fn().mockImplementation((fnName: string) => {
      if (fnName === "create_task") {
        return Promise.resolve({
          data: null,
          error: {
            code: "42501",
            message: "Permission denied for create_task",
          },
        });
      }
      return Promise.resolve({ data: null, error: null });
    });

    const mockClient = createStandardMockClient({
      rpcHandler: mockRpc,
      tasksProvider: () => Promise.resolve({ data: [], error: null }),
    });

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(
        screen.getByText(/permission denied for create_task/i),
      ).toBeInTheDocument();
      expect(
        screen.queryByText("Rejected Create Task"),
      ).not.toBeInTheDocument();
      expect(getOutbox(operatorUser.id)).toEqual([]);
    });
  });
});
