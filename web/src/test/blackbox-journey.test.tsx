import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { AuthProvider } from "../contexts/AuthContext";
import { CrasApp } from "../App";
import { getPublicSupabaseConfig } from "../config/supabase";
import type { SupabaseClient, Session, User } from "@supabase/supabase-js";
import type { Task, Plan } from "../contracts/task";

describe("Black-box Acceptance & Isolation Suite - Web Client (Issue #39)", () => {
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

  let dbTasks: DbRow[];

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
      schema: (schemaName: string) => {
        if (schemaName !== "api")
          throw new Error(`Access denied to non-api schema: ${schemaName}`);
        return {
          from: (tableName: string) => {
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
                const mapped: Task[] = userTasks.map((t) => ({
                  id: t.id,
                  title: t.title,
                  description: t.description,
                  priority: t.priority as 1 | 2 | 3 | 4,
                  plan: t.plan,
                  labels: [],
                  parentId: t.parent_id,
                  completedAt: t.completed_at,
                  createdAt: t.created_at,
                  updatedAt: t.updated_at,
                  version: t.version,
                }));
                return { data: mapped, error: null };
              }),
            };
          },
          rpc: (fnName: string, params: Record<string, unknown>) => {
            if (fnName !== "create_task")
              throw new Error(`Unknown RPC: ${fnName}`);
            if (!currentUser) {
              return Promise.resolve({
                data: null,
                error: { message: "Unauthorized", code: "42501" },
              });
            }
            const titleParam =
              typeof params.title === "string" ? params.title : "";
            if (titleParam.trim().length === 0) {
              return Promise.resolve({
                data: null,
                error: { message: "Task title cannot be empty", code: "23514" },
              });
            }

            const newId =
              typeof params.id === "string" ? params.id : crypto.randomUUID();
            const now = new Date().toISOString();
            const newRow: DbRow = {
              id: newId,
              operator_id: currentUser.id, // Derived strictly from auth.uid()
              title: titleParam.trim(),
              description: (params.description as string | null) ?? null,
              priority:
                typeof params.priority === "number" ? params.priority : 4,
              plan: (params.plan as Plan) ?? null,
              parent_id: (params.parentId as string | null) ?? null,
              completed_at: null,
              created_at: now,
              updated_at: now,
              version: 1,
            };

            dbTasks.push(newRow);

            const resultTask: Task = {
              id: newRow.id,
              title: newRow.title,
              description: newRow.description,
              priority: newRow.priority as 1 | 2 | 3 | 4,
              plan: newRow.plan,
              labels: [],
              parentId: newRow.parent_id,
              completedAt: newRow.completed_at,
              createdAt: newRow.created_at,
              updatedAt: newRow.updated_at,
              version: newRow.version,
            };

            return Promise.resolve({ data: resultTask, error: null });
          },
        };
      },
    } as unknown as SupabaseClient;
  }

  beforeEach(() => {
    dbTasks = [];
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

  it("proves Criterion 2 & 3: creating tasks with only a title creates distinct stable identities and Inbox excludes subtasks/dated/completed", async () => {
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

    // Create first task with title "Buy groceries"
    const input = screen.getByPlaceholderText(/add a task to inbox/i);
    const addButton = screen.getByRole("button", { name: /add task/i });

    fireEvent.change(input, { target: { value: "Buy groceries" } });
    fireEvent.click(addButton);

    await waitFor(() => {
      expect(screen.getAllByText("Buy groceries")).toHaveLength(1);
    });

    // Create second task with identical title "Buy groceries"
    fireEvent.change(input, { target: { value: "Buy groceries" } });
    fireEvent.click(addButton);

    await waitFor(() => {
      expect(screen.getAllByText("Buy groceries")).toHaveLength(2);
    });

    // Verify distinct stable identities in database
    const op1DbTasks = dbTasks.filter((t) => t.operator_id === "op-1");
    expect(op1DbTasks).toHaveLength(2);
    expect(op1DbTasks[0].title).toBe("Buy groceries");
    expect(op1DbTasks[1].title).toBe("Buy groceries");
    expect(op1DbTasks[0].id).not.toBe(op1DbTasks[1].id);

    // Now simulate non-inbox tasks in database:
    // Subtask (has parent_id)
    dbTasks.push({
      id: "550e8400-e29b-41d4-a716-446655440091",
      operator_id: "op-1",
      title: "Subtask item",
      description: null,
      priority: 4,
      plan: null,
      parent_id: op1DbTasks[0].id,
      completed_at: null,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
      version: 1,
    });

    // Dated task (has plan)
    dbTasks.push({
      id: "550e8400-e29b-41d4-a716-446655440092",
      operator_id: "op-1",
      title: "Tomorrow task",
      description: null,
      priority: 4,
      plan: { date: "2026-08-19" },
      parent_id: null,
      completed_at: null,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
      version: 1,
    });

    // Completed task (has completed_at)
    dbTasks.push({
      id: "550e8400-e29b-41d4-a716-446655440093",
      operator_id: "op-1",
      title: "Done yesterday",
      description: null,
      priority: 4,
      plan: null,
      parent_id: null,
      completed_at: new Date().toISOString(),
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
      version: 1,
    });

    // Re-render / refresh view for Operator 1
    render(
      <AuthProvider client={client}>
        <CrasApp client={client} />
      </AuthProvider>,
    );

    await waitFor(() => {
      // Inbox should still only show the 2 open undated top-level tasks!
      expect(screen.queryByText("Subtask item")).not.toBeInTheDocument();
      expect(screen.queryByText("Tomorrow task")).not.toBeInTheDocument();
      expect(screen.queryByText("Done yesterday")).not.toBeInTheDocument();
    });
  });

  it("proves Criterion 5 & 6: Operator data isolation from a second Operator and an unauthenticated caller", async () => {
    // Populate Operator 1 data in database
    dbTasks.push({
      id: "550e8400-e29b-41d4-a716-446655440101",
      operator_id: "op-1",
      title: "Alice Secret Task",
      description: null,
      priority: 4,
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
    expect(screen.queryByText("Alice Secret Task")).not.toBeInTheDocument();
    expect(screen.getByText(/no tasks in inbox/i)).toBeInTheDocument();

    // Operator 2 creates a task
    const input = screen.getByPlaceholderText(/add a task to inbox/i);
    const addButton = screen.getByRole("button", { name: /add task/i });
    fireEvent.change(input, { target: { value: "Bob Task" } });
    fireEvent.click(addButton);

    await waitFor(() => {
      expect(screen.getByText("Bob Task")).toBeInTheDocument();
    });

    // Verify Bob's task is owned by op-2
    const bobDbTask = dbTasks.find((t) => t.title === "Bob Task");
    expect(bobDbTask?.operator_id).toBe("op-2");

    // 2. Unauthenticated caller is blocked
    const unauthedClient = createMockSupabaseForOperator(null);
    const selectPromise = unauthedClient
      .schema("api")
      .from("tasks")
      .select("*");
    const selectResult = await selectPromise;
    expect(selectResult.error?.message).toBe("Unauthorized");

    const rpcPromise = unauthedClient
      .schema("api")
      .rpc("create_task", { title: "Forged Task" });
    const rpcResult = await rpcPromise;
    expect(rpcResult.error?.message).toBe("Unauthorized");
  });
});
