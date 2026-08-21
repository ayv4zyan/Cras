import { createClient } from "@supabase/supabase-js";
import {
  processVoiceCapture,
  validateWavAudio,
  derivePseudonymousKey,
  calculateEstimatedSpend,
  calculateActualSpend,
  callDeepInfraSTT,
  callDeepInfraExtractor,
  type AudioValidationResult,
  type ExtractedDraft,
  type ExtractedEdit,
  type VoicePipelineResult,
} from "../_shared/voicePipeline.ts";

export {
  processVoiceCapture,
  validateWavAudio,
  derivePseudonymousKey,
  calculateEstimatedSpend,
  calculateActualSpend,
  callDeepInfraSTT,
  callDeepInfraExtractor,
  type AudioValidationResult,
  type ExtractedDraft,
  type ExtractedEdit,
  type VoicePipelineResult,
};

declare const Deno: any;

export async function handleVoiceCaptureRequest(
  req: Request,
  env: {
    readonly SUPABASE_URL: string;
    readonly SUPABASE_ANON_KEY: string;
    readonly SUPABASE_SERVICE_ROLE_KEY: string;
    readonly DEEPINFRA_TOKEN: string;
  },
  fetchFn: typeof fetch = fetch,
  supabaseClientOverride?: any,
  supabaseAdminOverride?: any,
): Promise<Response> {
  // CORS preflight handling
  if (req.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "POST, OPTIONS",
        "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
      },
    });
  }

  if (req.method !== "POST") {
    return new Response(
      JSON.stringify({ error: "Method not allowed. Use POST." }),
      {
        status: 405,
        headers: { "Content-Type": "application/json" },
      },
    );
  }

  const authHeader = req.headers.get("Authorization");
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return new Response(
      JSON.stringify({
        error: "Unauthorized: Missing or invalid Authorization header.",
      }),
      {
        status: 401,
        headers: { "Content-Type": "application/json" },
      },
    );
  }

  const token = authHeader.replace("Bearer ", "").trim();
  const supabase =
    supabaseClientOverride ||
    createClient(env.SUPABASE_URL, env.SUPABASE_ANON_KEY, {
      global: { headers: { Authorization: `Bearer ${token}` } },
    });

  const {
    data: { user },
    error: authError,
  } = await supabase.auth.getUser(token);

  if (authError || !user) {
    return new Response(
      JSON.stringify({
        error: "Unauthorized: Invalid or expired session token.",
      }),
      {
        status: 401,
        headers: { "Content-Type": "application/json" },
      },
    );
  }

  const operatorId = user.id;

  // Supabase service-role client for trusted database operations (allowance, catalog)
  const supabaseAdmin =
    supabaseAdminOverride ||
    createClient(env.SUPABASE_URL, env.SUPABASE_SERVICE_ROLE_KEY);

  // Parse request body
  let audioBytes: Uint8Array;
  let recordingStartTime = new Date().toISOString();
  let timezone = "UTC";
  let focusedTask: any = null;
  let existingDrafts: any[] | null = null;

  try {
    const contentType = req.headers.get("content-type") || "";

    if (contentType.includes("multipart/form-data")) {
      const formData = await req.formData();
      const file = formData.get("audio") || formData.get("file");
      if (!file || !(file instanceof Blob)) {
        return new Response(
          JSON.stringify({
            error: "Missing required 'audio' or 'file' WAV upload.",
          }),
          { status: 400, headers: { "Content-Type": "application/json" } },
        );
      }
      audioBytes = new Uint8Array(await file.arrayBuffer());

      if (formData.has("recording_start_time")) {
        recordingStartTime = String(formData.get("recording_start_time"));
      }
      if (formData.has("timezone")) {
        timezone = String(formData.get("timezone"));
      }
      if (formData.has("focused_task")) {
        try {
          focusedTask = JSON.parse(String(formData.get("focused_task")));
        } catch {
          // ignore
        }
      }
      if (formData.has("existing_drafts")) {
        try {
          existingDrafts = JSON.parse(String(formData.get("existing_drafts")));
        } catch {
          // ignore
        }
      }
    } else {
      // JSON body with base64 audio
      const json = await req.json();
      if (!json.audio) {
        return new Response(
          JSON.stringify({
            error: "Missing required 'audio' base64 or multipart upload.",
          }),
          { status: 400, headers: { "Content-Type": "application/json" } },
        );
      }

      if (typeof json.audio === "string") {
        const binaryString = atob(json.audio);
        audioBytes = new Uint8Array(binaryString.length);
        for (let i = 0; i < binaryString.length; i++) {
          audioBytes[i] = binaryString.charCodeAt(i);
        }
      } else {
        return new Response(
          JSON.stringify({ error: "Invalid audio payload format." }),
          { status: 400, headers: { "Content-Type": "application/json" } },
        );
      }

      if (json.recording_start_time) {
        recordingStartTime = String(json.recording_start_time);
      }
      if (json.timezone) {
        timezone = String(json.timezone);
      }
      if (json.focused_task) {
        focusedTask = json.focused_task;
      }
      if (json.existing_drafts) {
        existingDrafts = json.existing_drafts;
      }
    }
  } catch (err) {
    return new Response(
      JSON.stringify({
        error: `Failed to parse request payload: ${err instanceof Error ? err.message : String(err)}`,
      }),
      { status: 400, headers: { "Content-Type": "application/json" } },
    );
  }

  // Load operator settings for voice prompt / models
  let customPrompt: string | null = null;
  try {
    const { data: settings } = await supabaseAdmin
      .from("settings")
      .select("stt_model_key, extractor_model_key, custom_extractor_prompt")
      .eq("operator_id", operatorId)
      .maybeSingle();

    if (settings?.custom_extractor_prompt) {
      customPrompt = settings.custom_extractor_prompt;
    }
  } catch {
    // Ignore settings read failure and use default
  }

  const result = await processVoiceCapture(
    supabaseAdmin,
    operatorId,
    audioBytes,
    {
      recordingStartTime,
      timezone,
      focusedTask,
      existingDrafts,
      deepInfraApiKey: env.DEEPINFRA_TOKEN,
      customPrompt,
    },
    fetchFn,
  );

  if (!result.success || !result.result) {
    const errorObj = result.error || {
      status: 500,
      code: "internal_error",
      message: "An unknown error occurred.",
    };
    return new Response(
      JSON.stringify({
        error: errorObj.message,
        code: errorObj.code,
        earliest_retry_at: errorObj.earliestRetryAt,
        retry_after_seconds: errorObj.retryAfterSeconds,
      }),
      {
        status: errorObj.status,
        headers: {
          "Content-Type": "application/json",
          "Access-Control-Allow-Origin": "*",
        },
      },
    );
  }

  return new Response(
    JSON.stringify({
      transcript: result.result.transcript,
      mode: result.result.mode,
      drafts: result.result.drafts,
      edit: result.result.edit,
    }),
    {
      status: 200,
      headers: {
        "Content-Type": "application/json",
        "Access-Control-Allow-Origin": "*",
      },
    },
  );
}

if (typeof Deno !== "undefined" && typeof Deno.serve === "function") {
  Deno.serve(async (req: Request) => {
    const env = {
      SUPABASE_URL: Deno.env.get("SUPABASE_URL") ?? "",
      SUPABASE_ANON_KEY: Deno.env.get("SUPABASE_ANON_KEY") ?? "",
      SUPABASE_SERVICE_ROLE_KEY: Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
      DEEPINFRA_TOKEN: Deno.env.get("DEEPINFRA_TOKEN") ?? "",
    };
    return await handleVoiceCaptureRequest(req, env, fetch);
  });
}
