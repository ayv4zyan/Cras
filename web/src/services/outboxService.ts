import type { SupabaseClient } from "@supabase/supabase-js";
import { type Task } from "../contracts/task";
import {
  createTask,
  completeTask,
  fetchTaskById,
  isVersionConflictError,
  type CreateTaskParams,
} from "./taskService";

export interface CreateOutboxItem {
  readonly id: string;
  readonly type: "create";
  readonly task: Task;
  readonly params: CreateTaskParams;
  readonly createdAt: string;
}

export interface CompleteOutboxItem {
  readonly id: string;
  readonly type: "complete";
  readonly taskId: string;
  readonly expectedVersion: number;
  readonly completedAt: string;
  readonly createdAt: string;
}

export type OutboxItem = CreateOutboxItem | CompleteOutboxItem;

export interface DrainOutboxOptions {
  readonly client: SupabaseClient;
  readonly operatorId: string;
  readonly onTaskCreated?: (task: Task) => void;
  readonly onTaskCompleted?: (task: Task) => void;
  readonly onConflict?: (error: unknown, item: OutboxItem) => void;
  readonly onError?: (error: unknown, item: OutboxItem) => void;
}

const OUTBOX_STORAGE_PREFIX = "cras_outbox_";

// In-memory fallback map if localStorage is unavailable
const inMemoryOutbox = new Map<string, OutboxItem[]>();
const activeDrains = new Map<string, Promise<void>>();

function getStorageKey(operatorId: string): string {
  return `${OUTBOX_STORAGE_PREFIX}${operatorId}`;
}

export function generateTaskId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  // Fallback RFC4122 v4 UUID generator
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/**
 * Retrieves pending Outbox items for the specified Operator.
 */
export function getOutbox(operatorId: string): OutboxItem[] {
  try {
    if (typeof localStorage !== "undefined") {
      const serialized = localStorage.getItem(getStorageKey(operatorId));
      if (serialized) {
        const parsed = JSON.parse(serialized);
        if (Array.isArray(parsed)) {
          return parsed;
        }
      }
    }
  } catch {
    // Fall back to in-memory store
  }
  return inMemoryOutbox.get(operatorId) || [];
}

/**
 * Persists Outbox items for the specified Operator.
 */
export function saveOutbox(
  operatorId: string,
  items: readonly OutboxItem[],
): void {
  inMemoryOutbox.set(operatorId, [...items]);
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(
        getStorageKey(operatorId),
        JSON.stringify(items),
      );
    }
  } catch {
    // Ignore storage write errors (e.g. quota exceeded)
  }
}

/**
 * Appends an item to the persistent Outbox.
 */
export function enqueueOutboxItem(
  operatorId: string,
  item: OutboxItem,
): void {
  const current = getOutbox(operatorId);
  const updated = [...current, item];
  saveOutbox(operatorId, updated);
}

/**
 * Removes an item from the Outbox by its unique ID.
 */
export function removeOutboxItem(
  operatorId: string,
  itemId: string,
): void {
  const current = getOutbox(operatorId);
  const updated = current.filter((item) => item.id !== itemId);
  saveOutbox(operatorId, updated);
}

/**
 * Clears all Outbox items for the specified Operator.
 */
export function clearOutbox(operatorId: string): void {
  inMemoryOutbox.delete(operatorId);
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.removeItem(getStorageKey(operatorId));
    }
  } catch {
    // Ignore
  }
}

/**
 * Checks whether an error indicates offline / network failure.
 */
export function isNetworkError(error: unknown): boolean {
  if (typeof navigator !== "undefined" && navigator.onLine === false) {
    return true;
  }
  if (!error) return false;
  if (typeof error === "object") {
    const errObj = error as Record<string, unknown>;
    const msg =
      typeof errObj.message === "string" ? errObj.message.toLowerCase() : "";
    const name = typeof errObj.name === "string" ? errObj.name : "";
    if (
      msg.includes("failed to fetch") ||
      msg.includes("network error") ||
      msg.includes("networkerror") ||
      msg.includes("networkrequestfailed") ||
      msg.includes("fetcherror") ||
      msg.includes("load failed") ||
      msg.includes("timed out") ||
      msg.includes("timeout") ||
      msg.includes("offline") ||
      (name === "TypeError" && msg.includes("fetch"))
    ) {
      return true;
    }
  }
  if (error instanceof Error) {
    const msg = error.message.toLowerCase();
    return (
      msg.includes("failed to fetch") ||
      msg.includes("network error") ||
      msg.includes("networkrequestfailed") ||
      msg.includes("fetcherror") ||
      msg.includes("load failed") ||
      msg.includes("timeout") ||
      msg.includes("offline")
    );
  }
  return false;
}

/**
 * Overlays pending Outbox creates and completions onto a canonical task list.
 */
export function applyOutboxToTasks(
  canonicalTasks: readonly Task[],
  outboxItems: readonly OutboxItem[],
): Task[] {
  let result = [...canonicalTasks];

  for (const item of outboxItems) {
    if (item.type === "create") {
      const exists = result.some((t) => t.id === item.task.id);
      if (!exists) {
        result = [item.task, ...result];
      }
    } else if (item.type === "complete") {
      result = result.map((task) => {
        if (task.id === item.taskId && task.completedAt === null) {
          return {
            ...task,
            completedAt: item.completedAt,
            updatedAt: item.completedAt,
          };
        }
        return task;
      });
    }
  }

  return result;
}

/**
 * Drains the Operator's Outbox in FIFO order.
 * Survives network errors by retaining unsent items in the queue.
 */
export async function drainOutbox(options: DrainOutboxOptions): Promise<void> {
  const {
    client,
    operatorId,
    onTaskCreated,
    onTaskCompleted,
    onConflict,
    onError,
  } = options;

  const previousDrain = activeDrains.get(operatorId) || Promise.resolve();

  const currentDrain = (async () => {
    try {
      await previousDrain;
    } catch {
      // Ignore errors in previous drain
    }

    while (true) {
      if (typeof navigator !== "undefined" && navigator.onLine === false) {
        break;
      }

      const items = getOutbox(operatorId);
      if (items.length === 0) {
        break;
      }

      const item = items[0];
      if (item.type === "create") {
        try {
          const created = await createTask(client, item.params);
          removeOutboxItem(operatorId, item.id);
          onTaskCreated?.(created);
        } catch (err) {
          if (isNetworkError(err)) {
            break;
          }
          // Check if task already exists on server (e.g. prior unacknowledged attempt)
          try {
            const existing = await fetchTaskById(client, item.task.id);
            if (existing) {
              removeOutboxItem(operatorId, item.id);
              onTaskCreated?.(existing);
              continue;
            }
          } catch {
            // Ignore fetch check error
          }
          removeOutboxItem(operatorId, item.id);
          onError?.(err, item);
        }
      } else if (item.type === "complete") {
        try {
          const completed = await completeTask(
            client,
            item.taskId,
            item.expectedVersion,
            item.completedAt,
          );
          removeOutboxItem(operatorId, item.id);
          onTaskCompleted?.(completed);
        } catch (err) {
          if (isNetworkError(err)) {
            break;
          }
          removeOutboxItem(operatorId, item.id);
          if (isVersionConflictError(err)) {
            onConflict?.(err, item);
          } else {
            onError?.(err, item);
          }
        }
      }
    }
  })();

  activeDrains.set(operatorId, currentDrain);
  try {
    await currentDrain;
  } finally {
    if (activeDrains.get(operatorId) === currentDrain) {
      activeDrains.delete(operatorId);
    }
  }
}
