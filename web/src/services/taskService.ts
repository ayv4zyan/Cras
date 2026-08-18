import type { SupabaseClient } from "@supabase/supabase-js";
import { type Task, parseTask, type Plan } from "../contracts/task";

export interface CreateTaskInput {
  readonly id?: string;
  readonly title: string;
  readonly description?: string | null;
  readonly priority?: 1 | 2 | 3 | 4;
  readonly plan?: Plan;
  readonly parentId?: string | null;
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
  input: CreateTaskInput,
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
    parentId: input.parentId ?? null,
    labels: input.labels ?? [],
  });

  if (error) {
    throw new Error(`Failed to create task: ${error.message} (${error.code})`);
  }

  return parseTask(data);
}
