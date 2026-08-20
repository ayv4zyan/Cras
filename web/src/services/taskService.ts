import type { SupabaseClient } from "@supabase/supabase-js";
import { type Task, parseTask, type Plan } from "../contracts/task";
import { filterTodayTasks, filterUpcomingTasks } from "./temporalService";

export { filterTodayTasks, filterUpcomingTasks };

export interface CreateTaskParams {
  readonly id?: string;
  readonly title: string;
  readonly description?: string | null;
  readonly priority?: 1 | 2 | 3 | 4;
  readonly plan?: Plan;
  readonly parentId?: string | null;
  readonly labels?: string[];
}

export interface UpdateTaskParams {
  readonly id: string;
  readonly title?: string;
  readonly description?: string | null;
  readonly clearDescription?: boolean;
  readonly priority?: 1 | 2 | 3 | 4;
  readonly plan?: Plan;
  readonly clearPlan?: boolean;
  readonly parentId?: string | null;
  readonly expectedVersion?: number;
  readonly labels?: string[];
}

/**
 * Filter tasks that belong to the Inbox view:
 * "The view of open top-level Tasks that have no date. Subtasks are not in Inbox."
 */
export function filterInboxTasks(tasks: readonly Task[]): Task[] {
  return tasks.filter(
    (task) =>
      task.completedAt === null && task.plan === null && task.parentId === null,
  );
}

/**
 * Filter subtasks that belong to a specific parent task:
 * "A Subtask is a Task that has a parent Task."
 */
export function filterSubtasks(
  tasks: readonly Task[],
  parentId: string,
): Task[] {
  return tasks.filter((task) => task.parentId === parentId);
}

/**
 * Filter tasks that belong to the Completed view:
 * "The view of Tasks that have a completed-at, newest first."
 */
export function filterCompletedTasks(tasks: readonly Task[]): Task[] {
  return tasks
    .filter(
      (task): task is Task & { readonly completedAt: string } =>
        task.completedAt !== null,
    )
    .slice()
    .sort(
      (a, b) =>
        new Date(b.completedAt).getTime() - new Date(a.completedAt).getTime(),
    );
}

/**
 * Fetches all canonical tasks from the api.tasks view and validates
 * each record against the shared Task contract schema.
 */
export async function fetchTasks(client: SupabaseClient): Promise<Task[]> {
  const { data, error } = await client.schema("api").from("tasks").select("*");

  if (error) {
    throw new Error(`Failed to fetch tasks: ${error.message} (${error.code})`);
  }

  if (!data || !Array.isArray(data)) {
    return [];
  }

  return data.map((item) => parseTask(item));
}

/**
 * Creates a new task via the api.create_task RPC, returning the
 * decoded canonical Task.
 */
export async function createTask(
  client: SupabaseClient,
  input: CreateTaskParams,
): Promise<Task> {
  const trimmedTitle = input.title.trim();
  if (trimmedTitle.length === 0) {
    throw new Error("Task title cannot be empty");
  }

  const { data, error } = await client.schema("api").rpc("create_task", {
    title: trimmedTitle,
    description: input.description ?? null,
    priority: input.priority ?? 4,
    plan: input.plan ?? null,
    parent_id: input.parentId ?? null,
    labels: input.labels ?? [],
  });

  if (error) {
    throw new Error(`Failed to create task: ${error.message} (${error.code})`);
  }

  return parseTask(data);
}

/**
 * Updates an open task via the api.update_task RPC, returning the
 * decoded updated Task. Fails if the task is completed or version CAS fails.
 */
export async function updateTask(
  client: SupabaseClient,
  input: UpdateTaskParams,
): Promise<Task> {
  if (input.title !== undefined && input.title.trim().length === 0) {
    throw new Error("Task title cannot be empty");
  }

  const clearDescription =
    input.clearDescription ?? (input.description === null ? true : false);
  const clearPlan = input.clearPlan ?? (input.plan === null ? true : false);

  const { data, error } = await client.schema("api").rpc("update_task", {
    id: input.id,
    title: input.title !== undefined ? input.title.trim() : null,
    description: input.description !== undefined ? input.description : null,
    priority: input.priority ?? null,
    plan: input.plan !== undefined ? input.plan : null,
    parent_id: input.parentId !== undefined ? input.parentId : null,
    expected_version: input.expectedVersion ?? null,
    labels: input.labels !== undefined ? input.labels : null,
    clear_plan: clearPlan,
    clear_description: clearDescription,
  });

  if (error) {
    throw new Error(`Failed to update task: ${error.message} (${error.code})`);
  }

  return parseTask(data);
}

/**
 * Completes a task via the api.complete_task RPC, recording a completion timestamp.
 */
export async function completeTask(
  client: SupabaseClient,
  taskId: string,
  completedAt?: string,
): Promise<Task> {
  const { data, error } = await client.schema("api").rpc("complete_task", {
    id: taskId,
    ...(completedAt !== undefined ? { completed_at: completedAt } : {}),
  });

  if (error) {
    throw new Error(
      `Failed to complete task: ${error.message} (${error.code})`,
    );
  }

  return parseTask(data);
}

/**
 * Uncompletes a task via the api.uncomplete_task RPC, removing completedAt.
 */
export async function uncompleteTask(
  client: SupabaseClient,
  taskId: string,
): Promise<Task> {
  const { data, error } = await client.schema("api").rpc("uncomplete_task", {
    id: taskId,
  });

  if (error) {
    throw new Error(
      `Failed to uncomplete task: ${error.message} (${error.code})`,
    );
  }

  return parseTask(data);
}
