import type { SupabaseClient } from "@supabase/supabase-js";

export interface AudioValidationResult {
  readonly valid: boolean;
  readonly error?: string;
  readonly durationSeconds?: number;
  readonly sizeBytes?: number;
}

export interface VoiceReservationResult {
  readonly allowed: boolean;
  readonly reservationId?: string;
  readonly reason?: string;
  readonly earliestRetryAt?: string;
  readonly retryAfterSeconds?: number;
  readonly message?: string;
}

/**
 * Normalizes the raw snake_case JSONB payload returned by the
 * api.reserve_voice_allowance RPC into a VoiceReservationResult.
 *
 * The Postgres function builds its response with jsonb_build_object using
 * snake_case keys ('reservation_id', 'earliest_retry_at',
 * 'retry_after_seconds'), so the payload must never be trusted as an
 * already-camelCase object. Fields are read loosely from an unknown record:
 * anything missing or of the wrong type is dropped to undefined, and
 * 'allowed' is only true when explicitly boolean true (garbage payloads
 * degrade to a conservative rejection).
 */
export function normalizeVoiceReservation(
  payload: unknown,
): VoiceReservationResult {
  const record =
    typeof payload === "object" && payload !== null
      ? (payload as Record<string, unknown>)
      : {};

  const str = (value: unknown): string | undefined =>
    typeof value === "string" ? value : undefined;

  return {
    allowed: record["allowed"] === true,
    reservationId: str(record["reservation_id"]),
    reason: str(record["reason"]),
    earliestRetryAt: str(record["earliest_retry_at"]),
    retryAfterSeconds:
      typeof record["retry_after_seconds"] === "number"
        ? record["retry_after_seconds"]
        : undefined,
    message: str(record["message"]),
  };
}

export interface ExtractedPlan {
  readonly date?: string | null;
  readonly time?: string | null;
  readonly type?: "instant" | "floating" | null;
}

export interface ExtractedDraft {
  readonly title: string;
  readonly description?: string | null;
  readonly priority?: number | null;
  readonly plan_date?: string | null;
  readonly plan_time?: string | null;
  readonly plan_type?: "instant" | "floating" | null;
  readonly target_draft_index?: number | null;
}

export interface ExtractedEdit {
  readonly title?: string | null;
  readonly description?: string | null;
  readonly priority?: number | null;
  readonly plan_date?: string | null;
  readonly plan_time?: string | null;
  readonly plan_type?: "instant" | "floating" | null;
  readonly clear_plan?: boolean | null;
}

export interface ExtractionResponse {
  readonly mode: "create" | "edit";
  readonly drafts: readonly ExtractedDraft[];
  readonly edit: ExtractedEdit | null;
}

export interface VoicePipelineResult {
  readonly transcript: string;
  readonly mode: "create" | "edit";
  readonly drafts: readonly ExtractedDraft[];
  readonly edit: ExtractedEdit | null;
  readonly usage: {
    readonly audioSeconds: number;
    readonly promptTokens: number;
    readonly completionTokens: number;
    readonly estimatedCost: number;
    readonly actualCost: number;
  };
}

export const MAX_AUDIO_DURATION_SECONDS = 120; // 2 minutes
export const MAX_AUDIO_SIZE_BYTES = 4 * 1024 * 1024; // 4 MB

/**
 * Validates that an ArrayBuffer or Uint8Array is a valid 16 kHz mono 16-bit PCM WAV.
 */
export function validateWavAudio(data: Uint8Array): AudioValidationResult {
  const sizeBytes = data.byteLength;

  if (sizeBytes < 44) {
    return {
      valid: false,
      error: "Audio payload too small to be a valid WAV file (minimum 44 bytes).",
    };
  }

  if (sizeBytes > MAX_AUDIO_SIZE_BYTES) {
    return {
      valid: false,
      error: `Audio payload exceeds maximum limit of 4 MB (got ${sizeBytes} bytes).`,
    };
  }

  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);

  // Check 'RIFF'
  const riff = String.fromCharCode(
    view.getUint8(0),
    view.getUint8(1),
    view.getUint8(2),
    view.getUint8(3),
  );
  if (riff !== "RIFF") {
    return {
      valid: false,
      error: "Invalid WAV format: missing 'RIFF' header identifier.",
    };
  }

  // Check 'WAVE'
  const wave = String.fromCharCode(
    view.getUint8(8),
    view.getUint8(9),
    view.getUint8(10),
    view.getUint8(11),
  );
  if (wave !== "WAVE") {
    return {
      valid: false,
      error: "Invalid WAV format: missing 'WAVE' identifier.",
    };
  }

  // Parse chunks
  let offset = 12;
  let audioFormat: number | null = null;
  let numChannels: number | null = null;
  let sampleRate: number | null = null;
  let bitsPerSample: number | null = null;
  let dataBytes: number | null = null;

  while (offset + 8 <= sizeBytes) {
    const chunkId = String.fromCharCode(
      view.getUint8(offset),
      view.getUint8(offset + 1),
      view.getUint8(offset + 2),
      view.getUint8(offset + 3),
    );
    const chunkSize = view.getUint32(offset + 4, true);

    if (chunkId === "fmt ") {
      if (chunkSize < 16 || offset + 8 + 16 > sizeBytes) {
        return { valid: false, error: "Malformed 'fmt ' chunk in WAV file." };
      }
      audioFormat = view.getUint16(offset + 8, true);
      numChannels = view.getUint16(offset + 10, true);
      sampleRate = view.getUint32(offset + 12, true);
      bitsPerSample = view.getUint16(offset + 22, true);
    } else if (chunkId === "data") {
      dataBytes = Math.min(chunkSize, sizeBytes - (offset + 8));
    }

    offset += 8 + chunkSize;
    // Word alignment padding
    if (chunkSize % 2 !== 0) {
      offset += 1;
    }
  }

  if (audioFormat !== 1) {
    return {
      valid: false,
      error: `Unsupported audio format (${audioFormat}). Audio must be linear PCM (format 1).`,
    };
  }

  if (numChannels !== 1) {
    return {
      valid: false,
      error: `Unsupported channel count (${numChannels}). Audio must be mono (1 channel).`,
    };
  }

  if (sampleRate !== 16000) {
    return {
      valid: false,
      error: `Unsupported sample rate (${sampleRate} Hz). Audio must be 16 kHz (16000 Hz).`,
    };
  }

  if (bitsPerSample !== 16) {
    return {
      valid: false,
      error: `Unsupported bit depth (${bitsPerSample} bit). Audio must be 16-bit PCM.`,
    };
  }

  const effectiveDataBytes = dataBytes ?? (sizeBytes - 44);
  const bytesPerSecond = 16000 * 1 * 2; // 32,000 bytes/sec
  const durationSeconds = effectiveDataBytes / bytesPerSecond;

  if (durationSeconds > MAX_AUDIO_DURATION_SECONDS + 0.5) {
    return {
      valid: false,
      error: `Audio duration exceeds maximum limit of 2 minutes (got ${durationSeconds.toFixed(1)} seconds).`,
      durationSeconds,
      sizeBytes,
    };
  }

  return {
    valid: true,
    durationSeconds: Math.round(durationSeconds * 100) / 100,
    sizeBytes,
  };
}

/**
 * Derives a content-free pseudonymous key for an Operator ID using SHA-256 HMAC / hash.
 */
export async function derivePseudonymousKey(
  operatorId: string,
  salt = "cras-voice-accounting-salt",
): Promise<string> {
  const encoder = new TextEncoder();
  const keyData = encoder.encode(salt);
  const messageData = encoder.encode(operatorId);

  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    keyData,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );

  const signature = await crypto.subtle.sign("HMAC", cryptoKey, messageData);
  const hashArray = Array.from(new Uint8Array(signature));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Calculates estimated spend before provider execution.
 */
export function calculateEstimatedSpend(audioSeconds: number): number {
  // Voxtral Small: $0.003 / min ($0.00005 / sec)
  const sttCost = audioSeconds * 0.00005;
  // Conservative Gemma 4 26B estimate (~1500 prompt tokens, ~600 output tokens)
  const gemmaCost = 1500 * 0.00000007 + 600 * 0.00000034;
  return Math.round((sttCost + gemmaCost + 0.0005) * 10000) / 10000;
}

/**
 * Calculates actual provider spend from measured duration and token counts.
 */
export function calculateActualSpend(
  audioSeconds: number,
  promptTokens: number,
  completionTokens: number,
): number {
  const sttCost = audioSeconds * 0.00005;
  const gemmaCost =
    promptTokens * 0.00000007 + completionTokens * 0.00000034;
  return Math.round((sttCost + gemmaCost) * 10000) / 10000;
}

/**
 * Calls DeepInfra Speech-To-Text (Voxtral Small) with up to 2 retries for transient failures.
 */
export async function callDeepInfraSTT(
  audioData: Uint8Array,
  apiKey: string,
  modelSlug = "mistralai/Voxtral-Small-24B-2507",
  fetchFn: typeof fetch = fetch,
  maxRetries = 2,
): Promise<string> {
  const formData = new FormData();
  const audioBlob = new Blob([audioData], { type: "audio/wav" });
  formData.append("file", audioBlob, "audio.wav");
  formData.append("model", modelSlug);

  let lastError: Error | null = null;

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      const response = await fetchFn(
        "https://api.deepinfra.com/v1/openai/audio/transcriptions",
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${apiKey}`,
          },
          body: formData,
        },
      );

      if (response.ok) {
        const json = await response.json();
        if (typeof json.text !== "string") {
          throw new Error("Invalid transcription response: missing 'text' property.");
        }
        return json.text.trim();
      }

      const status = response.status;
      const errorText = await response.text().catch(() => "");

      // Transient errors eligible for retry
      const isTransient = status === 429 || status >= 500;
      lastError = new Error(
        `DeepInfra STT failed with status ${status}: ${errorText}`,
      );

      if (!isTransient || attempt === maxRetries) {
        throw lastError;
      }

      // Exponential backoff with jitter
      await new Promise((res) =>
        setTimeout(res, Math.min(2000, 200 * Math.pow(2, attempt) + Math.random() * 100)),
      );
    } catch (err) {
      lastError = err instanceof Error ? err : new Error(String(err));
      if (attempt === maxRetries) {
        throw lastError;
      }
      await new Promise((res) =>
        setTimeout(res, Math.min(2000, 200 * Math.pow(2, attempt) + Math.random() * 100)),
      );
    }
  }

  throw lastError || new Error("Failed to transcribe audio after retries.");
}

export const EXTRACTION_SCHEMA = {
  name: "task_extraction",
  strict: true,
  schema: {
    type: "object",
    properties: {
      mode: {
        type: "string",
        enum: ["create", "edit"],
      },
      drafts: {
        type: "array",
        items: {
          type: "object",
          properties: {
            title: { type: "string" },
            description: { type: ["string", "null"] },
            priority: { type: ["integer", "null"] },
            plan_date: { type: ["string", "null"] },
            plan_time: { type: ["string", "null"] },
            plan_type: {
              type: ["string", "null"],
              enum: ["instant", "floating", null],
            },
            target_draft_index: { type: ["integer", "null"] },
          },
          required: [
            "title",
            "description",
            "priority",
            "plan_date",
            "plan_time",
            "plan_type",
          ],
          additionalProperties: false,
        },
      },
      edit: {
        type: ["object", "null"],
        properties: {
          title: { type: ["string", "null"] },
          description: { type: ["string", "null"] },
          priority: { type: ["integer", "null"] },
          plan_date: { type: ["string", "null"] },
          plan_time: { type: ["string", "null"] },
          plan_type: {
            type: ["string", "null"],
            enum: ["instant", "floating", null],
          },
          clear_plan: { type: ["boolean", "null"] },
        },
        required: [
          "title",
          "description",
          "priority",
          "plan_date",
          "plan_time",
          "plan_type",
          "clear_plan",
        ],
        additionalProperties: false,
      },
    },
    required: ["mode", "drafts", "edit"],
    additionalProperties: false,
  },
};

export const DEFAULT_EXTRACTOR_PROMPT = `You are Cras Task Extractor.
Extract personal task management drafts or task edits from speech.
Adhere strictly to Cras domain rules:
- Current context: Reference recording time is {RECORDING_START_TIME} in timezone {TIMEZONE}.
- Ground relative dates (e.g. "today", "tomorrow", "next Monday", "in 3 days") into exact ISO calendar date strings 'YYYY-MM-DD' using the reference time and timezone.
- For time of day, format as 'HH:MM:SS' in 24-hour time.
- Plan type rule: ONLY set 'plan_type' to 'instant' or 'floating' if the speech explicitly said the words "instant" or "floating" (or "zoned" or "same time everywhere"). Contextual words (e.g., "call Mary at 6", "breakfast at 8") MUST have plan_type: null so Cras applies the default.
- If speech specifies an explicit Instant or Floating plan without specifying a clock time, set plan_type: "instant" or "floating" and plan_time: null (Cras client will flag this validation error).
- Priority: P1 / Urgent = 1, P2 / High = 2, P3 / Medium = 3, P4 / Low / None = 4. Default is 4 (null if unstated).
- Description: Use 'description', never 'notes'. null if unstated.
- If focused task context is present, emit mode: "edit", populate 'edit' with updated fields (null for unchanged), and drafts: [].
- If existing drafts are present (Voice correction), update matching draft(s) by target_draft_index or title in 'drafts', keeping other drafts intact.
- Otherwise emit mode: "create", with an array of 1 or more task drafts in 'drafts' and edit: null.`;

/**
 * Calls DeepInfra Chat Completions (Gemma 4 26B-A4B-it) with structured output and retries.
 */
export async function callDeepInfraExtractor(
  transcript: string,
  apiKey: string,
  options: {
    readonly recordingStartTime: string;
    readonly timezone: string;
    readonly focusedTask?: {
      readonly id: string;
      readonly title: string;
      readonly description?: string | null;
      readonly priority?: number;
      readonly plan?: {
        readonly type?: "instant" | "floating";
        readonly at?: string;
        readonly date?: string;
        readonly time?: string;
      } | null;
    } | null;
    readonly existingDrafts?: readonly ExtractedDraft[] | null;
    readonly customPrompt?: string | null;
    readonly modelSlug?: string;
  },
  fetchFn: typeof fetch = fetch,
  maxRetries = 2,
): Promise<{
  readonly extraction: ExtractionResponse;
  readonly promptTokens: number;
  readonly completionTokens: number;
}> {
  const modelSlug = options.modelSlug || "google/gemma-4-26B-A4B-it";
  let systemPrompt = options.customPrompt || DEFAULT_EXTRACTOR_PROMPT;
  systemPrompt = systemPrompt
    .replace("{RECORDING_START_TIME}", options.recordingStartTime)
    .replace("{TIMEZONE}", options.timezone);

  let userContext = `Transcript to extract: "${transcript}"\n`;
  if (options.focusedTask) {
    userContext += `Focused Task context (this is an EDIT to existing task id ${options.focusedTask.id}):\n${JSON.stringify(options.focusedTask, null, 2)}\n`;
  }
  if (options.existingDrafts && options.existingDrafts.length > 0) {
    userContext += `Active Drafts on screen (this is a Voice Correction to these drafts):\n${JSON.stringify(options.existingDrafts, null, 2)}\n`;
  }

  const payload = {
    model: modelSlug,
    messages: [
      { role: "system", content: systemPrompt },
      { role: "user", content: userContext },
    ],
    response_format: {
      type: "json_schema",
      json_schema: EXTRACTION_SCHEMA,
    },
    temperature: 0.1,
  };

  let lastError: Error | null = null;

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      const response = await fetchFn(
        "https://api.deepinfra.com/v1/openai/chat/completions",
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${apiKey}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(payload),
        },
      );

      if (response.ok) {
        const json = await response.json();
        const choice = json.choices?.[0];
        const content = choice?.message?.content;
        if (!content) {
          throw new Error("Extractor returned empty completion content.");
        }

        const parsed = JSON.parse(content) as ExtractionResponse;
        const promptTokens = json.usage?.prompt_tokens ?? 500;
        const completionTokens = json.usage?.completion_tokens ?? 150;

        return {
          extraction: parsed,
          promptTokens,
          completionTokens,
        };
      }

      const status = response.status;
      const errorText = await response.text().catch(() => "");
      const isTransient = status === 429 || status >= 500;
      lastError = new Error(
        `DeepInfra Extractor failed with status ${status}: ${errorText}`,
      );

      if (!isTransient || attempt === maxRetries) {
        throw lastError;
      }

      await new Promise((res) =>
        setTimeout(res, Math.min(2000, 200 * Math.pow(2, attempt) + Math.random() * 100)),
      );
    } catch (err) {
      lastError = err instanceof Error ? err : new Error(String(err));
      if (attempt === maxRetries) {
        throw lastError;
      }
      await new Promise((res) =>
        setTimeout(res, Math.min(2000, 200 * Math.pow(2, attempt) + Math.random() * 100)),
      );
    }
  }

  throw lastError || new Error("Failed to extract task metadata after retries.");
}

/**
 * Orchestrates the full voice pipeline:
 * 1. Validates audio.
 * 2. Derives pseudonymous key.
 * 3. Reserves voice allowance atomically via Supabase RPC.
 * 4. Calls STT (Voxtral Small) with retries.
 * 5. Calls Extractor (Gemma 4 26B-A4B-it) with retries.
 * 6. Reconciles usage via Supabase RPC.
 */
export async function processVoiceCapture(
  supabaseAdmin: SupabaseClient,
  operatorId: string,
  audioData: Uint8Array,
  params: {
    readonly recordingStartTime: string;
    readonly timezone: string;
    readonly focusedTask?: any;
    readonly existingDrafts?: readonly ExtractedDraft[];
    readonly deepInfraApiKey: string;
    readonly customPrompt?: string | null;
    readonly sttModelKey?: string | null;
    readonly extractorModelKey?: string | null;
  },
  fetchFn: typeof fetch = fetch,
): Promise<{
  readonly success: boolean;
  readonly result?: VoicePipelineResult;
  readonly error?: {
    readonly status: number;
    readonly code: string;
    readonly message: string;
    readonly earliestRetryAt?: string;
    readonly retryAfterSeconds?: number;
  };
}> {
  // Step 1: Validate WAV audio before accounting
  const validation = validateWavAudio(audioData);
  if (!validation.valid || !validation.durationSeconds) {
    return {
      success: false,
      error: {
        status: 400,
        code: "invalid_audio",
        message: validation.error || "Invalid audio file format.",
      },
    };
  }

  const audioSeconds = validation.durationSeconds;
  const estimatedCost = calculateEstimatedSpend(audioSeconds);
  const pseudonymousKey = await derivePseudonymousKey(operatorId);

  // Step 2: Atomically reserve allowance
  const { data: reserveData, error: reserveError } = await supabaseAdmin
    .schema("api")
    .rpc("reserve_voice_allowance", {
      p_pseudonymous_key: pseudonymousKey,
      p_audio_seconds: audioSeconds,
      p_estimated_cost: estimatedCost,
    });

  if (reserveError) {
    return {
      success: false,
      error: {
        status: 500,
        code: "accounting_unavailable",
        message: "Voice capture is temporarily unavailable. Please try again later.",
      },
    };
  }

  const reservation = normalizeVoiceReservation(reserveData);
  if (!reservation.allowed) {
    if (
      reservation.reason === "voice_disabled" ||
      reservation.reason?.startsWith("circuit_breaker")
    ) {
      return {
        success: false,
        error: {
          status: 503,
          code: reservation.reason,
          message: "Voice capture is temporarily unavailable. Please try again later.",
        },
      };
    }

    if (reservation.reason === "concurrent_limit") {
      return {
        success: false,
        error: {
          status: 429,
          code: "concurrent_limit",
          message: "Another Voice capture is currently in progress.",
        },
      };
    }

    // Rate limit / daily / monthly limits exceeded
    return {
      success: false,
      error: {
        status: 429,
        code: reservation.reason || "allowance_exceeded",
        message: reservation.message || "Voice allowance exceeded.",
        earliestRetryAt: reservation.earliestRetryAt,
        retryAfterSeconds: reservation.retryAfterSeconds,
      },
    };
  }

  const reservationId = reservation.reservationId;

  // Step 3: Execute provider calls
  let transcript = "";
  let promptTokens = 0;
  let completionTokens = 0;
  let extraction: ExtractionResponse | null = null;
  let providerFailed = false;
  let providerErrorMessage = "";

  try {
    // STT call
    transcript = await callDeepInfraSTT(
      audioData,
      params.deepInfraApiKey,
      "mistralai/Voxtral-Small-24B-2507",
      fetchFn,
    );

    // Extraction call
    const extractRes = await callDeepInfraExtractor(
      transcript,
      params.deepInfraApiKey,
      {
        recordingStartTime: params.recordingStartTime,
        timezone: params.timezone,
        focusedTask: params.focusedTask,
        existingDrafts: params.existingDrafts,
        customPrompt: params.customPrompt,
      },
      fetchFn,
    );

    extraction = extractRes.extraction;
    promptTokens = extractRes.promptTokens;
    completionTokens = extractRes.completionTokens;
  } catch (providerErr) {
    providerFailed = true;
    providerErrorMessage =
      providerErr instanceof Error ? providerErr.message : String(providerErr);
  }

  // Step 4: Reconcile usage
  const actualCost = calculateActualSpend(
    audioSeconds,
    promptTokens,
    completionTokens,
  );

  if (reservationId) {
    await supabaseAdmin.schema("api").rpc("reconcile_voice_usage", {
      p_reservation_id: reservationId,
      p_status: providerFailed ? "failed" : "completed",
      p_actual_audio_seconds: audioSeconds,
      p_model_key: "voxtral-small+gemma-4-26b-a4b-it",
      p_prompt_tokens: promptTokens,
      p_completion_tokens: completionTokens,
      p_actual_cost: providerFailed ? estimatedCost : actualCost,
    });
  }

  if (providerFailed || !extraction) {
    return {
      success: false,
      error: {
        status: 502,
        code: "provider_error",
        message: "Voice processing failed. Please try again.",
      },
    };
  }

  return {
    success: true,
    result: {
      transcript,
      mode: extraction.mode,
      drafts: extraction.drafts,
      edit: extraction.edit,
      usage: {
        audioSeconds,
        promptTokens,
        completionTokens,
        estimatedCost,
        actualCost,
      },
    },
  };
}
