import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  render,
  screen,
  waitFor,
  fireEvent,
  within,
} from "@testing-library/react";
import { AuthProvider } from "../contexts/AuthContext";
import { CrasApp } from "../App";
import { getPublicSupabaseConfig } from "../config/supabase";
import type { SupabaseClient, Session, User } from "@supabase/supabase-js";
import type {
  Task,
  Plan,
  Comment as TaskCommentContract,
} from "../contracts/task";
import { getDeviceLocalDate } from "../services/temporalService";

describe("Black-box Acceptance & Isolation Suite - Web Client (Issues #39, #41, #43, & #45)", () => {
  // In-memory simulated Postgres database for black-box testing
  interface DbRow {
    id: string;
    operator_id: string;
    title: string;
    description: string | null;
    priority: number;
    plan: Plan;
    parent_id: string | null;
    completed_at: string | null;
    created_at: string;
    updated_at: string;
    version: number;
  }

  interface DbLabelRow {
    id: string;
    operator_id: string;
    name: string;
    color: string;
    created_at: string;
    updated_at: string;
  }

  interface DbTaskLabelRow {
    task_id: string;
    label_id: string;
    operator_id: string;
  }

  interface DbCommentRow {
    id: string;
    task_id: string;
    operator_id: string;
    content: string;
    created_at: string;
  }

  let dbTasks: DbRow[];
  let dbLabels: DbLabelRow[];
  let dbTaskLabels: DbTaskLabelRow[];
  let dbComments: DbCommentRow[];

  function createMockSupabaseForOperator(
    currentUser: User | null,
  ): SupabaseClient {
    return {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: {
            session: currentUser
              ? ({
                  user: currentUser,
                  access_token: `token-${currentUser.id}`,
                } as Session)
              : null,
          },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockImplementation((cb) => {
          if (currentUser) {
            cb("SIGNED_IN", {
              user: currentUser,
              access_token: `token-${currentUser.id}`,
            } as Session);
          }
          return {
            data: { subscription: { unsubscribe: vi.fn() } },
          };
        }),
        signInWithOAuth: vi
          .fn()
          .mockResolvedValue({ data: { provider: "google" }, error: null }),
        signOut: vi.fn().mockResolvedValue({ error: null }),
      },
      from: (tableName: string) => {
        if (tableName === "settings") {
          return {
            select: vi.fn().mockImplementation(() => ({
              maybeSingle: vi.fn().mockResolvedValue({
                data: null,
                error: null,
              }),
            })),
            upsert: vi.fn().mockResolvedValue({ error: null }),
          };
        }
        if (tableName === "deployment_config") {
          return {
            select: vi.fn().mockImplementation(() => ({
              maybeSingle: vi.fn().mockResolvedValue({
                data: { default_timed_plan_type: "instant" },
                error: null,
              }),
            })),
          };
        }
        if (tableName !== "labels")
          throw new Error(`Unknown public table: ${tableName}`);

        return {
          select: vi.fn().mockImplementation(() => {
            return {
              order: vi.fn().mockImplementation(async () => {
                if (!currentUser) {
                  return {
                    data: null,
                    error: { message: "Unauthorized", code: "42501" },
                  };
                }
                const userLabels = dbLabels.filter(
                  (l) => l.operator_id === currentUser.id,
                );
                return { data: [...userLabels], error: null };
              }),
            };
          }),
          insert: vi
            .fn()
            .mockImplementation((payload: Record<string, unknown>) => {
              return {
                select: vi.fn().mockReturnValue({
                  single: vi.fn().mockImplementation(async () => {
                    if (!currentUser) {
                      return {
                        data: null,
                        error: { message: "Unauthorized", code: "42501" },
                      };
                    }
                    const name = (payload.name as string).trim();
                    const color = (payload.color as string).trim();
                    if (!name) {
                      return {
                        data: null,
                        error: {
                          message: "Label name cannot be empty",
                          code: "23514",
                        },
                      };
                    }
                    // Check unique constraint: (name, operator_id)
                    const exists = dbLabels.some(
                      (l) =>
                        l.operator_id === currentUser.id && l.name === name,
                    );
                    if (exists) {
                      return {
                        data: null,
                        error: {
                          message:
                            'duplicate key value violates unique constraint "uq_labels_name_operator"',
                          code: "23505",
                        },
                      };
                    }
                    const now = new Date().toISOString();
                    const newLabel: DbLabelRow = {
                      id: (payload.id as string) || crypto.randomUUID(),
                      operator_id: currentUser.id,
                      name,
                      color,
                      created_at: now,
                      updated_at: now,
                    };
                    dbLabels.push(newLabel);
                    return { data: newLabel, error: null };
                  }),
                }),
              };
            }),
          update: vi
            .fn()
            .mockImplementation((payload: Record<string, unknown>) => {
              return {
                eq: vi.fn().mockImplementation((_col: string, val: string) => {
                  const runUpdate = async () => {
                    if (!currentUser) {
                      return {
                        data: null,
                        error: { message: "Unauthorized", code: "42501" },
                      };
                    }
                    const index = dbLabels.findIndex(
                      (l) => l.id === val && l.operator_id === currentUser.id,
                    );
                    if (index === -1) {
                      return {
                        data: null,
                        error: {
                          message: "Label not found or unauthorized",
                          code: "P0002",
                        },
                      };
                    }
                    const existing = dbLabels[index];
                    const newName =
                      payload.name !== undefined
                        ? (payload.name as string).trim()
                        : existing.name;
                    const newColor =
                      payload.color !== undefined
                        ? (payload.color as string).trim()
                        : existing.color;

                    // Check unique constraint on rename
                    if (newName !== existing.name) {
                      const duplicate = dbLabels.some(
                        (l) =>
                          l.id !== val &&
                          l.operator_id === currentUser.id &&
                          l.name === newName,
                      );
                      if (duplicate) {
                        return {
                          data: null,
                          error: {
                            message:
                              'duplicate key value violates unique constraint "uq_labels_name_operator"',
                            code: "23505",
                          },
                        };
                      }
                    }

                    const updated: DbLabelRow = {
                      ...existing,
                      name: newName,
                      color: newColor,
                      updated_at: new Date().toISOString(),
                    };
                    dbLabels[index] = updated;
                    return { data: updated, error: null };
                  };

                  return {
                    then: (
                      resolve: (v: unknown) => unknown,
                      reject?: (reason: unknown) => unknown,
                    ) => runUpdate().then(resolve, reject),
                    select: vi.fn().mockReturnValue({
                      single: vi.fn().mockImplementation(runUpdate),
                    }),
                  };
                }),
              };
            }),
          delete: vi.fn().mockImplementation(() => {
            return {
              eq: vi.fn().mockImplementation((_col: string, val: string) => {
                const runDelete = async () => {
                  if (!currentUser) {
                    return {
                      data: null,
                      error: { message: "Unauthorized", code: "42501" },
                    };
                  }
                  dbLabels = dbLabels.filter(
                    (l) => !(l.id === val && l.operator_id === currentUser.id),
                  );
                  // Cascade delete from task_labels
                  dbTaskLabels = dbTaskLabels.filter(
                    (tl) =>
                      !(
                        tl.label_id === val && tl.operator_id === currentUser.id
                      ),
                  );
                  return { data: null, error: null };
                };
                return {
                  then: (
                    resolve: (v: unknown) => unknown,
                    reject?: (reason: unknown) => unknown,
                  ) => runDelete().then(resolve, reject),
                };
              }),
            };
          }),
        };
      },
      schema: (schemaName: string) => {
        if (schemaName !== "api")
          throw new Error(`Access denied to non-api schema: ${schemaName}`);
        return {
          from: (tableName: string) => {
            if (tableName === "comments") {
              return {
                select: vi.fn().mockImplementation(() => {
                  const runQuery = async () => {
                    if (!currentUser) {
                      return {
                        data: null,
                        error: { message: "Unauthorized", code: "42501" },
                      };
                    }
                    const userComments = dbComments.filter(
                      (c) => c.operator_id === currentUser.id,
                    );
                    const mapped: TaskCommentContract[] = userComments.map(
                      (c) => ({
                        id: c.id,
                        taskId: c.task_id,
                        content: c.content,
                        createdAt: c.created_at,
                      }),
                    );
                    return { data: mapped, error: null };
                  };
                  const makeChain = (
                    filterFn?: (item: TaskCommentContract) => boolean,
                  ) => {
                    const execute = async () => {
                      const base = await runQuery();
                      if (base.error || !base.data) return base;
                      let data = base.data;
                      if (filterFn) {
                        data = data.filter(filterFn);
                      }
                      data = [...data].sort(
                        (a, b) =>
                          new Date(a.createdAt).getTime() -
                          new Date(b.createdAt).getTime(),
                      );
                      return { data, error: null };
                    };
                    return {
                      eq: vi
                        .fn()
                        .mockImplementation((col: string, val: string) => {
                          return makeChain(
                            (c) =>
                              (c as unknown as Record<string, string>)[col] ===
                              val,
                          );
                        }),
                      order: vi.fn().mockImplementation(() => {
                        return makeChain(filterFn);
                      }),
                      then: (
                        resolve: (v: unknown) => unknown,
                        reject?: (reason: unknown) => unknown,
                      ) => execute().then(resolve, reject),
                    };
                  };
                  return makeChain();
                }),
              };
            }

            if (tableName !== "tasks")
              throw new Error(`Unknown table in api: ${tableName}`);
            return {
              select: vi.fn().mockImplementation(async () => {
                if (!currentUser) {
                  return {
                    data: null,
                    error: { message: "Unauthorized", code: "42501" },
                  };
                }
                // RLS: operator_id = auth.uid()
                const userTasks = dbTasks.filter(
                  (t) => t.operator_id === currentUser.id,
                );
                const mapped: Task[] = userTasks.map((t) => {
                  const taskLabelIds = dbTaskLabels
                    .filter(
                      (tl) =>
                        tl.task_id === t.id &&
                        tl.operator_id === currentUser.id,
                    )
                    .map((tl) => tl.label_id);
                  return {
                    id: t.id,
                    title: t.title,
                    description: t.description,
                    priority: t.priority as 1 | 2 | 3 | 4,
                    plan: t.plan,
                    labels: taskLabelIds,
                    parentId: t.parent_id,
                    completedAt: t.completed_at,
                    createdAt: t.created_at,
                    updatedAt: t.updated_at,
                    version: t.version,
                  };
                });
                return { data: mapped, error: null };
              }),
            };
          },
          rpc: (fnName: string, params: Record<string, unknown>) => {
            if (!currentUser) {
              return Promise.resolve({
                data: null,
                error: { message: "Unauthorized", code: "42501" },
              });
            }

            const now = new Date().toISOString();

            if (fnName === "create_comment") {
              const contentParam =
                typeof params.content === "string" ? params.content.trim() : "";
              if (!contentParam) {
                return Promise.resolve({
                  data: null,
                  error: {
                    message: "Comment content cannot be empty",
                    code: "23514",
                  },
                });
              }

              const taskId = params.task_id as string;
              const targetTask = dbTasks.find(
                (t) => t.id === taskId && t.operator_id === currentUser.id,
              );
              if (!targetTask) {
                return Promise.resolve({
                  data: null,
                  error: {
                    message: "Task not found or unauthorized",
                    code: "P0001",
                  },
                });
              }

              const newComment: DbCommentRow = {
                id: (params.id as string) || crypto.randomUUID(),
                task_id: taskId,
                operator_id: currentUser.id,
                content: contentParam,
                created_at: now,
              };
              dbComments.push(newComment);

              return Promise.resolve({
                data: {
                  id: newComment.id,
                  taskId: newComment.task_id,
                  content: newComment.content,
                  createdAt: newComment.created_at,
                },
                error: null,
              });
            }

            if (fnName === "create_task") {
              const titleParam =
                typeof params.title === "string" ? params.title : "";
              if (titleParam.trim().length === 0) {
                return Promise.resolve({
                  data: null,
                  error: {
                    message: "Task title cannot be empty",
                    code: "23514",
                  },
                });
              }

              const parentId = (params.parent_id as string | null) ?? null;
              if (parentId !== null) {
                const parentTask = dbTasks.find(
                  (t) => t.id === parentId && t.operator_id === currentUser.id,
                );
                if (!parentTask) {
                  return Promise.resolve({
                    data: null,
                    error: {
                      message:
                        'insert or update on table "tasks" violates foreign key constraint "fk_tasks_parent"',
                      code: "23503",
                    },
                  });
                }
                if (parentTask.parent_id !== null) {
                  return Promise.resolve({
                    data: null,
                    error: {
                      message:
                        "Subtasks cannot have children (one-level nesting only)",
                      code: "P0001",
                    },
                  });
                }
              }

              const newId =
                typeof params.id === "string" ? params.id : crypto.randomUUID();
              const newRow: DbRow = {
                id: newId,
                operator_id: currentUser.id, // Derived strictly from auth.uid()
                title: titleParam.trim(),
                description: (params.description as string | null) ?? null,
                priority:
                  typeof params.priority === "number" ? params.priority : 4,
                plan: (params.plan as Plan) ?? null,
                parent_id: parentId,
                completed_at: null,
                created_at: now,
                updated_at: now,
                version: 1,
              };

              const labelIds = (params.labels as string[] | undefined) ?? [];
              // Enforce foreign key constraints and operator isolation on labels
              for (const labelId of labelIds) {
                const labelExistsForOp = dbLabels.some(
                  (l) => l.id === labelId && l.operator_id === currentUser.id,
                );
                if (!labelExistsForOp) {
                  return Promise.resolve({
                    data: null,
                    error: {
                      message:
                        'insert or update on table "task_labels" violates foreign key constraint "fk_task_labels_label"',
                      code: "23503",
                    },
                  });
                }
              }

              dbTasks.push(newRow);

              for (const labelId of labelIds) {
                dbTaskLabels.push({
                  task_id: newId,
                  label_id: labelId,
                  operator_id: currentUser.id,
                });
              }

              const resultTask: Task = {
                id: newRow.id,
                title: newRow.title,
                description: newRow.description,
                priority: newRow.priority as 1 | 2 | 3 | 4,
                plan: newRow.plan,
                labels: labelIds,
                parentId: newRow.parent_id,
                completedAt: newRow.completed_at,
                createdAt: newRow.created_at,
                updatedAt: newRow.updated_at,
                version: newRow.version,
              };

              return Promise.resolve({ data: resultTask, error: null });
            }

            if (fnName === "update_task") {
              const taskId = params.id as string;
              const taskIndex = dbTasks.findIndex(
                (t) => t.id === taskId && t.operator_id === currentUser.id,
              );

              if (taskIndex === -1) {
                return Promise.resolve({
                  data: null,
                  error: {
                    message: "Task not found or unauthorized",
                    code: "P0002",
                  },
                });
              }

              const existing = dbTasks[taskIndex];

              // Acceptance criterion: A completed Task must be uncompleted before its fields can be edited.
              if (existing.completed_at !== null) {
                return Promise.resolve({
                  data: null,
                  error: {
                    message:
                      "Completed tasks cannot be edited. Uncomplete first.",
                    code: "P0001",
                  },
                });
              }

              if (
                params.expected_version !== undefined &&
                params.expected_version !== null &&
                existing.version !== params.expected_version
              ) {
                return Promise.resolve({
                  data: null,
                  error: { message: "Task version conflict", code: "P0003" },
                });
              }

              if (
                params.title !== undefined &&
                params.title !== null &&
                (params.title as string).trim().length === 0
              ) {
                return Promise.resolve({
                  data: null,
                  error: {
                    message: "Task title cannot be empty",
                    code: "23514",
                  },
                });
              }

              if (params.parent_id !== undefined && params.parent_id !== null) {
                const parentTask = dbTasks.find(
                  (t) =>
                    t.id === params.parent_id &&
                    t.operator_id === currentUser.id,
                );
                if (!parentTask) {
                  return Promise.resolve({
                    data: null,
                    error: {
                      message:
                        'insert or update on table "tasks" violates foreign key constraint "fk_tasks_parent"',
                      code: "23503",
                    },
                  });
                }
                if (parentTask.parent_id !== null) {
                  return Promise.resolve({
                    data: null,
                    error: {
                      message:
                        "Subtasks cannot have children (one-level nesting only)",
                      code: "P0001",
                    },
                  });
                }
                const hasChildren = dbTasks.some(
                  (t) =>
                    t.parent_id === taskId && t.operator_id === currentUser.id,
                );
                if (hasChildren) {
                  return Promise.resolve({
                    data: null,
                    error: {
                      message:
                        "Subtasks cannot have children (one-level nesting only)",
                      code: "P0001",
                    },
                  });
                }
              }

              const updatedRow: DbRow = {
                ...existing,
                title:
                  params.title !== undefined && params.title !== null
                    ? (params.title as string).trim()
                    : existing.title,
                description:
                  params.clear_description === true
                    ? null
                    : params.description !== undefined &&
                        params.description !== null
                      ? (params.description as string)
                      : existing.description,
                priority:
                  params.priority !== undefined && params.priority !== null
                    ? (params.priority as number)
                    : existing.priority,
                plan:
                  params.clear_plan === true
                    ? null
                    : params.plan !== undefined && params.plan !== null
                      ? (params.plan as Plan)
                      : existing.plan,
                parent_id:
                  params.parent_id !== undefined && params.parent_id !== null
                    ? (params.parent_id as string)
                    : existing.parent_id,
                updated_at: now,
                version: existing.version + 1,
              };

              if (params.labels !== undefined && params.labels !== null) {
                const labelIds = params.labels as string[];
                // Verify all labels belong to current operator
                for (const labelId of labelIds) {
                  const labelExistsForOp = dbLabels.some(
                    (l) => l.id === labelId && l.operator_id === currentUser.id,
                  );
                  if (!labelExistsForOp) {
                    return Promise.resolve({
                      data: null,
                      error: {
                        message:
                          'insert or update on table "task_labels" violates foreign key constraint "fk_task_labels_label"',
                        code: "23503",
                      },
                    });
                  }
                }
                // Delete old associations
                dbTaskLabels = dbTaskLabels.filter(
                  (tl) =>
                    !(
                      tl.task_id === taskId && tl.operator_id === currentUser.id
                    ),
                );
                // Insert new associations
                for (const labelId of labelIds) {
                  dbTaskLabels.push({
                    task_id: taskId,
                    label_id: labelId,
                    operator_id: currentUser.id,
                  });
                }
              }

              dbTasks[taskIndex] = updatedRow;

              const currentLabelIds = dbTaskLabels
                .filter(
                  (tl) =>
                    tl.task_id === taskId && tl.operator_id === currentUser.id,
                )
                .map((tl) => tl.label_id);

              const resultTask: Task = {
                id: updatedRow.id,
                title: updatedRow.title,
                description: updatedRow.description,
                priority: updatedRow.priority as 1 | 2 | 3 | 4,
                plan: updatedRow.plan,
                labels: currentLabelIds,
                parentId: updatedRow.parent_id,
                completedAt: updatedRow.completed_at,
                createdAt: updatedRow.created_at,
                updatedAt: updatedRow.updated_at,
                version: updatedRow.version,
              };

              return Promise.resolve({ data: resultTask, error: null });
            }

            if (fnName === "complete_task") {
              const taskId = params.id as string;
              const taskIndex = dbTasks.findIndex(
                (t) => t.id === taskId && t.operator_id === currentUser.id,
              );

              if (taskIndex === -1) {
                return Promise.resolve({
                  data: null,
                  error: {
                    message: "Task not found or unauthorized",
                    code: "P0002",
                  },
                });
              }

              const existing = dbTasks[taskIndex];
              const completedAt =
                (params.completed_at as string | undefined) ?? now;

              const updatedRow: DbRow = {
                ...existing,
                completed_at: completedAt,
                updated_at: now,
                version: existing.version + 1,
              };

              dbTasks[taskIndex] = updatedRow;

              const currentLabelIds = dbTaskLabels
                .filter(
                  (tl) =>
                    tl.task_id === taskId && tl.operator_id === currentUser.id,
                )
                .map((tl) => tl.label_id);

              const resultTask: Task = {
                id: updatedRow.id,
                title: updatedRow.title,
                description: updatedRow.description,
                priority: updatedRow.priority as 1 | 2 | 3 | 4,
                plan: updatedRow.plan,
                labels: currentLabelIds,
                parentId: updatedRow.parent_id,
                completedAt: updatedRow.completed_at,
                createdAt: updatedRow.created_at,
                updatedAt: updatedRow.updated_at,
                version: updatedRow.version,
              };

              return Promise.resolve({ data: resultTask, error: null });
            }

            if (fnName === "uncomplete_task") {
              const taskId = params.id as string;
              const taskIndex = dbTasks.findIndex(
                (t) => t.id === taskId && t.operator_id === currentUser.id,
              );

              if (taskIndex === -1) {
                return Promise.resolve({
                  data: null,
                  error: {
                    message: "Task not found or unauthorized",
                    code: "P0002",
                  },
                });
              }

              const existing = dbTasks[taskIndex];

              const updatedRow: DbRow = {
                ...existing,
                completed_at: null,
                updated_at: now,
                version: existing.version + 1,
              };

              dbTasks[taskIndex] = updatedRow;

              const currentLabelIds = dbTaskLabels
                .filter(
                  (tl) =>
                    tl.task_id === taskId && tl.operator_id === currentUser.id,
                )
                .map((tl) => tl.label_id);

              const resultTask: Task = {
                id: updatedRow.id,
                title: updatedRow.title,
                description: updatedRow.description,
                priority: updatedRow.priority as 1 | 2 | 3 | 4,
                plan: updatedRow.plan,
                labels: currentLabelIds,
                parentId: updatedRow.parent_id,
                completedAt: updatedRow.completed_at,
                createdAt: updatedRow.created_at,
                updatedAt: updatedRow.updated_at,
                version: updatedRow.version,
              };

              return Promise.resolve({ data: resultTask, error: null });
            }

            throw new Error(`Unknown RPC: ${fnName}`);
          },
        };
      },
    } as unknown as SupabaseClient;
  }

  beforeEach(() => {
    dbTasks = [];
    dbLabels = [];
    dbTaskLabels = [];
    dbComments = [];
  });

  it("proves Criterion 4: public configuration contains only project URL and publishable key", () => {
    const config = getPublicSupabaseConfig({
      VITE_SUPABASE_URL: "https://demo-cras.supabase.co",
      VITE_SUPABASE_ANON_KEY: "sb_publishable_anon_token_example",
    });

    expect(config.url).toBe("https://demo-cras.supabase.co");
    expect(config.publishableKey).toBe("sb_publishable_anon_token_example");
    // Ensure no secret_role or internal DB passwords exist
    expect(config).not.toHaveProperty("serviceRoleKey");
    expect(config).not.toHaveProperty("secretKey");
  });

  it("proves Criterion 1 & Journey: unauthenticated user sees branded Continue with Google and returning session restores", async () => {
    // 1. Unauthenticated client
    const unauthedClient = createMockSupabaseForOperator(null);
    render(
      <AuthProvider client={unauthedClient}>
        <CrasApp client={unauthedClient} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /continue with google/i }),
      ).toBeInTheDocument();
    });

    // 2. Returning session restores automatically
    const returningOperator: User = {
      id: "operator-1-uuid",
      email: "operator1@example.com",
    } as User;

    const authedClient = createMockSupabaseForOperator(returningOperator);
    render(
      <AuthProvider client={authedClient}>
        <CrasApp client={authedClient} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("operator1@example.com")).toBeInTheDocument();
      expect(
        screen.getByRole("heading", { level: 2, name: /inbox/i }),
      ).toBeInTheDocument();
      expect(screen.getByText(/no tasks in inbox/i)).toBeInTheDocument();
    });
  });

  it("proves Issue #41 Criterion 1: Operator can add and edit an optional Description and one of four Priority levels or none", async () => {
    const op1: User = { id: "op-1", email: "alice@example.com" } as User;
    const client = createMockSupabaseForOperator(op1);

    render(
      <AuthProvider client={client}>
        <CrasApp client={client} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("alice@example.com")).toBeInTheDocument();
    });

    // 1. Create a task with description and priority level 2 (High)
    const expandBtn = screen.getByRole("button", { name: /add details/i });
    fireEvent.click(expandBtn);

    const titleInput = screen.getByPlaceholderText(/create a task in inbox/i);
    const descInput = screen.getByPlaceholderText(/add description/i);
    const prioritySelect = screen.getByLabelText(/priority/i);
    const createBtn = screen.getByRole("button", { name: /create task/i });

    fireEvent.change(titleInput, {
      target: { value: "Refactor task pipeline" },
    });
    fireEvent.change(descInput, { target: { value: "Optimize memory usage" } });
    fireEvent.change(prioritySelect, { target: { value: "2" } });
    fireEvent.click(createBtn);

    await waitFor(() => {
      expect(screen.getByText("Refactor task pipeline")).toBeInTheDocument();
      expect(screen.getByText("Optimize memory usage")).toBeInTheDocument();
      expect(screen.getByText("P2")).toBeInTheDocument();
    });

    // 2. Click the task item to open TaskDetailModal and edit description and priority
    const createdRow = dbTasks.find(
      (t) => t.title === "Refactor task pipeline" && t.operator_id === op1.id,
    )!;
    const taskItem = screen.getByTestId(`task-item-${createdRow.id}`);
    fireEvent.click(taskItem);

    const modal = screen.getByRole("dialog", { name: /task details/i });
    expect(modal).toBeInTheDocument();

    const modalTitleInput = within(modal).getByLabelText(/task title/i);
    const modalDescInput = within(modal).getByLabelText(/task description/i);
    const modalPrioritySelect = within(modal).getByLabelText(/task priority/i);
    const modalSaveBtn = within(modal).getByRole("button", {
      name: /save changes/i,
    });

    expect(modalTitleInput).toHaveValue("Refactor task pipeline");
    expect(modalDescInput).toHaveValue("Optimize memory usage");
    expect(modalPrioritySelect).toHaveValue("2");

    // Edit fields to Priority 1 (Urgent) and new description
    fireEvent.change(modalDescInput, {
      target: { value: "Updated description for pipeline" },
    });
    fireEvent.change(modalPrioritySelect, { target: { value: "1" } });
    fireEvent.click(modalSaveBtn);

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(
        screen.getByText("Updated description for pipeline"),
      ).toBeInTheDocument();
      expect(screen.getByText("P1")).toBeInTheDocument();
    });

    // Verify persisted record in database
    const updatedDbRow = dbTasks.find((t) => t.id === createdRow.id)!;
    expect(updatedDbRow.description).toBe("Updated description for pipeline");
    expect(updatedDbRow.priority).toBe(1);
    expect(updatedDbRow.version).toBe(2);
  });

  it("proves Issue #41 Criteria 2 & 3: Completing a task records completion timestamp, removes it from Inbox, and lists in Completed view newest-first", async () => {
    const op1: User = { id: "op-1", email: "alice@example.com" } as User;
    const client = createMockSupabaseForOperator(op1);

    // Pre-populate tasks with distinct completion timestamps
    dbTasks.push({
      id: "550e8400-e29b-41d4-a716-446655440051",
      operator_id: "op-1",
      title: "Completed Task Older",
      description: null,
      priority: 4,
      plan: null,
      parent_id: null,
      completed_at: "2026-08-18T10:00:00.000Z",
      created_at: "2026-08-18T09:00:00.000Z",
      updated_at: "2026-08-18T10:00:00.000Z",
      version: 2,
    });

    dbTasks.push({
      id: "550e8400-e29b-41d4-a716-446655440052",
      operator_id: "op-1",
      title: "Open Inbox Task",
      description: "Needs to be done",
      priority: 3,
      plan: null,
      parent_id: null,
      completed_at: null,
      created_at: "2026-08-18T11:00:00.000Z",
      updated_at: "2026-08-18T11:00:00.000Z",
      version: 1,
    });

    render(
      <AuthProvider client={client}>
        <CrasApp client={client} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Open Inbox Task")).toBeInTheDocument();
      // Completed older task must not appear in Inbox!
      expect(
        screen.queryByText("Completed Task Older"),
      ).not.toBeInTheDocument();
    });

    // Complete the open task via circle complete button
    const completeBtn = screen.getByRole("button", {
      name: /complete task open inbox task/i,
    });
    fireEvent.click(completeBtn);

    // Open Inbox Task should disappear from Inbox view
    await waitFor(() => {
      expect(screen.queryByText("Open Inbox Task")).not.toBeInTheDocument();
      expect(screen.getByText(/no tasks in inbox/i)).toBeInTheDocument();
    });

    // Navigate to Completed view
    const completedNavBtn = screen.getByRole("button", { name: /completed/i });
    fireEvent.click(completedNavBtn);

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { level: 2, name: /completed/i }),
      ).toBeInTheDocument();
      expect(screen.getByText("Open Inbox Task")).toBeInTheDocument();
      expect(screen.getByText("Completed Task Older")).toBeInTheDocument();
    });

    // Verify newest-first ordering: "Open Inbox Task" was completed just now (newer than 10:00 UTC)
    const completedItems = screen.getAllByRole("listitem");
    expect(completedItems).toHaveLength(2);
    expect(completedItems[0]).toHaveTextContent("Open Inbox Task");
    expect(completedItems[1]).toHaveTextContent("Completed Task Older");
  });

  it("proves Issue #41 Criteria 4 & 5: Completed task rejects edits until uncompleted; uncompleting restores task to Inbox", async () => {
    const op1: User = { id: "op-1", email: "alice@example.com" } as User;
    const client = createMockSupabaseForOperator(op1);

    dbTasks.push({
      id: "550e8400-e29b-41d4-a716-446655440061",
      operator_id: "op-1",
      title: "Completed Project Plan",
      description: "Finalized project architecture",
      priority: 1,
      plan: null,
      parent_id: null,
      completed_at: "2026-08-18T14:00:00.000Z",
      created_at: "2026-08-18T10:00:00.000Z",
      updated_at: "2026-08-18T14:00:00.000Z",
      version: 2,
    });

    render(
      <AuthProvider client={client}>
        <CrasApp client={client} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("alice@example.com")).toBeInTheDocument();
    });

    // 1. Navigate to Completed view
    const completedNavBtn = screen.getByRole("button", { name: /completed/i });
    fireEvent.click(completedNavBtn);

    await waitFor(() => {
      expect(screen.getByText("Completed Project Plan")).toBeInTheDocument();
    });

    // 2. Click on the completed task to view details
    const taskItem = screen.getByTestId(
      "task-item-550e8400-e29b-41d4-a716-446655440061",
    );
    fireEvent.click(taskItem);

    const modal = screen.getByRole("dialog", { name: /task details/i });
    expect(modal).toBeInTheDocument();

    // Verify warning banner and that inputs are disabled
    expect(
      within(modal).getByText(
        /completed tasks cannot be edited\. uncomplete first\./i,
      ),
    ).toBeInTheDocument();
    expect(within(modal).getByLabelText(/task title/i)).toBeDisabled();
    expect(within(modal).getByLabelText(/task description/i)).toBeDisabled();
    expect(within(modal).getByLabelText(/task priority/i)).toBeDisabled();
    expect(
      within(modal).queryByRole("button", { name: /save changes/i }),
    ).not.toBeInTheDocument();

    // 3. Uncomplete the task from inside the modal
    const uncompleteBtn = within(modal).getByRole("button", {
      name: /uncomplete task/i,
    });
    fireEvent.click(uncompleteBtn);

    // Modal closes and Completed view becomes empty
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(screen.getByText(/no completed tasks yet/i)).toBeInTheDocument();
    });

    // 4. Navigate back to Inbox -> Task must be restored to Inbox!
    const inboxNavBtn = screen.getByRole("button", { name: /inbox/i });
    fireEvent.click(inboxNavBtn);

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { level: 2, name: /inbox/i }),
      ).toBeInTheDocument();
      expect(screen.getByText("Completed Project Plan")).toBeInTheDocument();
      expect(
        screen.getByText("Finalized project architecture"),
      ).toBeInTheDocument();
    });
  });

  it("proves Issue #41 Criterion 6: Observable journey covers identical titles across edits, completions, and uncompletions", async () => {
    const op1: User = { id: "op-1", email: "alice@example.com" } as User;
    const client = createMockSupabaseForOperator(op1);

    render(
      <AuthProvider client={client}>
        <CrasApp client={client} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("alice@example.com")).toBeInTheDocument();
    });

    // Create two tasks with identical title "Weekly Review"
    const input = screen.getByPlaceholderText(/create a task in inbox/i);
    const addBtn = screen.getByRole("button", { name: /create task/i });

    fireEvent.change(input, { target: { value: "Weekly Review" } });
    fireEvent.click(addBtn);

    await waitFor(() => {
      expect(screen.getAllByText("Weekly Review")).toHaveLength(1);
    });

    fireEvent.change(input, { target: { value: "Weekly Review" } });
    fireEvent.click(addBtn);

    await waitFor(() => {
      expect(screen.getAllByText("Weekly Review")).toHaveLength(2);
    });

    const [firstDbTask, secondDbTask] = dbTasks.filter(
      (t) => t.operator_id === "op-1",
    );
    expect(firstDbTask.id).not.toBe(secondDbTask.id);

    // Edit only the first task's description and priority
    const firstTaskItem = screen.getByTestId(`task-item-${firstDbTask.id}`);
    fireEvent.click(firstTaskItem);

    const modal = screen.getByRole("dialog", { name: /task details/i });
    const descInput = within(modal).getByLabelText(/task description/i);
    const prioSelect = within(modal).getByLabelText(/task priority/i);
    const saveBtn = within(modal).getByRole("button", {
      name: /save changes/i,
    });

    fireEvent.change(descInput, {
      target: { value: "Review Q3 team roadmap" },
    });
    fireEvent.change(prioSelect, { target: { value: "1" } });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(screen.getByText("Review Q3 team roadmap")).toBeInTheDocument();
      expect(screen.getByText("P1")).toBeInTheDocument();
    });

    // Complete the first task
    const completeFirstBtn = within(firstTaskItem).getByRole("button", {
      name: /complete task weekly review/i,
    });
    fireEvent.click(completeFirstBtn);

    // Now only the second "Weekly Review" remains in Inbox
    await waitFor(() => {
      expect(screen.getAllByText("Weekly Review")).toHaveLength(1);
      expect(
        screen.queryByText("Review Q3 team roadmap"),
      ).not.toBeInTheDocument();
    });

    // Navigate to Completed view -> First "Weekly Review" is there
    fireEvent.click(screen.getByRole("button", { name: /completed/i }));

    await waitFor(() => {
      expect(screen.getByText("Weekly Review")).toBeInTheDocument();
      expect(screen.getByText("Review Q3 team roadmap")).toBeInTheDocument();
    });
  });

  it("proves Operator data isolation from a second Operator and an unauthenticated caller across completion and edits", async () => {
    // Populate Operator 1 data in database
    dbTasks.push({
      id: "550e8400-e29b-41d4-a716-446655440101",
      operator_id: "op-1",
      title: "Alice Confidential",
      description: "Top secret",
      priority: 1,
      plan: null,
      parent_id: null,
      completed_at: null,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
      version: 1,
    });

    // 1. Operator 2 logs in
    const op2: User = { id: "op-2", email: "bob@example.com" } as User;
    const clientOp2 = createMockSupabaseForOperator(op2);

    render(
      <AuthProvider client={clientOp2}>
        <CrasApp client={clientOp2} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("bob@example.com")).toBeInTheDocument();
    });

    // Operator 2's Inbox must be empty (isolated from Alice)
    expect(screen.queryByText("Alice Confidential")).not.toBeInTheDocument();
    expect(screen.getByText(/no tasks in inbox/i)).toBeInTheDocument();

    // 2. Operator 2 cannot update or complete Alice's task
    const forgedUpdatePromise = clientOp2.schema("api").rpc("update_task", {
      id: "550e8400-e29b-41d4-a716-446655440101",
      title: "Forged Title",
    });
    const forgedUpdateResult = await forgedUpdatePromise;
    expect(forgedUpdateResult.error?.message).toContain("unauthorized");

    const forgedCompletePromise = clientOp2.schema("api").rpc("complete_task", {
      id: "550e8400-e29b-41d4-a716-446655440101",
    });
    const forgedCompleteResult = await forgedCompletePromise;
    expect(forgedCompleteResult.error?.message).toContain("unauthorized");

    // 3. Unauthenticated caller is blocked
    const unauthedClient = createMockSupabaseForOperator(null);
    const selectResult = await unauthedClient
      .schema("api")
      .from("tasks")
      .select("*");
    expect(selectResult.error?.message).toBe("Unauthorized");

    const rpcResult = await unauthedClient.schema("api").rpc("update_task", {
      id: "550e8400-e29b-41d4-a716-446655440101",
      title: "Forged Title",
    });
    expect(rpcResult.error?.message).toBe("Unauthorized");
  });

  it("proves Issue #43 Criterion 1: Operator can create, rename, recolor, and remove a Label", async () => {
    const op1: User = { id: "op-1", email: "alice@example.com" } as User;
    const client = createMockSupabaseForOperator(op1);

    render(
      <AuthProvider client={client}>
        <CrasApp client={client} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("alice@example.com")).toBeInTheDocument();
    });

    // 1. Open Label Manager Modal from sidebar
    const manageLabelsBtn = screen.getByRole("button", {
      name: /add or manage labels/i,
    });
    fireEvent.click(manageLabelsBtn);

    const modal = screen.getByRole("dialog", { name: /manage labels/i });
    expect(modal).toBeInTheDocument();

    // 2. Create Label "Urgent" with red color
    const newNameInput = within(modal).getByPlaceholderText(/new label name/i);
    const addLabelBtn = within(modal).getByRole("button", {
      name: /add label/i,
    });

    fireEvent.change(newNameInput, { target: { value: "Urgent" } });
    fireEvent.click(addLabelBtn);

    await waitFor(() => {
      expect(within(modal).getByText("Urgent")).toBeInTheDocument();
    });

    const urgentLabel = dbLabels.find(
      (l) => l.name === "Urgent" && l.operator_id === "op-1",
    );
    expect(urgentLabel).toBeDefined();
    const urgentId = urgentLabel!.id;

    // 3. Rename "Urgent" to "Critical" and recolor to orange
    const editBtn = within(modal).getByRole("button", {
      name: /edit label urgent/i,
    });
    fireEvent.click(editBtn);

    const editInput = within(modal).getByDisplayValue("Urgent");
    const orangeColorBtns = within(modal).getAllByRole("button", {
      name: /select orange color/i,
    });
    const orangeColorBtn = orangeColorBtns[orangeColorBtns.length - 1];
    const saveLabelBtn = within(modal).getByRole("button", {
      name: /save label/i,
    });

    fireEvent.change(editInput, { target: { value: "Critical" } });
    fireEvent.click(orangeColorBtn);
    fireEvent.click(saveLabelBtn);

    await waitFor(() => {
      expect(within(modal).getByText("Critical")).toBeInTheDocument();
      expect(within(modal).queryByText("Urgent")).not.toBeInTheDocument();
    });

    // Identity preserved
    expect(dbLabels[0].id).toBe(urgentId);
    expect(dbLabels[0].name).toBe("Critical");
    expect(dbLabels[0].color).toBe("#f97316");

    // 4. Create another label "Temporary" and remove it
    fireEvent.change(newNameInput, { target: { value: "Temporary" } });
    fireEvent.click(addLabelBtn);

    await waitFor(() => {
      expect(within(modal).getByText("Temporary")).toBeInTheDocument();
    });

    const deleteTempBtn = within(modal).getByRole("button", {
      name: /delete label temporary/i,
    });
    fireEvent.click(deleteTempBtn);

    await waitFor(() => {
      expect(within(modal).queryByText("Temporary")).not.toBeInTheDocument();
    });

    expect(dbLabels.some((l) => l.name === "Temporary")).toBe(false);

    // Close modal
    fireEvent.click(within(modal).getByRole("button", { name: /done/i }));
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      // Sidebar should display "Critical"
      expect(screen.getByText("Critical")).toBeInTheDocument();
    });
  });

  it("proves Issue #43 Criteria 2 & 6: Label names are unique within one Operator task space (duplicate-name rejection in UI & DB)", async () => {
    const op1: User = { id: "op-1", email: "alice@example.com" } as User;
    const client = createMockSupabaseForOperator(op1);

    // Pre-populate label "Work"
    dbLabels.push({
      id: "11111111-1111-4111-a111-111111111111",
      operator_id: "op-1",
      name: "Work",
      color: "#3b82f6",
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    });

    render(
      <AuthProvider client={client}>
        <CrasApp client={client} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Work")).toBeInTheDocument();
    });

    // Open Label Manager
    fireEvent.click(
      screen.getByRole("button", { name: /add or manage labels/i }),
    );
    const modal = screen.getByRole("dialog", { name: /manage labels/i });

    // 1. Attempt to create duplicate label "Work"
    const nameInput = within(modal).getByPlaceholderText(/new label name/i);
    const addBtn = within(modal).getByRole("button", { name: /add label/i });

    fireEvent.change(nameInput, { target: { value: "Work" } });
    fireEvent.click(addBtn);

    await waitFor(() => {
      expect(
        within(modal).getByText(/a label with this name already exists/i),
      ).toBeInTheDocument();
    });

    // 2. Create label "Personal"
    fireEvent.change(nameInput, { target: { value: "Personal" } });
    fireEvent.click(addBtn);

    await waitFor(() => {
      expect(within(modal).getByText("Personal")).toBeInTheDocument();
    });

    // 3. Attempt to rename "Personal" to "Work" -> duplicate rejected
    const editPersonalBtn = within(modal).getByRole("button", {
      name: /edit label personal/i,
    });
    fireEvent.click(editPersonalBtn);

    const editInput = within(modal).getByDisplayValue("Personal");
    const saveBtn = within(modal).getByRole("button", { name: /save label/i });

    fireEvent.change(editInput, { target: { value: "Work" } });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(
        within(modal).getByText(/a label with this name already exists/i),
      ).toBeInTheDocument();
    });
  });

  it("proves Issue #43 Criteria 3 & 4: Renaming a Label preserves its identity and existing Task associations; a Task can have multiple Labels", async () => {
    const op1: User = { id: "op-1", email: "alice@example.com" } as User;
    const client = createMockSupabaseForOperator(op1);

    // Pre-populate labels
    const backendLabelId = "22222222-2222-4222-a222-222222222222";
    const urgentLabelId = "33333333-3333-4333-a333-333333333333";
    const frontendLabelId = "44444444-4444-4444-a444-444444444444";

    dbLabels.push(
      {
        id: backendLabelId,
        operator_id: "op-1",
        name: "Backend",
        color: "#3b82f6",
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      },
      {
        id: urgentLabelId,
        operator_id: "op-1",
        name: "Urgent",
        color: "#ef4444",
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      },
      {
        id: frontendLabelId,
        operator_id: "op-1",
        name: "Frontend",
        color: "#10b981",
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      },
    );

    render(
      <AuthProvider client={client}>
        <CrasApp client={client} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Backend")).toBeInTheDocument();
      expect(screen.getByText("Urgent")).toBeInTheDocument();
    });

    // 1. Create a task with multiple labels ("Backend" and "Urgent")
    const expandBtn = screen.getByRole("button", { name: /add details/i });
    fireEvent.click(expandBtn);

    const titleInput = screen.getByPlaceholderText(/create a task in inbox/i);
    const backendCheckbox = screen.getByLabelText("Backend");
    const urgentCheckbox = screen.getByLabelText("Urgent");
    const createBtn = screen.getByRole("button", { name: /create task/i });

    fireEvent.change(titleInput, { target: { value: "Optimize SQL index" } });
    fireEvent.click(backendCheckbox);
    fireEvent.click(urgentCheckbox);
    fireEvent.click(createBtn);

    await waitFor(() => {
      expect(screen.getByText("Optimize SQL index")).toBeInTheDocument();
    });

    const createdTask = dbTasks.find(
      (t) => t.title === "Optimize SQL index" && t.operator_id === "op-1",
    )!;
    const taskItem = screen.getByTestId(`task-item-${createdTask.id}`);
    expect(within(taskItem).getByText("Backend")).toBeInTheDocument();
    expect(within(taskItem).getByText("Urgent")).toBeInTheDocument();

    // 2. Open Label Manager and Rename "Backend" to "Infrastructure"
    fireEvent.click(
      screen.getByRole("button", { name: /add or manage labels/i }),
    );
    const labelModal = screen.getByRole("dialog", { name: /manage labels/i });

    const editBackendBtn = within(labelModal).getByRole("button", {
      name: /edit label backend/i,
    });
    fireEvent.click(editBackendBtn);

    const editInput = within(labelModal).getByDisplayValue("Backend");
    const saveLabelBtn = within(labelModal).getByRole("button", {
      name: /save label/i,
    });

    fireEvent.change(editInput, { target: { value: "Infrastructure" } });
    fireEvent.click(saveLabelBtn);

    await waitFor(() => {
      expect(
        within(labelModal).getByText("Infrastructure"),
      ).toBeInTheDocument();
    });

    // Close label manager modal
    fireEvent.click(within(labelModal).getByRole("button", { name: /done/i }));

    // 3. Verify task association is preserved with the renamed label!
    await waitFor(() => {
      expect(within(taskItem).getByText("Infrastructure")).toBeInTheDocument();
      expect(within(taskItem).getByText("Urgent")).toBeInTheDocument();
      expect(within(taskItem).queryByText("Backend")).not.toBeInTheDocument();
    });

    // 4. Open Task Detail Modal and attach a 3rd label ("Frontend")
    fireEvent.click(taskItem);
    const detailModal = screen.getByRole("dialog", { name: /task details/i });

    const frontendCheckboxInModal =
      within(detailModal).getByLabelText("Frontend");
    expect(frontendCheckboxInModal).not.toBeChecked();
    fireEvent.click(frontendCheckboxInModal);

    const saveDetailBtn = within(detailModal).getByRole("button", {
      name: /save changes/i,
    });
    fireEvent.click(saveDetailBtn);

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(within(taskItem).getByText("Frontend")).toBeInTheDocument();
      expect(within(taskItem).getByText("Infrastructure")).toBeInTheDocument();
      expect(within(taskItem).getByText("Urgent")).toBeInTheDocument();
    });

    // Verify persisted relationship in mock DB
    const taskLabelsInDb = dbTaskLabels.filter(
      (tl) => tl.task_id === createdTask.id && tl.operator_id === "op-1",
    );
    expect(taskLabelsInDb).toHaveLength(3);
    expect(taskLabelsInDb.map((tl) => tl.label_id)).toEqual(
      expect.arrayContaining([backendLabelId, urgentLabelId, frontendLabelId]),
    );
  });

  it("proves Issue #43 Criterion 5: Cross-Operator Label and Task–Label relationships are strictly rejected", async () => {
    // Populate Operator 1 data in database
    const op1LabelId = "11111111-1111-4111-a111-111111111111";
    dbLabels.push({
      id: op1LabelId,
      operator_id: "op-1",
      name: "Secret Alice Label",
      color: "#ef4444",
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    });

    // 1. Operator 2 logs in
    const op2: User = { id: "op-2", email: "bob@example.com" } as User;
    const clientOp2 = createMockSupabaseForOperator(op2);

    render(
      <AuthProvider client={clientOp2}>
        <CrasApp client={clientOp2} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("bob@example.com")).toBeInTheDocument();
    });

    // Bob cannot see Alice's label in his sidebar or task space
    expect(screen.queryByText("Secret Alice Label")).not.toBeInTheDocument();

    // 2. Bob tries to create a task referencing Alice's label ID -> Rejected
    const forgedCreatePromise = clientOp2.schema("api").rpc("create_task", {
      title: "Bob Task with Alice Label",
      labels: [op1LabelId],
    });
    const forgedCreateResult = await forgedCreatePromise;
    expect(forgedCreateResult.error?.message).toContain(
      "foreign key constraint",
    );
    expect(dbTasks.some((t) => t.title === "Bob Task with Alice Label")).toBe(
      false,
    );

    // 3. Bob creates his own task, then tries to update it with Alice's label ID -> Rejected
    const bobTaskPromise = clientOp2.schema("api").rpc("create_task", {
      title: "Bob Legitimate Task",
      labels: [],
    });
    const bobTaskResult = await bobTaskPromise;
    expect(bobTaskResult.data).toBeDefined();

    const forgedUpdatePromise = clientOp2.schema("api").rpc("update_task", {
      id: bobTaskResult.data.id,
      labels: [op1LabelId],
    });
    const forgedUpdateResult = await forgedUpdatePromise;
    expect(forgedUpdateResult.error?.message).toContain(
      "foreign key constraint",
    );
    const bobTaskInDb = dbTasks.find((t) => t.id === bobTaskResult.data.id);
    expect(bobTaskInDb?.version).toBe(1);
    expect(
      dbTaskLabels.some((tl) => tl.task_id === bobTaskResult.data.id),
    ).toBe(false);

    // 4. Bob tries to update or delete Alice's label directly -> Rejected / not found
    const forgedLabelUpdate = await clientOp2
      .from("labels")
      .update({ name: "Hacked Name" })
      .eq("id", op1LabelId);
    expect(forgedLabelUpdate.error?.message).toContain("unauthorized");

    // 5. Unauthenticated caller is blocked from reading or inserting labels
    const unauthedClient = createMockSupabaseForOperator(null);
    const unauthedSelect = await unauthedClient
      .from("labels")
      .select("*")
      .order("created_at");
    expect(unauthedSelect.error?.message).toBe("Unauthorized");

    const unauthedInsert = await unauthedClient
      .from("labels")
      .insert({ name: "Ghost", color: "#ef4444" })
      .select("*")
      .single();
    expect(unauthedInsert.error?.message).toBe("Unauthorized");
  });

  it("proves Issue #45 Criterion 1: Operator can create and view dated Comments that remain distinct from Description", async () => {
    const operator: User = {
      id: "operator-comments-uuid",
      email: "comments-op@example.com",
    } as User;
    const client = createMockSupabaseForOperator(operator);

    render(
      <AuthProvider client={client}>
        <CrasApp client={client} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("comments-op@example.com")).toBeInTheDocument();
    });

    // 1. Create a task in Inbox
    const input = screen.getByPlaceholderText(/create a task in inbox/i);
    fireEvent.change(input, { target: { value: "Review security audit" } });
    fireEvent.click(screen.getByRole("button", { name: /create task/i }));

    await waitFor(() => {
      expect(screen.getByText("Review security audit")).toBeInTheDocument();
    });

    // 2. Open task detail modal
    const taskItem = screen.getByText("Review security audit");
    fireEvent.click(taskItem);

    await waitFor(() => {
      expect(screen.getByLabelText(/task details/i)).toBeInTheDocument();
    });

    // 3. Add an optional Description and save
    const descInput = screen.getByLabelText(/task description/i);
    fireEvent.change(descInput, {
      target: { value: "Full review of cloud infrastructure" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => {
      expect(screen.queryByLabelText(/task details/i)).not.toBeInTheDocument();
    });

    // 4. Reopen modal and add a dated Comment
    fireEvent.click(screen.getByText("Review security audit"));
    await waitFor(() => {
      expect(screen.getByLabelText(/task details/i)).toBeInTheDocument();
    });

    // Verify description is present
    expect(
      screen.getByDisplayValue("Full review of cloud infrastructure"),
    ).toBeInTheDocument();

    // Add first Comment
    const commentInput = screen.getByPlaceholderText(/add a comment/i);
    fireEvent.change(commentInput, {
      target: { value: "Checked AWS IAM policies - all good." },
    });
    fireEvent.click(screen.getByRole("button", { name: /add comment/i }));

    await waitFor(() => {
      expect(
        screen.getByText("Checked AWS IAM policies - all good."),
      ).toBeInTheDocument();
    });

    // Add second Comment
    fireEvent.change(commentInput, {
      target: { value: "Checked VPC network ingress rules." },
    });
    fireEvent.click(screen.getByRole("button", { name: /add comment/i }));

    await waitFor(() => {
      expect(
        screen.getByText("Checked VPC network ingress rules."),
      ).toBeInTheDocument();
    });

    // Verify that Description remains intact and distinct from Comments
    expect(
      screen.getByDisplayValue("Full review of cloud infrastructure"),
    ).toBeInTheDocument();

    // Verify in mock DB that description and comments are distinct entities
    const taskInDb = dbTasks.find((t) => t.title === "Review security audit");
    expect(taskInDb?.description).toBe("Full review of cloud infrastructure");
    const commentsInDb = dbComments.filter((c) => c.task_id === taskInDb?.id);
    expect(commentsInDb).toHaveLength(2);
    expect(commentsInDb[0].content).toBe(
      "Checked AWS IAM policies - all good.",
    );
    expect(commentsInDb[1].content).toBe("Checked VPC network ingress rules.");
  });

  it("proves Issue #45 Criteria 2 & 4: Operator can create a Subtask under a top-level Task; Subtasks are excluded from Inbox", async () => {
    const operator: User = {
      id: "operator-subtasks-uuid",
      email: "subtasks-op@example.com",
    } as User;
    const client = createMockSupabaseForOperator(operator);

    render(
      <AuthProvider client={client}>
        <CrasApp client={client} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("subtasks-op@example.com")).toBeInTheDocument();
    });

    // 1. Create a top-level task in Inbox
    const input = screen.getByPlaceholderText(/create a task in inbox/i);
    fireEvent.change(input, { target: { value: "Launch Cras v1" } });
    fireEvent.click(screen.getByRole("button", { name: /create task/i }));

    await waitFor(() => {
      expect(screen.getByText("Launch Cras v1")).toBeInTheDocument();
    });

    // 2. Open top-level task detail modal
    fireEvent.click(screen.getByText("Launch Cras v1"));
    await waitFor(() => {
      expect(screen.getByLabelText(/task details/i)).toBeInTheDocument();
    });

    // 3. Create subtasks under top-level task
    const subtaskInput = screen.getByPlaceholderText(/add subtask/i);
    fireEvent.change(subtaskInput, { target: { value: "Set up CDN" } });
    fireEvent.click(screen.getByRole("button", { name: /add subtask/i }));

    await waitFor(() => {
      expect(screen.getByText("Set up CDN")).toBeInTheDocument();
    });

    fireEvent.change(subtaskInput, {
      target: { value: "Configure custom domain" },
    });
    fireEvent.click(screen.getByRole("button", { name: /add subtask/i }));

    await waitFor(() => {
      expect(screen.getByText("Configure custom domain")).toBeInTheDocument();
    });

    // Close detail modal
    fireEvent.click(screen.getByRole("button", { name: /close/i }));
    await waitFor(() => {
      expect(screen.queryByLabelText(/task details/i)).not.toBeInTheDocument();
    });

    // 4. Acceptance Criterion 4: "Subtasks are excluded from Inbox."
    // In Inbox view, only top-level task "Launch Cras v1" should appear; "Set up CDN" and "Configure custom domain" should NOT appear in main Inbox list!
    expect(screen.getByText("Launch Cras v1")).toBeInTheDocument();
    expect(screen.queryByText("Set up CDN")).not.toBeInTheDocument();
    expect(
      screen.queryByText("Configure custom domain"),
    ).not.toBeInTheDocument();

    // 5. Complete a subtask inside modal
    fireEvent.click(screen.getByText("Launch Cras v1"));
    await waitFor(() => {
      expect(screen.getByLabelText(/task details/i)).toBeInTheDocument();
    });

    const completeCdnBtn = screen.getByLabelText(/complete task set up cdn/i);
    fireEvent.click(completeCdnBtn);

    await waitFor(() => {
      const cdnInDb = dbTasks.find((t) => t.title === "Set up CDN");
      expect(cdnInDb?.completed_at).not.toBeNull();
    });
  });

  it("proves Issue #45 Criterion 3: A Subtask cannot receive its own child (one-level nesting only)", async () => {
    const operator: User = {
      id: "operator-nesting-uuid",
      email: "nesting-op@example.com",
    } as User;
    const client = createMockSupabaseForOperator(operator);

    // 1. Seed a top-level task and a subtask
    const topLevelTaskRes = await client.schema("api").rpc("create_task", {
      title: "Parent Task",
    });
    const topTaskId = topLevelTaskRes.data.id;

    const subtaskRes = await client.schema("api").rpc("create_task", {
      title: "Child Subtask",
      parent_id: topTaskId,
    });
    const subtaskId = subtaskRes.data.id;
    expect(subtaskRes.data.parentId).toBe(topTaskId);

    // 2. Render UI and open detail modal for the subtask
    render(
      <AuthProvider client={client}>
        <CrasApp client={client} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("nesting-op@example.com")).toBeInTheDocument();
    });

    // Open parent task first, click child subtask to navigate to subtask
    fireEvent.click(screen.getByText("Parent Task"));
    await waitFor(() => {
      expect(screen.getByText("Child Subtask")).toBeInTheDocument();
    });

    // Click on Child Subtask to open subtask details
    fireEvent.click(screen.getByText("Child Subtask"));
    await waitFor(() => {
      expect(screen.getByText(/subtask/i)).toBeInTheDocument();
    });

    // Verify subtask detail modal does NOT allow adding deeper child subtasks
    expect(
      screen.queryByPlaceholderText(/add subtask/i),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /add subtask/i }),
    ).not.toBeInTheDocument();

    // 3. Prove Database rejection when attempting to create a child under a subtask
    const invalidSubtaskPromise = client.schema("api").rpc("create_task", {
      title: "Grandchild Subtask (Forbidden)",
      parent_id: subtaskId,
    });
    const invalidSubtaskResult = await invalidSubtaskPromise;
    expect(invalidSubtaskResult.error?.message).toContain(
      "Subtasks cannot have children (one-level nesting only)",
    );

    // 4. Prove Database rejection when attempting to set parent_id on a task that already has subtasks
    const invalidParentUpdatePromise = client.schema("api").rpc("update_task", {
      id: topTaskId,
      parent_id: subtaskId,
    });
    const invalidParentUpdateResult = await invalidParentUpdatePromise;
    expect(invalidParentUpdateResult.error?.message).toContain(
      "Subtasks cannot have children (one-level nesting only)",
    );
  });

  it("proves Issue #45 Criterion 5: Database relationships reject cross-Operator parents and Comments", async () => {
    // 1. Operator 1 (Alice) creates a task
    const op1: User = { id: "alice-uuid", email: "alice@example.com" } as User;
    const clientOp1 = createMockSupabaseForOperator(op1);
    const aliceTaskRes = await clientOp1.schema("api").rpc("create_task", {
      title: "Alice Confidential Strategy",
    });
    const aliceTaskId = aliceTaskRes.data.id;

    // Alice attaches a comment
    const aliceCommentRes = await clientOp1
      .schema("api")
      .rpc("create_comment", {
        task_id: aliceTaskId,
        content: "Alice private remark",
      });
    expect(aliceCommentRes.data.content).toBe("Alice private remark");

    // 2. Operator 2 (Bob) logs in
    const op2: User = { id: "bob-uuid", email: "bob@example.com" } as User;
    const clientOp2 = createMockSupabaseForOperator(op2);

    render(
      <AuthProvider client={clientOp2}>
        <CrasApp client={clientOp2} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("bob@example.com")).toBeInTheDocument();
    });

    // Bob cannot see Alice's task in Inbox
    expect(
      screen.queryByText("Alice Confidential Strategy"),
    ).not.toBeInTheDocument();

    // 3. Bob attempts to create a comment under Alice's task -> Rejected
    const forgedCommentPromise = clientOp2.schema("api").rpc("create_comment", {
      task_id: aliceTaskId,
      content: "Bob malicious comment",
    });
    const forgedCommentResult = await forgedCommentPromise;
    expect(forgedCommentResult.error?.message).toContain(
      "Task not found or unauthorized",
    );

    // 4. Bob attempts to create a subtask with Alice's task as parent -> Rejected
    const forgedSubtaskPromise = clientOp2.schema("api").rpc("create_task", {
      title: "Bob subtask under Alice task",
      parent_id: aliceTaskId,
    });
    const forgedSubtaskResult = await forgedSubtaskPromise;
    expect(forgedSubtaskResult.error?.message).toContain(
      "foreign key constraint",
    );

    // 5. Bob creates his own task, then attempts to update it with Alice's task as parent -> Rejected
    const bobTaskRes = await clientOp2.schema("api").rpc("create_task", {
      title: "Bob Task",
    });
    const forgedUpdatePromise = clientOp2.schema("api").rpc("update_task", {
      id: bobTaskRes.data.id,
      parent_id: aliceTaskId,
    });
    const forgedUpdateResult = await forgedUpdatePromise;
    expect(forgedUpdateResult.error?.message).toContain(
      "foreign key constraint",
    );
  });

  it("Journey 7 (Issue #47): Plans tasks with temporal semantics and navigates Today and Upcoming views", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date(2026, 7, 19, 12, 0, 0));
    try {
      const operator: User = {
        id: "operator-temporal-uuid",
        email: "planner@example.com",
      } as User;

      const client = createMockSupabaseForOperator(operator);

      const now = new Date(2026, 7, 19, 12, 0, 0);
      const todayStr = getDeviceLocalDate(now);
      const yesterdayDate = new Date(
        now.getFullYear(),
        now.getMonth(),
        now.getDate() - 1,
      );
      const yesterdayStr = getDeviceLocalDate(yesterdayDate);
      const futureDate = new Date(
        now.getFullYear(),
        now.getMonth(),
        now.getDate() + 6,
      );
      const futureStr = getDeviceLocalDate(futureDate);

      // 1. Pre-seed tasks: 1 inbox task, 1 today task, 1 overdue task, 1 upcoming task
      await client.schema("api").rpc("create_task", {
        title: "Inbox Task",
        plan: null,
      });

      await client.schema("api").rpc("create_task", {
        title: "Today Urgent Task",
        plan: { date: todayStr },
        priority: 1,
      });

      await client.schema("api").rpc("create_task", {
        title: "Overdue Task",
        plan: { date: yesterdayStr },
        priority: 2,
      });

      await client.schema("api").rpc("create_task", {
        title: "Future Floating Task",
        plan: { type: "floating", date: futureStr, time: "14:00" },
        priority: 3,
      });

      const { unmount } = render(
        <AuthProvider client={client}>
          <CrasApp client={client} />
        </AuthProvider>,
      );

      // Initial view is Inbox: should show only Inbox Task
      await waitFor(() => {
        expect(screen.getByText("Inbox Task")).toBeInTheDocument();
      });
      expect(screen.queryByText("Today Urgent Task")).not.toBeInTheDocument();
      expect(screen.queryByText("Overdue Task")).not.toBeInTheDocument();
      expect(
        screen.queryByText("Future Floating Task"),
      ).not.toBeInTheDocument();

      // 2. Navigate to Today View
      const todayNavBtn = screen.getByRole("button", { name: /today/i });
      fireEvent.click(todayNavBtn);

      await waitFor(() => {
        expect(screen.getByText("Today Urgent Task")).toBeInTheDocument();
        expect(screen.getByText("Overdue Task")).toBeInTheDocument();
      });
      expect(screen.queryByText("Inbox Task")).not.toBeInTheDocument();
      expect(
        screen.queryByText("Future Floating Task"),
      ).not.toBeInTheDocument();

      // 3. Navigate to Upcoming View
      const upcomingNavBtn = screen.getByRole("button", { name: /upcoming/i });
      fireEvent.click(upcomingNavBtn);

      await waitFor(() => {
        // Overdue section
        expect(screen.getByText(/overdue \(\d+\)/i)).toBeInTheDocument();
        expect(screen.getByText("Overdue Task")).toBeInTheDocument();
        // Future tasks
        expect(screen.getByText("Today Urgent Task")).toBeInTheDocument();
        expect(screen.getByText("Future Floating Task")).toBeInTheDocument();
      });
      expect(screen.queryByText("Inbox Task")).not.toBeInTheDocument();

      // 4. Open Task Detail modal on Future Floating Task and edit plan
      const futureTaskItem = screen.getByText("Future Floating Task");
      fireEvent.click(futureTaskItem);

      await waitFor(() => {
        expect(
          screen.getByRole("dialog", { name: /task details/i }),
        ).toBeInTheDocument();
      });

      // Remove clock time -> becomes Date-only
      const timeInput = screen.getByLabelText(/task plan time/i);
      fireEvent.change(timeInput, { target: { value: "" } });

      const saveChangesBtn = screen.getByRole("button", {
        name: /save changes/i,
      });
      fireEvent.click(saveChangesBtn);

      await waitFor(() => {
        expect(
          screen.queryByRole("dialog", { name: /task details/i }),
        ).not.toBeInTheDocument();
      });

      // Check DB state: plan should now be Date-only without type or time
      const updatedTask = dbTasks.find(
        (t) => t.title === "Future Floating Task",
      );
      expect(updatedTask?.plan).toEqual({ date: futureStr });

      // 5. Open modal again and clear date -> moves to Inbox
      fireEvent.click(screen.getByText("Future Floating Task"));
      await waitFor(() => {
        expect(
          screen.getByRole("dialog", { name: /task details/i }),
        ).toBeInTheDocument();
      });

      const clearDateBtn = screen.getByRole("button", {
        name: /clear date \(move to inbox\)/i,
      });
      fireEvent.click(clearDateBtn);
      fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

      await waitFor(() => {
        expect(
          screen.queryByRole("dialog", { name: /task details/i }),
        ).not.toBeInTheDocument();
      });

      const movedToInboxTask = dbTasks.find(
        (t) => t.title === "Future Floating Task",
      );
      expect(movedToInboxTask?.plan).toBeNull();

      // Navigate to Inbox: Future Floating Task is now in Inbox
      const inboxNavBtn = screen.getByRole("button", { name: /inbox/i });
      fireEvent.click(inboxNavBtn);

      await waitFor(() => {
        expect(screen.getByText("Future Floating Task")).toBeInTheDocument();
      });

      unmount();
    } finally {
      vi.useRealTimers();
    }
  });
});
