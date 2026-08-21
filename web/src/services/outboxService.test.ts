import { describe, it, expect, beforeEach, vi } from "vitest";
import {
  getOutbox,
  enqueueOutboxItem,
  removeOutboxItem,
  clearOutbox,
  applyOutboxToTasks,
  drainOutbox,
  isNetworkError,
  generateTaskId,
  type CreateOutboxItem,
  type CompleteOutboxItem,
  type OutboxItem,
} from "./outboxService";
import type { Task } from "../contracts/task";
import type { SupabaseClient } from "@supabase/supabase-js";

describe("OutboxService", () => {
  const operatorId = "op-12345";

  beforeEach(() => {
    clearOutbox(operatorId);
    localStorage.clear();
  });

  it("generates valid UUIDs", () => {
    const id1 = generateTaskId();
    const id2 = generateTaskId();
    expect(id1).not.toBe(id2);
    expect(id1).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
  });

  it("persists and retrieves outbox items across operations", () => {
    expect(getOutbox(operatorId)).toEqual([]);

    const createItem: CreateOutboxItem = {
      id: "task-1",
      type: "create",
      task: {
        id: "task-1",
        title: "Test Task",
        description: null,
        priority: 4,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-21T10:00:00.000Z",
        updatedAt: "2026-08-21T10:00:00.000Z",
        version: 1,
      },
      params: {
        id: "task-1",
        title: "Test Task",
      },
      createdAt: "2026-08-21T10:00:00.000Z",
    };

    enqueueOutboxItem(operatorId, createItem);
    expect(getOutbox(operatorId)).toEqual([createItem]);

    const completeItem: CompleteOutboxItem = {
      id: "outbox-2",
      type: "complete",
      taskId: "task-1",
      expectedVersion: 1,
      completedAt: "2026-08-21T10:05:00.000Z",
      createdAt: "2026-08-21T10:05:00.000Z",
    };

    enqueueOutboxItem(operatorId, completeItem);
    expect(getOutbox(operatorId)).toEqual([createItem, completeItem]);

    removeOutboxItem(operatorId, "task-1");
    expect(getOutbox(operatorId)).toEqual([completeItem]);

    clearOutbox(operatorId);
    expect(getOutbox(operatorId)).toEqual([]);
  });

  it("overlays pending creates and completions onto canonical tasks", () => {
    const canonicalTask: Task = {
      id: "task-existing",
      title: "Existing Task",
      description: null,
      priority: 4,
      plan: null,
      labels: [],
      parentId: null,
      completedAt: null,
      createdAt: "2026-08-21T09:00:00.000Z",
      updatedAt: "2026-08-21T09:00:00.000Z",
      version: 1,
    };

    const createItem: CreateOutboxItem = {
      id: "task-new",
      type: "create",
      task: {
        id: "task-new",
        title: "New Offline Task",
        description: null,
        priority: 4,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-21T10:00:00.000Z",
        updatedAt: "2026-08-21T10:00:00.000Z",
        version: 1,
      },
      params: { id: "task-new", title: "New Offline Task" },
      createdAt: "2026-08-21T10:00:00.000Z",
    };

    const completeItem: CompleteOutboxItem = {
      id: "outbox-c1",
      type: "complete",
      taskId: "task-existing",
      expectedVersion: 1,
      completedAt: "2026-08-21T10:10:00.000Z",
      createdAt: "2026-08-21T10:10:00.000Z",
    };

    const overlaid = applyOutboxToTasks(
      [canonicalTask],
      [createItem, completeItem],
    );

    expect(overlaid).toHaveLength(2);
    // New create is present
    expect(overlaid.find((t) => t.id === "task-new")).toBeDefined();
    // Existing task is completed
    const existing = overlaid.find((t) => t.id === "task-existing");
    expect(existing?.completedAt).toBe("2026-08-21T10:10:00.000Z");
  });

  it("identifies network errors accurately", () => {
    expect(isNetworkError(new Error("Failed to fetch"))).toBe(true);
    expect(
      isNetworkError(
        new Error("NetworkError when attempting to fetch resource"),
      ),
    ).toBe(true);
    expect(isNetworkError(new Error("The operation timed out"))).toBe(true);
    expect(
      isNetworkError(new Error("Task version conflict: expected 1, found 2")),
    ).toBe(false);
    expect(isNetworkError(new Error("Task title cannot be empty"))).toBe(false);
  });

  it("drains outbox successfully and notifies callbacks", async () => {
    const validId = "550e8400-e29b-41d4-a716-446655440001";
    const createdTask: Task = {
      id: validId,
      title: "Task 1",
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
      if (fnName === "create_task") {
        return Promise.resolve({ data: createdTask, error: null });
      }
      if (fnName === "complete_task") {
        return Promise.resolve({
          data: {
            ...createdTask,
            completedAt: "2026-08-21T10:05:00Z",
            version: 2,
          },
          error: null,
        });
      }
      return Promise.resolve({ data: null, error: null });
    });

    const mockClient = {
      schema: vi.fn().mockReturnValue({
        rpc: mockRpc,
        from: vi.fn().mockReturnValue({
          select: vi.fn().mockReturnValue({
            eq: vi.fn().mockReturnValue({
              single: vi
                .fn()
                .mockResolvedValue({ data: createdTask, error: null }),
            }),
          }),
        }),
      }),
    } as unknown as SupabaseClient;

    enqueueOutboxItem(operatorId, {
      id: validId,
      type: "create",
      task: createdTask,
      params: { id: validId, title: "Task 1" },
      createdAt: "2026-08-21T10:00:00.000Z",
    });

    enqueueOutboxItem(operatorId, {
      id: "550e8400-e29b-41d4-a716-446655440002",
      type: "complete",
      taskId: validId,
      expectedVersion: 1,
      completedAt: "2026-08-21T10:05:00.000Z",
      createdAt: "2026-08-21T10:05:00.000Z",
    });

    const onCreated = vi.fn();
    const onCompleted = vi.fn();

    await drainOutbox({
      client: mockClient,
      operatorId,
      onTaskCreated: onCreated,
      onTaskCompleted: onCompleted,
    });

    expect(onCreated).toHaveBeenCalledWith(createdTask);
    expect(onCompleted).toHaveBeenCalled();
    expect(getOutbox(operatorId)).toEqual([]);
  });

  it("preserves outbox items upon network failure during drain", async () => {
    const mockRpc = vi.fn().mockRejectedValue(new Error("Failed to fetch"));
    const mockClient = {
      schema: vi.fn().mockReturnValue({
        rpc: mockRpc,
        from: vi.fn().mockReturnValue({
          select: vi.fn().mockReturnValue({
            eq: vi.fn().mockReturnValue({
              single: vi.fn().mockRejectedValue(new Error("Failed to fetch")),
            }),
          }),
        }),
      }),
    } as unknown as SupabaseClient;

    const createItem: CreateOutboxItem = {
      id: "task-1",
      type: "create",
      task: {
        id: "task-1",
        title: "Offline Create",
        description: null,
        priority: 4,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-21T10:00:00.000Z",
        updatedAt: "2026-08-21T10:00:00.000Z",
        version: 1,
      },
      params: { id: "task-1", title: "Offline Create" },
      createdAt: "2026-08-21T10:00:00.000Z",
    };

    enqueueOutboxItem(operatorId, createItem);

    await drainOutbox({
      client: mockClient,
      operatorId,
    });

    // Item was NOT removed from outbox
    expect(getOutbox(operatorId)).toEqual([createItem]);
  });

  it("discards unrecognized outbox item types to prevent infinite drain loop", async () => {
    const mockClient = {
      schema: vi.fn(),
    } as unknown as SupabaseClient;

    const invalidItem = {
      id: "unsupported-1",
      type: "update",
      createdAt: "2026-08-21T10:00:00.000Z",
    } as unknown as OutboxItem;

    enqueueOutboxItem(operatorId, invalidItem);

    const onError = vi.fn();
    await drainOutbox({
      client: mockClient,
      operatorId,
      onError,
    });

    expect(onError).toHaveBeenCalledWith(
      expect.objectContaining({
        message: expect.stringMatching(/unrecognized/i),
      }),
      invalidItem,
    );
    expect(getOutbox(operatorId)).toEqual([]);
  }, 2000);

  it("discards unrecognized outbox item without id by dropping queue head", async () => {
    const mockClient = {
      schema: vi.fn(),
    } as unknown as SupabaseClient;

    const invalidItemWithoutId = {
      type: "unknown_shape",
    } as unknown as OutboxItem;

    enqueueOutboxItem(operatorId, invalidItemWithoutId);

    const onError = vi.fn();
    await drainOutbox({
      client: mockClient,
      operatorId,
      onError,
    });

    expect(onError).toHaveBeenCalled();
    expect(getOutbox(operatorId)).toEqual([]);
  }, 2000);
});
