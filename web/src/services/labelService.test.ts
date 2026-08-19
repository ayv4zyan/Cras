import { describe, it, expect, vi } from "vitest";
import type { SupabaseClient } from "@supabase/supabase-js";
import {
  fetchLabels,
  createLabel,
  updateLabel,
  deleteLabel,
} from "./labelService";

describe("Label Domain Service Seam", () => {
  const sampleDbLabels = [
    {
      id: "22222222-2222-2222-2222-222222222222",
      operator_id: "op-1",
      name: "Urgent",
      color: "#ef4444",
      created_at: "2026-08-18T10:00:00.000Z",
      updated_at: "2026-08-18T10:00:00.000Z",
    },
    {
      id: "33333333-3333-3333-3333-333333333333",
      operator_id: "op-1",
      name: "Work",
      color: "#3b82f6",
      created_at: "2026-08-18T11:00:00.000Z",
      updated_at: "2026-08-18T11:00:00.000Z",
    },
  ];

  describe("fetchLabels", () => {
    it("fetches labels from public.labels and parses through Effect schema", async () => {
      const mockClient = {
        from: vi.fn().mockReturnValue({
          select: vi.fn().mockReturnValue({
            order: vi.fn().mockResolvedValue({
              data: sampleDbLabels,
              error: null,
            }),
          }),
        }),
      } as unknown as SupabaseClient;

      const labels = await fetchLabels(mockClient);

      expect(mockClient.from).toHaveBeenCalledWith("labels");
      expect(labels).toHaveLength(2);
      expect(labels[0]).toEqual({
        id: "22222222-2222-2222-2222-222222222222",
        name: "Urgent",
        color: "#ef4444",
        createdAt: "2026-08-18T10:00:00.000Z",
        updatedAt: "2026-08-18T10:00:00.000Z",
      });
      expect(labels[1].name).toBe("Work");
    });

    it("throws when Supabase query returns an error", async () => {
      const mockClient = {
        from: vi.fn().mockReturnValue({
          select: vi.fn().mockReturnValue({
            order: vi.fn().mockResolvedValue({
              data: null,
              error: { message: "Network failure", code: "PGRST301" },
            }),
          }),
        }),
      } as unknown as SupabaseClient;

      await expect(fetchLabels(mockClient)).rejects.toThrow(
        "Failed to fetch labels: Network failure (PGRST301)",
      );
    });
  });

  describe("createLabel", () => {
    it("creates a new label with trimmed name and returns validated Label", async () => {
      const mockClient = {
        from: vi.fn().mockReturnValue({
          insert: vi.fn().mockReturnValue({
            select: vi.fn().mockReturnValue({
              single: vi.fn().mockResolvedValue({
                data: {
                  id: "44444444-4444-4444-4444-444444444444",
                  operator_id: "op-1",
                  name: "Home",
                  color: "#10b981",
                  created_at: "2026-08-18T12:00:00.000Z",
                  updated_at: "2026-08-18T12:00:00.000Z",
                },
                error: null,
              }),
            }),
          }),
        }),
      } as unknown as SupabaseClient;

      const newLabel = await createLabel(mockClient, {
        name: "  Home  ",
        color: "#10b981",
      });

      expect(newLabel.id).toBe("44444444-4444-4444-4444-444444444444");
      expect(newLabel.name).toBe("Home");
      expect(newLabel.color).toBe("#10b981");
    });

    it("rejects empty or whitespace-only names", async () => {
      const mockClient = {} as SupabaseClient;
      await expect(
        createLabel(mockClient, { name: "   ", color: "#10b981" }),
      ).rejects.toThrow("Label name cannot be empty");
    });

    it("rejects empty colors", async () => {
      const mockClient = {} as SupabaseClient;
      await expect(
        createLabel(mockClient, { name: "Home", color: "" }),
      ).rejects.toThrow("Label color cannot be empty");
    });

    it("maps unique constraint violation (code 23505) to duplicate name error", async () => {
      const mockClient = {
        from: vi.fn().mockReturnValue({
          insert: vi.fn().mockReturnValue({
            select: vi.fn().mockReturnValue({
              single: vi.fn().mockResolvedValue({
                data: null,
                error: {
                  message: 'duplicate key value violates unique constraint "uq_labels_name_operator"',
                  code: "23505",
                },
              }),
            }),
          }),
        }),
      } as unknown as SupabaseClient;

      await expect(
        createLabel(mockClient, { name: "Urgent", color: "#ef4444" }),
      ).rejects.toThrow("A label with this name already exists");
    });
  });

  describe("updateLabel", () => {
    it("renames and recolors a label while preserving identity", async () => {
      const mockClient = {
        from: vi.fn().mockReturnValue({
          update: vi.fn().mockReturnValue({
            eq: vi.fn().mockReturnValue({
              select: vi.fn().mockReturnValue({
                single: vi.fn().mockResolvedValue({
                  data: {
                    id: "22222222-2222-2222-2222-222222222222",
                    operator_id: "op-1",
                    name: "High Priority",
                    color: "#ea580c",
                    created_at: "2026-08-18T10:00:00.000Z",
                    updated_at: "2026-08-18T13:00:00.000Z",
                  },
                  error: null,
                }),
              }),
            }),
          }),
        }),
      } as unknown as SupabaseClient;

      const updated = await updateLabel(mockClient, {
        id: "22222222-2222-2222-2222-222222222222",
        name: "High Priority",
        color: "#ea580c",
      });

      expect(updated.id).toBe("22222222-2222-2222-2222-222222222222");
      expect(updated.name).toBe("High Priority");
      expect(updated.color).toBe("#ea580c");
    });

    it("rejects empty name if provided in update", async () => {
      const mockClient = {} as SupabaseClient;
      await expect(
        updateLabel(mockClient, {
          id: "22222222-2222-2222-2222-222222222222",
          name: "  ",
        }),
      ).rejects.toThrow("Label name cannot be empty");
    });

    it("maps unique constraint violation during rename to duplicate name error", async () => {
      const mockClient = {
        from: vi.fn().mockReturnValue({
          update: vi.fn().mockReturnValue({
            eq: vi.fn().mockReturnValue({
              select: vi.fn().mockReturnValue({
                single: vi.fn().mockResolvedValue({
                  data: null,
                  error: {
                    message: 'duplicate key value violates unique constraint "uq_labels_name_operator"',
                    code: "23505",
                  },
                }),
              }),
            }),
          }),
        }),
      } as unknown as SupabaseClient;

      await expect(
        updateLabel(mockClient, {
          id: "22222222-2222-2222-2222-222222222222",
          name: "Work",
        }),
      ).rejects.toThrow("A label with this name already exists");
    });
  });

  describe("deleteLabel", () => {
    it("deletes label by id", async () => {
      const mockClient = {
        from: vi.fn().mockReturnValue({
          delete: vi.fn().mockReturnValue({
            eq: vi.fn().mockResolvedValue({
              error: null,
            }),
          }),
        }),
      } as unknown as SupabaseClient;

      await expect(
        deleteLabel(mockClient, "22222222-2222-2222-2222-222222222222"),
      ).resolves.toBeUndefined();
    });

    it("throws when delete returns an error", async () => {
      const mockClient = {
        from: vi.fn().mockReturnValue({
          delete: vi.fn().mockReturnValue({
            eq: vi.fn().mockResolvedValue({
              error: { message: "Delete failed", code: "42501" },
            }),
          }),
        }),
      } as unknown as SupabaseClient;

      await expect(
        deleteLabel(mockClient, "22222222-2222-2222-2222-222222222222"),
      ).rejects.toThrow("Failed to delete label: Delete failed (42501)");
    });
  });
});
