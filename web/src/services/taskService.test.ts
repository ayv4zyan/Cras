import { describe, it, expect, vi } from "vitest";
import { filterInboxTasks, fetchTasks, createTask } from "./taskService";
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
        description: null,
        priority: 4,
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

      const task = await createTask(mockClient, { title: "Buy groceries" });

      expect(mockSchema).toHaveBeenCalledWith("api");
      expect(mockRpc).toHaveBeenCalledWith("create_task", {
        title: "Buy groceries",
        description: null,
        priority: 4,
        plan: null,
        parentId: null,
        labels: [],
      });
      expect(task.id).toBe("550e8400-e29b-41d4-a716-446655440010");
      expect(task.title).toBe("Buy groceries");
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
});
