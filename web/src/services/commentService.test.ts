import { describe, it, expect, vi, beforeEach } from "vitest";
import type { SupabaseClient } from "@supabase/supabase-js";
import {
  fetchComments,
  createComment,
  type CreateCommentParams,
} from "./commentService";

describe("commentService", () => {
  let mockClient: SupabaseClient;

  beforeEach(() => {
    mockClient = {
      schema: vi.fn(),
    } as unknown as SupabaseClient;
  });

  describe("fetchComments", () => {
    it("fetches comments from api.comments view and validates schema", async () => {
      const mockData = [
        {
          id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          taskId: "11111111-1111-1111-1111-111111111111",
          content: "First dated comment",
          createdAt: "2026-08-19T10:00:00Z",
        },
      ];

      const selectMock = vi.fn().mockResolvedValue({
        data: mockData,
        error: null,
      });

      const fromMock = vi.fn().mockReturnValue({
        select: selectMock,
      });

      (mockClient.schema as ReturnType<typeof vi.fn>).mockReturnValue({
        from: fromMock,
      });

      const result = await fetchComments(mockClient);

      expect(mockClient.schema).toHaveBeenCalledWith("api");
      expect(fromMock).toHaveBeenCalledWith("comments");
      expect(selectMock).toHaveBeenCalledWith("*");
      expect(result).toHaveLength(1);
      expect(result[0].id).toBe("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
      expect(result[0].content).toBe("First dated comment");
      expect(result[0].taskId).toBe("11111111-1111-1111-1111-111111111111");
    });

    it("fetches comments filtered by taskId if provided", async () => {
      const mockData = [
        {
          id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          taskId: "11111111-1111-1111-1111-111111111111",
          content: "First dated comment",
          createdAt: "2026-08-19T10:00:00Z",
        },
      ];

      const eqMock = vi.fn().mockResolvedValue({
        data: mockData,
        error: null,
      });

      const selectMock = vi.fn().mockReturnValue({
        eq: eqMock,
      });

      const fromMock = vi.fn().mockReturnValue({
        select: selectMock,
      });

      (mockClient.schema as ReturnType<typeof vi.fn>).mockReturnValue({
        from: fromMock,
      });

      const result = await fetchComments(
        mockClient,
        "11111111-1111-1111-1111-111111111111",
      );

      expect(eqMock).toHaveBeenCalledWith(
        "taskId",
        "11111111-1111-1111-1111-111111111111",
      );
      expect(result).toHaveLength(1);
    });

    it("returns empty array when data is null or empty", async () => {
      const selectMock = vi.fn().mockResolvedValue({
        data: null,
        error: null,
      });

      (mockClient.schema as ReturnType<typeof vi.fn>).mockReturnValue({
        from: vi.fn().mockReturnValue({
          select: selectMock,
        }),
      });

      const result = await fetchComments(mockClient);
      expect(result).toEqual([]);
    });

    it("throws error when Supabase query fails", async () => {
      const selectMock = vi.fn().mockResolvedValue({
        data: null,
        error: { message: "Permission denied", code: "42501" },
      });

      (mockClient.schema as ReturnType<typeof vi.fn>).mockReturnValue({
        from: vi.fn().mockReturnValue({
          select: selectMock,
        }),
      });

      await expect(fetchComments(mockClient)).rejects.toThrow(
        "Failed to fetch comments: Permission denied (42501)",
      );
    });
  });

  describe("createComment", () => {
    it("creates a comment via api.create_comment RPC and returns validated Comment", async () => {
      const mockCreatedComment = {
        id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        taskId: "11111111-1111-1111-1111-111111111111",
        content: "Verified testing on staging",
        createdAt: "2026-08-19T10:05:00Z",
      };

      const rpcMock = vi.fn().mockResolvedValue({
        data: mockCreatedComment,
        error: null,
      });

      (mockClient.schema as ReturnType<typeof vi.fn>).mockReturnValue({
        rpc: rpcMock,
      });

      const params: CreateCommentParams = {
        taskId: "11111111-1111-1111-1111-111111111111",
        content: "  Verified testing on staging  ",
      };

      const result = await createComment(mockClient, params);

      expect(mockClient.schema).toHaveBeenCalledWith("api");
      expect(rpcMock).toHaveBeenCalledWith("create_comment", {
        task_id: "11111111-1111-1111-1111-111111111111",
        content: "Verified testing on staging",
        id: null,
      });
      expect(result).toEqual(mockCreatedComment);
    });

    it("rejects empty or whitespace-only content before network call", async () => {
      await expect(
        createComment(mockClient, {
          taskId: "11111111-1111-1111-1111-111111111111",
          content: "   ",
        }),
      ).rejects.toThrow("Comment content cannot be empty");

      expect(mockClient.schema).not.toHaveBeenCalled();
    });

    it("throws error when RPC call fails", async () => {
      const rpcMock = vi.fn().mockResolvedValue({
        data: null,
        error: { message: "Task not found or unauthorized", code: "P0001" },
      });

      (mockClient.schema as ReturnType<typeof vi.fn>).mockReturnValue({
        rpc: rpcMock,
      });

      await expect(
        createComment(mockClient, {
          taskId: "11111111-1111-1111-1111-111111111111",
          content: "Some comment",
        }),
      ).rejects.toThrow(
        "Failed to create comment: Task not found or unauthorized (P0001)",
      );
    });
  });
});
