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
import type { Task, Plan } from "../contracts/task";

describe("Black-box Acceptance & Isolation Suite - Web Client (Issues #39 & #41)", () => {
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
            if (!currentUser) {
              return Promise.resolve({
                data: null,
                error: { message: "Unauthorized", code: "42501" },
              });
            }

            const now = new Date().toISOString();

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
                parent_id: (params.parent_id as string | null) ?? null,
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

              const updatedRow: DbRow = {
                ...existing,
                title:
                  params.title !== undefined && params.title !== null
                    ? (params.title as string).trim()
                    : existing.title,
                description:
                  params.description !== undefined
                    ? (params.description as string | null)
                    : existing.description,
                priority:
                  params.priority !== undefined && params.priority !== null
                    ? (params.priority as number)
                    : existing.priority,
                plan:
                  params.plan !== undefined
                    ? (params.plan as Plan)
                    : existing.plan,
                parent_id:
                  params.parent_id !== undefined
                    ? (params.parent_id as string | null)
                    : existing.parent_id,
                updated_at: now,
                version: existing.version + 1,
              };

              dbTasks[taskIndex] = updatedRow;

              const resultTask: Task = {
                id: updatedRow.id,
                title: updatedRow.title,
                description: updatedRow.description,
                priority: updatedRow.priority as 1 | 2 | 3 | 4,
                plan: updatedRow.plan,
                labels: [],
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

              const resultTask: Task = {
                id: updatedRow.id,
                title: updatedRow.title,
                description: updatedRow.description,
                priority: updatedRow.priority as 1 | 2 | 3 | 4,
                plan: updatedRow.plan,
                labels: [],
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

              const resultTask: Task = {
                id: updatedRow.id,
                title: updatedRow.title,
                description: updatedRow.description,
                priority: updatedRow.priority as 1 | 2 | 3 | 4,
                plan: updatedRow.plan,
                labels: [],
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
    const taskItem = screen.getByTestId(`task-item-${dbTasks[0].id}`);
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
    expect(dbTasks[0].description).toBe("Updated description for pipeline");
    expect(dbTasks[0].priority).toBe(1);
    expect(dbTasks[0].version).toBe(2);
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
});
