import type { SupabaseClient } from "@supabase/supabase-js";
import type { Task, Priority, TaskPlan } from "../contracts/task";
import {
  createPlanFromInputs,
  formatPlanDate,
  formatPlanTime,
  getDeviceTimezone,
  type TimedPlanType,
} from "./temporalService";

export interface VoiceModelCatalogEntry {
  readonly key: string;
  readonly type: "stt" | "extractor";
  readonly name: string;
  readonly is_default: boolean;
  readonly is_enabled: boolean;
  readonly created_at?: string;
}

export interface ExtractedDraftPayload {
  readonly title: string;
  readonly description?: string | null;
  readonly priority?: number | null;
  readonly plan_date?: string | null;
  readonly plan_time?: string | null;
  readonly plan_type?: "instant" | "floating" | null;
  readonly target_draft_index?: number | null;
}

export interface ExtractedEditPayload {
  readonly title?: string | null;
  readonly description?: string | null;
  readonly priority?: number | null;
  readonly plan_date?: string | null;
  readonly plan_time?: string | null;
  readonly plan_type?: "instant" | "floating" | null;
  readonly clear_plan?: boolean | null;
}

export interface DraftTask {
  readonly id: string; // Temporary UUID on client
  readonly title: string;
  readonly description: string | null;
  readonly priority: Priority;
  readonly plan: TaskPlan | null;
  readonly labels: string[];
  readonly parentId: string | null;
  readonly originalTaskId?: string | null;
  readonly validationError: string | null;
}

export interface VoiceCaptureRequestOptions {
  readonly audioBlob: Blob;
  readonly recordingStartTime: string;
  readonly timezone?: string;
  readonly focusedTask?: Task | null;
  readonly existingDrafts?: readonly DraftTask[] | null;
  readonly effectiveDefaultTimedPlanType: TimedPlanType;
}

export interface VoiceCaptureResult {
  readonly transcript: string;
  readonly mode: "create" | "edit";
  readonly drafts: readonly DraftTask[];
  readonly editProposal?: DraftTask | null;
}

export interface VoiceError {
  readonly status: number;
  readonly code?: string;
  readonly message: string;
  readonly earliestRetryAt?: string;
  readonly retryAfterSeconds?: number;
  readonly isNetworkError?: boolean;
}

/**
 * Builds a DraftTask from an extracted draft payload.
 */
export function createDraftTaskFromExtracted(
  payload: ExtractedDraftPayload,
  effectiveDefault: TimedPlanType,
  originalTaskId?: string | null,
): DraftTask {
  const id = crypto.randomUUID();
  const title = (payload.title || "Untitled task").trim();
  const description = payload.description?.trim() || null;
  const priority = (
    payload.priority && payload.priority >= 1 && payload.priority <= 4
      ? payload.priority
      : 4
  ) as Priority;

  let plan: TaskPlan | null = null;
  let validationError: string | null = null;

  const date = payload.plan_date?.trim() || null;
  const time = payload.plan_time?.trim() || null;
  const explicitType = payload.plan_type;

  if (date) {
    if (time) {
      // Timed plan: explicit type overrides effective default
      const chosenType = explicitType ?? effectiveDefault;
      plan = createPlanFromInputs({
        date,
        time,
        type: chosenType,
        effectiveDefault,
      });
    } else {
      // Untimed date (Date-only)
      if (explicitType) {
        // An explicit Instant/Floating instruction without a clock time is invalid
        validationError =
          "An explicit Instant or Floating plan requires a clock time. Please provide a time or change to Date-only.";
        plan = { date };
      } else {
        plan = { date };
      }
    }
  } else if (explicitType) {
    validationError =
      "An explicit Instant or Floating plan requires a date and clock time.";
  }

  return {
    id,
    title,
    description,
    priority,
    plan,
    labels: [],
    parentId: null,
    originalTaskId: originalTaskId ?? null,
    validationError,
  };
}

/**
 * Switches a DraftTask's plan between Instant and Floating.
 * Preserves the displayed calendar date and clock time and reinterprets their meaning.
 */
export function switchDraftTimedPlanType(
  draft: DraftTask,
  newType: TimedPlanType,
  effectiveDefault: TimedPlanType = "instant",
): DraftTask {
  if (!draft.plan) {
    return draft;
  }

  const currentDate = formatPlanDate(draft.plan);
  const currentTime = formatPlanTime(draft.plan);

  if (!currentDate || !currentTime) {
    return {
      ...draft,
      validationError:
        "Cannot switch plan type on an untimed task without a clock time.",
    };
  }

  const updatedPlan = createPlanFromInputs({
    date: currentDate,
    time: currentTime,
    type: newType,
    effectiveDefault,
  });

  return {
    ...draft,
    plan: updatedPlan,
    validationError: null,
  };
}

/**
 * Sends audio and metadata to the authenticated Supabase Edge Function `voice-capture`.
 */
export async function sendVoiceCapture(
  client: SupabaseClient,
  options: VoiceCaptureRequestOptions,
): Promise<VoiceCaptureResult> {
  const {
    audioBlob,
    recordingStartTime,
    timezone = getDeviceTimezone(),
    focusedTask = null,
    existingDrafts = null,
    effectiveDefaultTimedPlanType,
  } = options;

  const {
    data: { session },
  } = await client.auth.getSession();

  if (!session) {
    throw {
      status: 401,
      code: "unauthorized",
      message: "Please sign in to use Voice capture.",
    } as VoiceError;
  }

  const formData = new FormData();
  formData.append("audio", audioBlob, "audio.wav");
  formData.append("recording_start_time", recordingStartTime);
  formData.append("timezone", timezone);

  if (focusedTask) {
    formData.append(
      "focused_task",
      JSON.stringify({
        id: focusedTask.id,
        title: focusedTask.title,
        description: focusedTask.description,
        priority: focusedTask.priority,
        plan: focusedTask.plan,
      }),
    );
  }

  if (existingDrafts && existingDrafts.length > 0) {
    const draftsSummary = existingDrafts.map((d, index) => ({
      target_draft_index: index,
      title: d.title,
      description: d.description,
      priority: d.priority,
      plan_date: formatPlanDate(d.plan),
      plan_time: formatPlanTime(d.plan),
      plan_type:
        d.plan && "type" in d.plan && d.plan.type ? d.plan.type : undefined,
    }));
    formData.append("existing_drafts", JSON.stringify(draftsSummary));
  }

  let response: Response;
  try {
    const supabaseUrl =
      (client as unknown as { supabaseUrl?: string }).supabaseUrl || "";
    const edgeFunctionUrl = `${supabaseUrl}/functions/v1/voice-capture`;

    response = await fetch(edgeFunctionUrl, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${session.access_token}`,
      },
      body: formData,
    });
  } catch {
    throw {
      status: 0,
      code: "network_error",
      message: "Network error: unable to reach Voice service. Your recording is preserved.",
      isNetworkError: true,
    } as VoiceError;
  }

  if (!response.ok) {
    let errorData: {
      error?: string;
      code?: string;
      earliest_retry_at?: string;
      retry_after_seconds?: number;
    } = {};
    try {
      errorData = await response.json();
    } catch {
      // ignore
    }

    const status = response.status;
    const message =
      errorData.error ||
      (status === 503
        ? "Voice capture is temporarily unavailable. Please try again later."
        : status === 429
          ? "Voice allowance or rate limit reached."
          : "Voice capture failed.");

    throw {
      status,
      code: errorData.code || "voice_error",
      message,
      earliestRetryAt: errorData.earliest_retry_at,
      retryAfterSeconds: errorData.retry_after_seconds,
    } as VoiceError;
  }

  const result = await response.json();
  const transcript: string = result.transcript || "";
  const mode: "create" | "edit" = result.mode || (focusedTask ? "edit" : "create");

  if (mode === "edit" && focusedTask) {
    const editPayload: ExtractedEditPayload | null = result.edit;
    let editDraft: DraftTask;

    if (editPayload) {
      const newTitle = editPayload.title?.trim() || focusedTask.title;
      const newDescription =
        editPayload.description !== undefined
          ? editPayload.description
          : focusedTask.description;
      const newPriority = (
        editPayload.priority &&
        editPayload.priority >= 1 &&
        editPayload.priority <= 4
          ? editPayload.priority
          : focusedTask.priority
      ) as Priority;

      let newPlan = focusedTask.plan;
      let validationError: string | null = null;

      if (editPayload.clear_plan) {
        newPlan = null;
      } else if (editPayload.plan_date) {
        const date = editPayload.plan_date.trim();
        const time = editPayload.plan_time?.trim() || null;
        // Preserves existing Instant/Floating type if already timed unless speech explicitly changed it
        const existingType =
          focusedTask.plan &&
          "type" in focusedTask.plan &&
          focusedTask.plan.type
            ? focusedTask.plan.type
            : null;
        const chosenType =
          editPayload.plan_type ?? existingType ?? effectiveDefaultTimedPlanType;

        if (time) {
          newPlan = createPlanFromInputs({
            date,
            time,
            type: chosenType,
            effectiveDefault: effectiveDefaultTimedPlanType,
          });
        } else {
          if (editPayload.plan_type) {
            validationError =
              "An explicit Instant or Floating plan requires a clock time.";
            newPlan = { date };
          } else {
            newPlan = { date };
          }
        }
      }

      editDraft = {
        id: crypto.randomUUID(),
        title: newTitle,
        description: newDescription,
        priority: newPriority,
        plan: newPlan,
        labels: focusedTask.labels || [],
        parentId: focusedTask.parentId,
        originalTaskId: focusedTask.id,
        validationError,
      };
    } else {
      editDraft = {
        id: crypto.randomUUID(),
        title: focusedTask.title,
        description: focusedTask.description,
        priority: focusedTask.priority,
        plan: focusedTask.plan,
        labels: focusedTask.labels || [],
        parentId: focusedTask.parentId,
        originalTaskId: focusedTask.id,
        validationError: null,
      };
    }

    return {
      transcript,
      mode: "edit",
      drafts: [editDraft],
      editProposal: editDraft,
    };
  }

  // Create or Correction mode
  const extractedDrafts: ExtractedDraftPayload[] = result.drafts || [];
  let finalDrafts: DraftTask[];

  if (existingDrafts && existingDrafts.length > 0) {
    // Merge Voice correction updates into existing drafts
    finalDrafts = existingDrafts.map((prevDraft, index) => {
      const match = extractedDrafts.find(
        (ed) =>
          ed.target_draft_index === index ||
          ed.title.toLowerCase() === prevDraft.title.toLowerCase(),
      );

      if (match) {
        return createDraftTaskFromExtracted(
          match,
          effectiveDefaultTimedPlanType,
          prevDraft.originalTaskId,
        );
      }
      return prevDraft;
    });

    // Add any completely new drafts
    const newItems = extractedDrafts.filter(
      (ed) =>
        ed.target_draft_index === undefined ||
        ed.target_draft_index >= existingDrafts.length,
    );
    for (const item of newItems) {
      finalDrafts.push(
        createDraftTaskFromExtracted(
          item,
          effectiveDefaultTimedPlanType,
          null,
        ),
      );
    }
  } else {
    finalDrafts = extractedDrafts.map((d) =>
      createDraftTaskFromExtracted(d, effectiveDefaultTimedPlanType, null),
    );
  }

  return {
    transcript,
    mode: "create",
    drafts: finalDrafts,
  };
}

/**
 * Fetches the enabled models from the Voice model catalog.
 */
export async function fetchVoiceModelCatalog(
  client: SupabaseClient,
): Promise<VoiceModelCatalogEntry[]> {
  const { data, error } = await client
    .from("voice_model_catalog")
    .select("key, type, name, is_default, is_enabled")
    .eq("is_enabled", true);

  if (error) {
    throw new Error(`Failed to fetch voice model catalog: ${error.message}`);
  }

  return (data as VoiceModelCatalogEntry[]) || [];
}

/**
 * Updates the Operator's voice configuration settings.
 */
export async function updateOperatorVoiceSettings(
  client: SupabaseClient,
  settings: {
    readonly stt_model_key?: string | null;
    readonly extractor_model_key?: string | null;
    readonly custom_extractor_prompt?: string | null;
  },
): Promise<void> {
  const { error } = await client.from("settings").upsert(settings);

  if (error) {
    throw new Error(
      `Failed to update voice settings: ${error.message} (${error.code})`,
    );
  }
}
