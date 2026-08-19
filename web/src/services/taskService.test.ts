import { describe, it, expect, vi } from "vitest";
import {
  filterInboxTasks,
  filterCompletedTasks,
  fetchTasks,
  createTask,
  updateTask,
  completeTask,
  uncompleteTask,
} from "./taskService";
import type { Task } from "../contracts/task";
import type { SupabaseClient } from "@supabase/supabase-js";

describe("Task Domain Service Seam", () => {
  const baseTask: Task = {
    id: "550e8400-e29b-41d4-a716-446655440000",
    title: "Draft release notes",
    description: null,
    priority: 4,
    plan: null,
    labels: [],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-18T20:00:00.000Z",
    updatedAt: "2026-08-18T20:00:00.000Z",
    version: 1,
  };

  describe("filterInboxTasks", () => {
    it("includes open, undated, top-level tasks", () => {
      const tasks: Task[] = [baseTask];
      expect(filterInboxTasks(tasks)).toEqual([baseTask]);
    });

    it("excludes completed tasks", () => {
      const completedTask: Task = {
        ...baseTask,
        id: "550e8400-e29b-41d4-a716-446655440001",
        completedAt: "2026-08-18T21:00:00.000Z",
      };
      expect(filterInboxTasks([baseTask, completedTask])).toEqual([baseTask]);
    });

    it("excludes dated tasks (date-only, floating, instant)", () => {
      const dateOnlyTask: Task = {
        ...baseTask,
        id: "550e8400-e29b-41d4-a716-446655440002",
        plan: { date: "2026-08-19" },
      };
      const floatingTask: Task = {
        ...baseTask,
        id: "550e8400-e29b-41d4-a716-446655440003",
        plan: { type: "floating", date: "2026-08-19", time: "14:00" },
      };
      const instantTask: Task = {
        ...baseTask,
        id: "550e8400-e29b-41d4-a716-446655440004",
        plan: { type: "instant", at: "2026-08-19T14:00:00.000Z" },
      };

      expect(
        filterInboxTasks([baseTask, dateOnlyTask, floatingTask, instantTask]),
      ).toEqual([baseTask]);
    });

    it("excludes subtasks with parentId", () => {
      const subtask: Task = {
        ...baseTask,
        id: "550e8400-e29b-41d4-a716-446655440005",
        parentId: baseTask.id,
      };
      expect(filterInboxTasks([baseTask, subtask])).toEqual([baseTask]);
    });
  });

  describe("filterCompletedTasks", () => {
    it("includes only completed tasks and sorts them newest-first by completedAt", () => {
      const task1: Task = {
        ...baseTask,
        id: "550e8400-e29b-41d4-a716-446655440011",
        completedAt: "2026-08-18T10:00:00.000Z",
      };
      const task2: Task = {
        ...baseTask,
        id: "550e8400-e29b-41d4-a716-446655440012",
        completedAt: "2026-08-18T12:00:00.000Z",
      };
      const task3: Task = {
        ...baseTask,
        id: "550e8400-e29b-41d4-a716-446655440013",
        completedAt: "2026-08-18T11:00:00.000Z",
      };
      const openTask: Task = {
        ...baseTask,
        id: "550e8400-e29b-41d4-a716-446655440014",
        completedAt: null,
      };

      const result = filterCompletedTasks([task1, openTask, task2, task3]);
      expect(result).toHaveLength(3);
      expect(result.map((t) => t.id)).toEqual([task2.id, task3.id, task1.id]);
    });
  });

  describe("fetchTasks", () => {
    it("fetches canonical tasks from api schema and parses through Effect schema", async () => {
      const rawTasks = [
        {
          id: "550e8400-e29b-41d4-a716-446655440000",
          title: "Inbox Task 1",
          description: null,
          priority: 4,
          plan: null,
          labels: [],
          parentId: null,
          completedAt: null,
          createdAt: "2026-08-18T20:00:00.000Z",
          updatedAt: "2026-08-18T20:00:00.000Z",
          version: 1,
        },
      ];

      const mockSelect = vi
        .fn()
        .mockResolvedValue({ data: rawTasks, error: null });
      const mockFrom = vi.fn().mockReturnValue({ select: mockSelect });
      const mockSchema = vi.fn().mockReturnValue({ from: mockFrom });
      const mockClient = { schema: mockSchema } as unknown as SupabaseClient;

      const result = await fetchTasks(mockClient);

      expect(mockSchema).toHaveBeenCalledWith("api");
      expect(mockFrom).toHaveBeenCalledWith("tasks");
      expect(mockSelect).toHaveBeenCalledWith("*");
      expect(result).toHaveLength(1);
      expect(result[0].title).toBe("Inbox Task 1");
    });

    it("throws when Supabase query returns an error", async () => {
      const mockSelect = vi.fn().mockResolvedValue({
        data: null,
        error: { message: "Database connection failed", code: "PGRST000" },
      });
      const mockFrom = vi.fn().mockReturnValue({ select: mockSelect });
      const mockSchema = vi.fn().mockReturnValue({ from: mockFrom });
      const mockClient = { schema: mockSchema } as unknown as SupabaseClient;

      await expect(fetchTasks(mockClient)).rejects.toThrow(
        /Database connection failed/,
      );
    });
  });

  describe("createTask", () => {
    it("calls create_task RPC on api schema and returns validated Task", async () => {
      const rawCreated = {
        id: "550e8400-e29b-41d4-a716-446655440010",
        title: "Buy groceries",
        description: "Almond milk and oats",
        priority: 2,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-18T20:05:00.000Z",
        updatedAt: "2026-08-18T20:05:00.000Z",
        version: 1,
      };

      const mockRpc = vi
        .fn()
        .mockResolvedValue({ data: rawCreated, error: null });
      const mockSchema = vi.fn().mockReturnValue({ rpc: mockRpc });
      const mockClient = { schema: mockSchema } as unknown as SupabaseClient;

      const task = await createTask(mockClient, {
        title: "Buy groceries",
        description: "Almond milk and oats",
        priority: 2,
      });

      expect(mockSchema).toHaveBeenCalledWith("api");
      expect(mockRpc).toHaveBeenCalledWith("create_task", {
        title: "Buy groceries",
        description: "Almond milk and oats",
        priority: 2,
        plan: null,
        parent_id: null,
        labels: [],
      });
      expect(task.id).toBe("550e8400-e29b-41d4-a716-446655440010");
      expect(task.title).toBe("Buy groceries");
      expect(task.description).toBe("Almond milk and oats");
      expect(task.priority).toBe(2);
    });

    it("passes labels array to create_task RPC", async () => {
      const rawCreated = {
        id: "550e8400-e29b-41d4-a716-446655440010",
        title: "Triage bug",
        description: null,
        priority: 1,
        plan: null,
        labels: ["22222222-2222-2222-2222-222222222222"],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-18T20:05:00.000Z",
        updatedAt: "2026-08-18T20:05:00.000Z",
        version: 1,
      };

      const mockRpc = vi
        .fn()
        .mockResolvedValue({ data: rawCreated, error: null });
      const mockSchema = vi.fn().mockReturnValue({ rpc: mockRpc });
      const mockClient = { schema: mockSchema } as unknown as SupabaseClient;

      const task = await createTask(mockClient, {
        title: "Triage bug",
        labels: ["22222222-2222-2222-2222-222222222222"],
      });

      expect(mockRpc).toHaveBeenCalledWith("create_task", {
        title: "Triage bug",
        description: null,
        priority: 4,
        plan: null,
        parent_id: null,
        labels: ["22222222-2222-2222-2222-222222222222"],
      });
      expect(task.labels).toEqual(["22222222-2222-2222-2222-222222222222"]);
    });

    it("rejects empty or whitespace-only titles", async () => {
      const mockRpc = vi.fn();
      const mockSchema = vi.fn().mockReturnValue({ rpc: mockRpc });
      const mockClient = { schema: mockSchema } as unknown as SupabaseClient;

      await expect(createTask(mockClient, { title: "   " })).rejects.toThrow(
        /Task title cannot be empty/,
      );

      expect(mockRpc).not.toHaveBeenCalled();
    });
  });

  describe("updateTask", () => {
    it("calls update_task RPC on api schema and returns updated Task", async () => {
      const rawUpdated = {
        id: "550e8400-e29b-41d4-a716-446655440000",
        title: "Updated Title",
        description: "Updated description",
        priority: 1,
        plan: null,
        labels: ["22222222-2222-2222-2222-222222222222"],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-18T20:00:00.000Z",
        updatedAt: "2026-08-18T20:10:00.000Z",
        version: 2,
      };

      const mockRpc = vi
        .fn()
        .mockResolvedValue({ data: rawUpdated, error: null });
      const mockSchema = vi.fn().mockReturnValue({ rpc: mockRpc });
      const mockClient = { schema: mockSchema } as unknown as SupabaseClient;

      const task = await updateTask(mockClient, {
        id: "550e8400-e29b-41d4-a716-446655440000",
        title: "Updated Title",
        description: "Updated description",
        priority: 1,
        expectedVersion: 1,
        labels: ["22222222-2222-2222-2222-222222222222"],
      });

      expect(mockSchema).toHaveBeenCalledWith("api");
      expect(mockRpc).toHaveBeenCalledWith("update_task", {
        id: "550e8400-e29b-41d4-a716-446655440000",
        title: "Updated Title",
        description: "Updated description",
        priority: 1,
        plan: null,
        parent_id: null,
        expected_version: 1,
        labels: ["22222222-2222-2222-2222-222222222222"],
      });
      expect(task.title).toBe("Updated Title");
      expect(task.priority).toBe(1);
      expect(task.labels).toEqual(["22222222-2222-2222-2222-222222222222"]);
      expect(task.version).toBe(2);
    });

    it("rejects empty or whitespace-only titles when title is provided", async () => {
      const mockRpc = vi.fn();
      const mockSchema = vi.fn().mockReturnValue({ rpc: mockRpc });
      const mockClient = { schema: mockSchema } as unknown as SupabaseClient;

      await expect(
        updateTask(mockClient, {
          id: "550e8400-e29b-41d4-a716-446655440000",
          title: "   ",
        }),
      ).rejects.toThrow(/Task title cannot be empty/);

      expect(mockRpc).not.toHaveBeenCalled();
    });

    it("throws when RPC returns an error (e.g. editing completed task)", async () => {
      const mockRpc = vi.fn().mockResolvedValue({
        data: null,
        error: {
          message: "Completed tasks cannot be edited. Uncomplete first.",
          code: "P0001",
        },
      });
      const mockSchema = vi.fn().mockReturnValue({ rpc: mockRpc });
      const mockClient = { schema: mockSchema } as unknown as SupabaseClient;

      await expect(
        updateTask(mockClient, {
          id: "550e8400-e29b-41d4-a716-446655440000",
          title: "New Title",
        }),
      ).rejects.toThrow(
        /Completed tasks cannot be edited\. Uncomplete first\./,
      );
    });
  });

  describe("completeTask", () => {
    it("calls complete_task RPC on api schema and returns completed Task", async () => {
      const rawCompleted = {
        id: "550e8400-e29b-41d4-a716-446655440000",
        title: "Draft release notes",
        description: null,
        priority: 4,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: "2026-08-18T20:15:00.000Z",
        createdAt: "2026-08-18T20:00:00.000Z",
        updatedAt: "2026-08-18T20:15:00.000Z",
        version: 2,
      };

      const mockRpc = vi
        .fn()
        .mockResolvedValue({ data: rawCompleted, error: null });
      const mockSchema = vi.fn().mockReturnValue({ rpc: mockRpc });
      const mockClient = { schema: mockSchema } as unknown as SupabaseClient;

      const task = await completeTask(
        mockClient,
        "550e8400-e29b-41d4-a716-446655440000",
        "2026-08-18T20:15:00.000Z",
      );

      expect(mockSchema).toHaveBeenCalledWith("api");
      expect(mockRpc).toHaveBeenCalledWith("complete_task", {
        id: "550e8400-e29b-41d4-a716-446655440000",
        completed_at: "2026-08-18T20:15:00.000Z",
      });
      expect(task.completedAt).toBe("2026-08-18T20:15:00.000Z");
      expect(task.version).toBe(2);
    });
  });

  describe("uncompleteTask", () => {
    it("calls uncomplete_task RPC on api schema and returns uncompleted Task", async () => {
      const rawUncompleted = {
        id: "550e8400-e29b-41d4-a716-446655440000",
        title: "Draft release notes",
        description: null,
        priority: 4,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-18T20:00:00.000Z",
        updatedAt: "2026-08-18T20:20:00.000Z",
        version: 3,
      };

      const mockRpc = vi
        .fn()
        .mockResolvedValue({ data: rawUncompleted, error: null });
      const mockSchema = vi.fn().mockReturnValue({ rpc: mockRpc });
      const mockClient = { schema: mockSchema } as unknown as SupabaseClient;

      const task = await uncompleteTask(
        mockClient,
        "550e8400-e29b-41d4-a716-446655440000",
      );

      expect(mockSchema).toHaveBeenCalledWith("api");
      expect(mockRpc).toHaveBeenCalledWith("uncomplete_task", {
        id: "550e8400-e29b-41d4-a716-446655440000",
      });
      expect(task.completedAt).toBeNull();
      expect(task.version).toBe(3);
    });
  });
});
