import type { SupabaseClient } from "@supabase/supabase-js";
import { type Label, parseLabel } from "../contracts/task";

export interface CreateLabelParams {
  readonly id?: string;
  readonly name: string;
  readonly color: string;
}

export interface UpdateLabelParams {
  readonly id: string;
  readonly name?: string;
  readonly color?: string;
}

interface RawDbLabel {
  id: string;
  name: string;
  color: string;
  created_at?: string;
  updated_at?: string;
}

function mapDbRowToLabel(row: RawDbLabel): Label {
  return parseLabel({
    id: row.id,
    name: row.name,
    color: row.color,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  });
}

/**
 * Fetches all labels belonging to the authenticated Operator from public.labels.
 */
export async function fetchLabels(client: SupabaseClient): Promise<Label[]> {
  const { data, error } = await client
    .from("labels")
    .select("*")
    .order("created_at", { ascending: true });

  if (error) {
    throw new Error(`Failed to fetch labels: ${error.message} (${error.code})`);
  }

  if (!data || !Array.isArray(data)) {
    return [];
  }

  return data.map((item) => mapDbRowToLabel(item as RawDbLabel));
}

/**
 * Creates a new colored Label for the Operator, enforcing unique names.
 */
export async function createLabel(
  client: SupabaseClient,
  input: CreateLabelParams,
): Promise<Label> {
  const trimmedName = input.name.trim();
  if (trimmedName.length === 0) {
    throw new Error("Label name cannot be empty");
  }

  const trimmedColor = input.color.trim();
  if (trimmedColor.length === 0) {
    throw new Error("Label color cannot be empty");
  }

  const payload: Record<string, unknown> = {
    name: trimmedName,
    color: trimmedColor,
  };
  if (input.id) {
    payload.id = input.id;
  }

  const { data, error } = await client
    .from("labels")
    .insert(payload)
    .select("*")
    .single();

  if (error) {
    if (error.code === "23505") {
      throw new Error("A label with this name already exists");
    }
    throw new Error(`Failed to create label: ${error.message} (${error.code})`);
  }

  return mapDbRowToLabel(data as RawDbLabel);
}

/**
 * Renames and/or recolors an existing Label, preserving its stable identity.
 */
export async function updateLabel(
  client: SupabaseClient,
  input: UpdateLabelParams,
): Promise<Label> {
  const updates: Record<string, unknown> = {
    updated_at: new Date().toISOString(),
  };

  if (input.name !== undefined) {
    const trimmed = input.name.trim();
    if (trimmed.length === 0) {
      throw new Error("Label name cannot be empty");
    }
    updates.name = trimmed;
  }

  if (input.color !== undefined) {
    const trimmedColor = input.color.trim();
    if (trimmedColor.length === 0) {
      throw new Error("Label color cannot be empty");
    }
    updates.color = trimmedColor;
  }

  const { data, error } = await client
    .from("labels")
    .update(updates)
    .eq("id", input.id)
    .select("*")
    .single();

  if (error) {
    if (error.code === "23505") {
      throw new Error("A label with this name already exists");
    }
    throw new Error(`Failed to update label: ${error.message} (${error.code})`);
  }

  return mapDbRowToLabel(data as RawDbLabel);
}

/**
 * Deletes a Label by its stable identity. Cascades to task associations.
 */
export async function deleteLabel(
  client: SupabaseClient,
  labelId: string,
): Promise<void> {
  const { error } = await client.from("labels").delete().eq("id", labelId);

  if (error) {
    throw new Error(`Failed to delete label: ${error.message} (${error.code})`);
  }
}
