import type { SupabaseClient } from "@supabase/supabase-js";
import { type Comment, parseComment } from "../contracts/task";

export interface CreateCommentParams {
  readonly id?: string;
  readonly taskId: string;
  readonly content: string;
}

/**
 * Fetches comments from api.comments view, optionally filtered by taskId.
 */
export async function fetchComments(
  client: SupabaseClient,
  taskId?: string,
): Promise<Comment[]> {
  let query = client.schema("api").from("comments").select("*");

  if (taskId) {
    query = query.eq("taskId", taskId);
  }

  const { data, error } = await query;

  if (error) {
    throw new Error(
      `Failed to fetch comments: ${error.message} (${error.code})`,
    );
  }

  if (!data || !Array.isArray(data)) {
    return [];
  }

  return data.map((item) => parseComment(item));
}

/**
 * Creates a dated Comment under a Task via api.create_comment RPC.
 */
export async function createComment(
  client: SupabaseClient,
  params: CreateCommentParams,
): Promise<Comment> {
  const trimmedContent = params.content.trim();
  if (trimmedContent.length === 0) {
    throw new Error("Comment content cannot be empty");
  }

  const { data, error } = await client.schema("api").rpc("create_comment", {
    task_id: params.taskId,
    content: trimmedContent,
    id: params.id ?? null,
  });

  if (error) {
    throw new Error(
      `Failed to create comment: ${error.message} (${error.code})`,
    );
  }

  return parseComment(data);
}
