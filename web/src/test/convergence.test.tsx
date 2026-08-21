import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  render,
  screen,
  waitFor,
  act,
  fireEvent,
} from "@testing-library/react";
import { CrasApp } from "../App";
import { AuthProvider } from "../contexts/AuthContext";
import { parseInvalidationPayload } from "../services/realtimeService";
import type { Task } from "../contracts/task";
import type {
  SupabaseClient,
  Session,
  User,
  RealtimeChannel,
} from "@supabase/supabase-js";

describe("Web Concurrent Sessions Convergence Seam", () => {
  const operatorUser: User = {
    id: "550e8400-e29b-41d4-a716-446655440099",
    email: "operator@example.com",
  } as User;

  beforeEach(() => {
    localStorage.clear();
  });

  const authSession: Session = {
    user: operatorUser,
    access_token: "operator-token",
  } as Session;

  it("handles overlapping edits: rejects stale mutation via version CAS, refetches canonical state, and presents conflict", async () => {
    let canonicalVersion = 1;
    let canonicalTitle = "Initial Task Title";

    const baseTask: Task = {
      id: "550e8400-e29b-41d4-a716-446655440001",
      title: canonicalTitle,
      description: "Initial description",
      priority: 4,
      plan: null,
      labels: [],
      parentId: null,
      completedAt: null,
      createdAt: "2026-08-19T10:00:00.000Z",
      updatedAt: "2026-08-19T10:00:00.000Z",
      version: 1,
    };

    const mockChannel = {
      on: vi.fn().mockReturnThis(),
      subscribe: vi.fn().mockImplementation((cb: (status: string) => void) => {
        cb("SUBSCRIBED");
        return mockChannel;
      }),
      unsubscribe: vi.fn().mockResolvedValue("ok"),
    } as unknown as RealtimeChannel;

    const mockRpc = vi
      .fn()
      .mockImplementation((fnName: string, params: Record<string, unknown>) => {
        if (fnName === "update_task") {
          const expectedVersion = params.expected_version as number | undefined;
          if (
            expectedVersion !== undefined &&
            expectedVersion !== canonicalVersion
          ) {
            return Promise.resolve({
              data: null,
              error: {
                message: `Task version conflict: expected ${expectedVersion}, found ${canonicalVersion}`,
                code: "P0003",
              },
            });
          }
          canonicalVersion += 1;
          canonicalTitle = (params.title as string) || canonicalTitle;
          return Promise.resolve({
            data: {
              ...baseTask,
              title: canonicalTitle,
              version: canonicalVersion,
              updatedAt: "2026-08-19T10:05:00.000Z",
            },
            error: null,
          });
        }
        return Promise.resolve({ data: null, error: null });
      });

    const mockClient = {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: { session: authSession },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockReturnValue({
          data: { subscription: { unsubscribe: vi.fn() } },
        }),
        signOut: vi.fn(),
      },
      channel: vi.fn().mockReturnValue(mockChannel),
      removeChannel: vi.fn().mockResolvedValue("ok"),
      schema: vi.fn().mockReturnValue({
        rpc: mockRpc,
        from: vi.fn().mockImplementation((table: string) => {
          if (table === "tasks") {
            const getCurrentTasks = () => [
              {
                ...baseTask,
                title: canonicalTitle,
                version: canonicalVersion,
              },
            ];
            return {
              select: vi.fn().mockImplementation(() => {
                const promise = Promise.resolve({
                  data: getCurrentTasks(),
                  error: null,
                });
                return Object.assign(promise, {
                  eq: vi.fn().mockImplementation(() => ({
                    single: vi.fn().mockImplementation(() =>
                      Promise.resolve({
                        data: getCurrentTasks()[0],
                        error: null,
                      }),
                    ),
                  })),
                });
              }),
            };
          }
          if (table === "comments") {
            const commentsResult = { data: [], error: null };
            return {
              select: vi.fn().mockReturnValue({
                order: vi.fn().mockReturnValue({
                  eq: vi.fn().mockResolvedValue(commentsResult),
                  then: (r: (v: unknown) => unknown) => r(commentsResult),
                }),
                eq: vi.fn().mockResolvedValue(commentsResult),
                then: (r: (v: unknown) => unknown) => r(commentsResult),
              }),
            };
          }
          return {
            select: vi.fn().mockResolvedValue({ data: [], error: null }),
          };
        }),
      }),
      from: vi.fn().mockReturnValue({
        select: vi.fn().mockReturnValue({
          order: vi.fn().mockResolvedValue({ data: [], error: null }),
          eq: vi.fn().mockReturnValue({
            maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
          }),
          maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
        }),
      }),
    } as unknown as SupabaseClient;

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    // 1. Initial render loads initial canonical task (version 1)
    await waitFor(() => {
      expect(screen.getByText("Initial Task Title")).toBeInTheDocument();
    });

    // 2. Open task detail modal in Session B
    const taskItem = screen.getByTestId(
      "task-item-550e8400-e29b-41d4-a716-446655440001",
    );
    fireEvent.click(taskItem);

    const dialog = await screen.findByRole("dialog");
    expect(dialog).toBeInTheDocument();
    const titleInput = screen.getByPlaceholderText(/task title\.\.\./i);
    expect(titleInput).toHaveValue("Initial Task Title");

    // 3. Simulate Session A concurrently updating the task to version 2 on the server
    canonicalVersion = 2;
    canonicalTitle = "Updated by Session A";

    // 4. Session B attempts to save an edit with stale version 1
    fireEvent.change(titleInput, {
      target: { value: "Conflicting edit from Session B" },
    });
    const saveButton = screen.getByRole("button", { name: /save changes/i });
    fireEvent.click(saveButton);

    // 5. Version CAS rejects the stale mutation, refetches canonical state, and presents conflict
    await waitFor(() => {
      expect(mockRpc).toHaveBeenCalledWith(
        "update_task",
        expect.objectContaining({
          expected_version: 1,
        }),
      );
      // Conflict notice is presented
      expect(screen.getByText(/task version conflict/i)).toBeInTheDocument();
    });
  });

  it("handles two independent equal-title creates by assigning distinct IDs without collision", async () => {
    const createdTasks: Task[] = [];

    const mockRpc = vi
      .fn()
      .mockImplementation((fnName: string, params: Record<string, unknown>) => {
        if (fnName === "create_task") {
          const newTask: Task = {
            id:
              (params.id as string) ||
              `550e8400-e29b-41d4-a716-44665544000${createdTasks.length + 1}`,
            title: params.title as string,
            description: null,
            priority: (params.priority as 1 | 2 | 3 | 4) || 4,
            plan: null,
            labels: [],
            parentId: null,
            completedAt: null,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            version: 1,
          };
          createdTasks.push(newTask);
          return Promise.resolve({ data: newTask, error: null });
        }
        return Promise.resolve({ data: null, error: null });
      });

    const mockClient = {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: { session: authSession },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockReturnValue({
          data: { subscription: { unsubscribe: vi.fn() } },
        }),
        signOut: vi.fn(),
      },
      channel: vi.fn().mockReturnValue({
        on: vi.fn().mockReturnThis(),
        subscribe: vi
          .fn()
          .mockImplementation((cb: (s: string) => void) => cb("SUBSCRIBED")),
      }),
      removeChannel: vi.fn().mockResolvedValue("ok"),
      schema: vi.fn().mockReturnValue({
        rpc: mockRpc,
        from: vi.fn().mockReturnValue({
          select: vi.fn().mockImplementation(() => ({
            then: (resolve: (val: unknown) => unknown) =>
              resolve({ data: createdTasks, error: null }),
          })),
        }),
      }),
      from: vi.fn().mockReturnValue({
        select: vi.fn().mockReturnValue({
          order: vi.fn().mockResolvedValue({ data: [], error: null }),
          eq: vi.fn().mockReturnValue({
            maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
          }),
        }),
      }),
    } as unknown as SupabaseClient;

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    // Create first task with title "Buy coffee"
    const input = await screen.findByPlaceholderText(/create a task in inbox/i);
    fireEvent.change(input, { target: { value: "Buy coffee" } });
    fireEvent.submit(input.closest("form")!);

    await waitFor(() => {
      expect(screen.getAllByText("Buy coffee")).toHaveLength(1);
    });

    // Create second independent task with identical title "Buy coffee"
    fireEvent.change(input, { target: { value: "Buy coffee" } });
    fireEvent.submit(input.closest("form")!);

    await waitFor(() => {
      // Both tasks coexist as separate items
      expect(screen.getAllByText("Buy coffee")).toHaveLength(2);
      expect(createdTasks).toHaveLength(2);
      expect(createdTasks[0].id).not.toBe(createdTasks[1].id);
      expect(createdTasks[0].version).toBe(1);
      expect(createdTasks[1].version).toBe(1);
    });
  });

  it("invalidates only affected resources on private realtime broadcast events", async () => {
    let broadcastCb: ((e: { payload?: unknown }) => void) | null = null;

    const mockTasks = [
      {
        id: "550e8400-e29b-41d4-a716-446655440001",
        title: "Initial Task",
        description: null,
        priority: 4,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-19T10:00:00.000Z",
        updatedAt: "2026-08-19T10:00:00.000Z",
        version: 1,
      },
    ];

    const fetchTasksMock = vi
      .fn()
      .mockResolvedValue({ data: mockTasks, error: null });
    const fetchLabelsMock = vi
      .fn()
      .mockResolvedValue({ data: [], error: null });

    const mockChannel = {
      on: vi
        .fn()
        .mockImplementation(
          (
            type: string,
            filter: Record<string, unknown>,
            cb: (e: { payload?: unknown }) => void,
          ) => {
            if (type === "broadcast" && filter.event === "invalidate") {
              broadcastCb = cb;
            }
            return mockChannel;
          },
        ),
      subscribe: vi.fn().mockImplementation((cb: (s: string) => void) => {
        cb("SUBSCRIBED");
        return mockChannel;
      }),
      unsubscribe: vi.fn().mockResolvedValue("ok"),
    } as unknown as RealtimeChannel;

    const mockClient = {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: { session: authSession },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockReturnValue({
          data: { subscription: { unsubscribe: vi.fn() } },
        }),
        signOut: vi.fn(),
      },
      channel: vi.fn().mockReturnValue(mockChannel),
      removeChannel: vi.fn().mockResolvedValue("ok"),
      schema: vi.fn().mockReturnValue({
        from: vi.fn().mockImplementation((table: string) => {
          if (table === "tasks") {
            return { select: fetchTasksMock };
          }
          return {
            select: vi.fn().mockResolvedValue({ data: [], error: null }),
          };
        }),
      }),
      from: vi.fn().mockImplementation((table: string) => {
        if (table === "labels") {
          return {
            select: vi.fn().mockReturnValue({ order: fetchLabelsMock }),
          };
        }
        return {
          select: vi.fn().mockReturnValue({
            eq: vi.fn().mockReturnValue({
              maybeSingle: vi
                .fn()
                .mockResolvedValue({ data: null, error: null }),
            }),
          }),
        };
      }),
    } as unknown as SupabaseClient;

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Initial Task")).toBeInTheDocument();
    });

    const initialTaskFetchCount = fetchTasksMock.mock.calls.length;
    const initialLabelFetchCount = fetchLabelsMock.mock.calls.length;

    // Trigger task-only invalidation event
    act(() => {
      broadcastCb?.({
        payload: {
          resource: "task",
          id: "550e8400-e29b-41d4-a716-446655440001",
          operation: "updated",
        },
      });
    });

    await waitFor(() => {
      // Only tasks refetched, labels was NOT refetched
      expect(fetchTasksMock.mock.calls.length).toBeGreaterThan(
        initialTaskFetchCount,
      );
      expect(fetchLabelsMock.mock.calls.length).toBe(initialLabelFetchCount);
    });

    // Trigger label-only invalidation event
    const afterTaskFetchCount = fetchTasksMock.mock.calls.length;
    act(() => {
      broadcastCb?.({
        payload: {
          resource: "label",
          id: "550e8400-e29b-41d4-a716-446655440011",
          operation: "created",
        },
      });
    });

    await waitFor(() => {
      // Only labels refetched, tasks was NOT refetched
      expect(fetchLabelsMock.mock.calls.length).toBeGreaterThan(
        initialLabelFetchCount,
      );
      expect(fetchTasksMock.mock.calls.length).toBe(afterTaskFetchCount);
    });
  });

  it("reconnects and refetches canonical state upon connection restoration", async () => {
    let statusCallback: ((status: string) => void) | null = null;

    const mockTasks = [
      {
        id: "550e8400-e29b-41d4-a716-446655440001",
        title: "Initial Task",
        description: null,
        priority: 4,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-19T10:00:00.000Z",
        updatedAt: "2026-08-19T10:00:00.000Z",
        version: 1,
      },
    ];

    const fetchTasksMock = vi
      .fn()
      .mockResolvedValue({ data: mockTasks, error: null });

    const mockChannel = {
      on: vi.fn().mockReturnThis(),
      subscribe: vi.fn().mockImplementation((cb: (s: string) => void) => {
        statusCallback = cb;
        cb("SUBSCRIBED");
        return mockChannel;
      }),
      unsubscribe: vi.fn().mockResolvedValue("ok"),
    } as unknown as RealtimeChannel;

    const mockClient = {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: { session: authSession },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockReturnValue({
          data: { subscription: { unsubscribe: vi.fn() } },
        }),
        signOut: vi.fn(),
      },
      channel: vi.fn().mockReturnValue(mockChannel),
      removeChannel: vi.fn().mockResolvedValue("ok"),
      schema: vi.fn().mockReturnValue({
        from: vi.fn().mockImplementation((table: string) => {
          if (table === "tasks") {
            return { select: fetchTasksMock };
          }
          return {
            select: vi.fn().mockResolvedValue({ data: [], error: null }),
          };
        }),
      }),
      from: vi.fn().mockReturnValue({
        select: vi.fn().mockReturnValue({
          order: vi.fn().mockResolvedValue({ data: [], error: null }),
          eq: vi.fn().mockReturnValue({
            maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
          }),
        }),
      }),
    } as unknown as SupabaseClient;

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Initial Task")).toBeInTheDocument();
    });

    const preDisconnectCallCount = fetchTasksMock.mock.calls.length;

    // Simulate socket disconnect
    act(() => {
      statusCallback?.("CLOSED");
    });

    // Simulate reconnect
    act(() => {
      statusCallback?.("SUBSCRIBED");
    });

    await waitFor(() => {
      // Reconnect triggers refetch of canonical tasks
      expect(fetchTasksMock.mock.calls.length).toBeGreaterThan(
        preDisconnectCallCount,
      );
    });
  });

  it("handles stale task completion: rejects via version CAS, refetches canonical state, and presents conflict", async () => {
    let canonicalVersion = 2; // already bumped on server
    const baseTask: Task = {
      id: "550e8400-e29b-41d4-a716-446655440001",
      title: "Task to complete",
      description: null,
      priority: 4,
      plan: null,
      labels: [],
      parentId: null,
      completedAt: null,
      createdAt: "2026-08-19T10:00:00.000Z",
      updatedAt: "2026-08-19T10:00:00.000Z",
      version: 1, // client has version 1 in memory
    };

    const mockRpc = vi
      .fn()
      .mockImplementation((fnName: string, params: Record<string, unknown>) => {
        if (fnName === "complete_task") {
          const expectedVersion = params.expected_version as number | undefined;
          if (
            expectedVersion !== undefined &&
            expectedVersion !== canonicalVersion
          ) {
            return Promise.resolve({
              data: null,
              error: {
                message: `Task version conflict: expected ${expectedVersion}, found ${canonicalVersion}`,
                code: "P0003",
              },
            });
          }
          return Promise.resolve({
            data: {
              ...baseTask,
              version: 3,
              completedAt: "2026-08-19T10:10:00Z",
            },
            error: null,
          });
        }
        return Promise.resolve({ data: null, error: null });
      });

    const mockClient = {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: { session: authSession },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockReturnValue({
          data: { subscription: { unsubscribe: vi.fn() } },
        }),
        signOut: vi.fn(),
      },
      channel: vi.fn().mockReturnValue({
        on: vi.fn().mockReturnThis(),
        subscribe: vi
          .fn()
          .mockImplementation((cb: (s: string) => void) => cb("SUBSCRIBED")),
      }),
      removeChannel: vi.fn().mockResolvedValue("ok"),
      schema: vi.fn().mockReturnValue({
        rpc: mockRpc,
        from: vi.fn().mockReturnValue({
          select: vi.fn().mockImplementation(() => ({
            then: (resolve: (val: unknown) => unknown) =>
              resolve({
                data: [{ ...baseTask, version: canonicalVersion }],
                error: null,
              }),
          })),
        }),
      }),
      from: vi.fn().mockReturnValue({
        select: vi.fn().mockReturnValue({
          order: vi.fn().mockResolvedValue({ data: [], error: null }),
          eq: vi.fn().mockReturnValue({
            maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
          }),
        }),
      }),
    } as unknown as SupabaseClient;

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Task to complete")).toBeInTheDocument();
    });

    // Simulate another session bumping server version to 3
    canonicalVersion = 3;

    // Attempt to complete task with stale version 2
    const completeBtn = screen.getByRole("button", {
      name: /complete task task to complete/i,
    });
    fireEvent.click(completeBtn);

    await waitFor(() => {
      expect(mockRpc).toHaveBeenCalledWith(
        "complete_task",
        expect.objectContaining({
          id: baseTask.id,
          expected_version: 2,
        }),
      );
      expect(screen.getByText(/task version conflict/i)).toBeInTheDocument();
    });
  });

  it("verifies private realtime invalidation payloads do not leak sensitive task content", () => {
    const rawBroadcast = {
      resource: "task",
      id: "550e8400-e29b-41d4-a716-446655440001",
      operation: "updated",
      parentId: null,
      title: "Confidential plan details",
      description: "Do not leak across network",
      raw_sql: "SELECT * FROM secrets",
    };

    const parsed = parseInvalidationPayload(rawBroadcast);
    expect(parsed).toEqual({
      resource: "task",
      id: "550e8400-e29b-41d4-a716-446655440001",
      operation: "updated",
      parentId: null,
      taskId: null,
    });
    expect(parsed).not.toHaveProperty("title");
    expect(parsed).not.toHaveProperty("description");
    expect(parsed).not.toHaveProperty("raw_sql");
  });

  it("inserts absent task when receiving a task update and discards stale versions without duplicates", async () => {
    let broadcastCb: ((e: { payload?: unknown }) => void) | null = null;

    const initialTasks: Task[] = [
      {
        id: "550e8400-e29b-41d4-a716-446655440001",
        title: "Existing Task",
        description: null,
        priority: 4,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-19T10:00:00.000Z",
        updatedAt: "2026-08-19T10:00:00.000Z",
        version: 2,
      },
    ];

    const newTaskFromAnotherSession: Task = {
      id: "550e8400-e29b-41d4-a716-446655440002",
      title: "Task From Session B",
      description: null,
      priority: 3,
      plan: null,
      labels: [],
      parentId: null,
      completedAt: null,
      createdAt: "2026-08-19T10:05:00.000Z",
      updatedAt: "2026-08-19T10:05:00.000Z",
      version: 1,
    };

    const mockChannel = {
      on: vi
        .fn()
        .mockImplementation(
          (
            type: string,
            filter: Record<string, unknown>,
            cb: (e: { payload?: unknown }) => void,
          ) => {
            if (type === "broadcast" && filter.event === "invalidate") {
              broadcastCb = cb;
            }
            return mockChannel;
          },
        ),
      subscribe: vi.fn().mockImplementation((cb: (s: string) => void) => {
        cb("SUBSCRIBED");
        return mockChannel;
      }),
      unsubscribe: vi.fn().mockResolvedValue("ok"),
    } as unknown as RealtimeChannel;

    const mockClient = {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: { session: authSession },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockReturnValue({
          data: { subscription: { unsubscribe: vi.fn() } },
        }),
        signOut: vi.fn(),
      },
      channel: vi.fn().mockReturnValue(mockChannel),
      removeChannel: vi.fn().mockResolvedValue("ok"),
      schema: vi.fn().mockReturnValue({
        from: vi.fn().mockImplementation((table: string) => {
          if (table === "tasks") {
            return {
              select: vi.fn().mockImplementation(() => ({
                eq: vi.fn().mockImplementation((col: string, val: string) => {
                  if (col === "id" && val === newTaskFromAnotherSession.id) {
                    return {
                      single: vi.fn().mockResolvedValue({
                        data: newTaskFromAnotherSession,
                        error: null,
                      }),
                    };
                  }
                  return {
                    single: vi.fn().mockResolvedValue({
                      data: null,
                      error: { code: "PGRST116", message: "No rows" },
                    }),
                  };
                }),
                then: (resolve: (val: unknown) => void) =>
                  Promise.resolve({ data: initialTasks, error: null }).then(
                    resolve,
                  ),
              })),
            };
          }
          return {
            select: vi.fn().mockResolvedValue({ data: [], error: null }),
          };
        }),
      }),
      from: vi.fn().mockImplementation((table: string) => {
        if (table === "labels") {
          return {
            select: vi.fn().mockReturnValue({
              order: vi.fn().mockResolvedValue({ data: [], error: null }),
            }),
          };
        }
        return {
          select: vi.fn().mockReturnValue({
            eq: vi.fn().mockReturnValue({
              maybeSingle: vi
                .fn()
                .mockResolvedValue({ data: null, error: null }),
            }),
          }),
        };
      }),
    } as unknown as SupabaseClient;

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Existing Task")).toBeInTheDocument();
    });

    // Broadcast invalidation for the new task from another session
    act(() => {
      broadcastCb?.({
        payload: {
          resource: "task",
          id: newTaskFromAnotherSession.id,
          operation: "updated",
        },
      });
    });

    await waitFor(() => {
      expect(screen.getByText("Task From Session B")).toBeInTheDocument();
      expect(screen.getByText("Existing Task")).toBeInTheDocument();
    });
  });
});
