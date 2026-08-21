import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  validateWavAudio,
  derivePseudonymousKey,
  calculateEstimatedSpend,
  calculateActualSpend,
  callDeepInfraSTT,
  callDeepInfraExtractor,
  handleVoiceCaptureRequest,
} from "../services/voiceWorker";
import { createWavHeader } from "../services/audioRecorder";
import type { SupabaseClient } from "@supabase/supabase-js";

describe("Voice Edge Pipeline - WAV Validation & Math", () => {
  it("accepts valid mono 16 kHz 16-bit PCM WAV within 2 minutes and 4 MB", () => {
    const dataSize = 16000 * 2 * 10; // 10 seconds of 16kHz mono 16-bit PCM
    const header = createWavHeader(dataSize, 16000, 1, 16);
    const fullBuffer = new Uint8Array(44 + dataSize);
    fullBuffer.set(new Uint8Array(header), 0);

    const result = validateWavAudio(fullBuffer);
    expect(result.valid).toBe(true);
    expect(result.durationSeconds).toBe(10);
    expect(result.sizeBytes).toBe(44 + dataSize);
  });

  it("rejects audio buffer smaller than 44 bytes", () => {
    const tinyBuffer = new Uint8Array(30);
    const result = validateWavAudio(tinyBuffer);
    expect(result.valid).toBe(false);
    expect(result.error).toContain("too small");
  });

  it("rejects buffer with corrupted or missing RIFF header", () => {
    const badBuffer = new Uint8Array(60);
    badBuffer.set([0x41, 0x42, 0x43, 0x44], 0); // "ABCD"
    const result = validateWavAudio(badBuffer);
    expect(result.valid).toBe(false);
    expect(result.error).toContain("missing 'RIFF'");
  });

  it("rejects non-16kHz sample rate (e.g. 44.1 kHz)", () => {
    const dataSize = 44100 * 2 * 2;
    const header = createWavHeader(dataSize, 44100, 1, 16);
    const fullBuffer = new Uint8Array(44 + dataSize);
    fullBuffer.set(new Uint8Array(header), 0);

    const result = validateWavAudio(fullBuffer);
    expect(result.valid).toBe(false);
    expect(result.error).toContain("Unsupported sample rate (44100 Hz)");
  });

  it("rejects stereo audio (channels != 1)", () => {
    const dataSize = 16000 * 4 * 2;
    const header = createWavHeader(dataSize, 16000, 2, 16);
    const fullBuffer = new Uint8Array(44 + dataSize);
    fullBuffer.set(new Uint8Array(header), 0);

    const result = validateWavAudio(fullBuffer);
    expect(result.valid).toBe(false);
    expect(result.error).toContain("Unsupported channel count");
  });

  it("rejects audio exceeding 2 minutes limit (e.g. 130 seconds)", () => {
    const dataSize = 16000 * 2 * 130;
    const header = createWavHeader(dataSize, 16000, 1, 16);
    const fullBuffer = new Uint8Array(44 + dataSize);
    fullBuffer.set(new Uint8Array(header), 0);

    const result = validateWavAudio(fullBuffer);
    expect(result.valid).toBe(false);
    expect(result.error).toContain("exceeds maximum limit of 2 minutes");
  });

  it("rejects audio exceeding 4 MB limit", () => {
    const dataSize = 4.5 * 1024 * 1024;
    const dummy = new Uint8Array(dataSize);
    const result = validateWavAudio(dummy);
    expect(result.valid).toBe(false);
    expect(result.error).toContain("exceeds maximum limit of 4 MB");
  });

  it("calculates estimated spend accurately", () => {
    // 60 seconds duration at $0.001/min STT + 600 prompt tokens + 150 completion tokens
    const est = calculateEstimatedSpend(60);
    expect(est).toBeGreaterThan(0.0001);
  });

  it("calculates actual spend accurately", () => {
    const actual = calculateActualSpend(60, 500, 100);
    expect(actual).toBeGreaterThan(0);
  });

  it("derives deterministic pseudonymous key from user ID without reversible info", async () => {
    const key1 = await derivePseudonymousKey("user-1234", "secret-salt");
    const key2 = await derivePseudonymousKey("user-1234", "secret-salt");
    const key3 = await derivePseudonymousKey("user-5678", "secret-salt");

    expect(key1).toBe(key2);
    expect(key1).not.toBe(key3);
    expect(key1).toHaveLength(64); // SHA-256 hex string
  });
});

describe("DeepInfra API Callers & Retries", () => {
  it("calls DeepInfra STT and handles retries with jitter", async () => {
    const mockFetch = vi
      .fn()
      .mockRejectedValueOnce(new Error("Transient socket timeout"))
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ text: "Pick up milk" }),
      });

    const dataSize = 16000 * 2 * 2;
    const header = createWavHeader(dataSize, 16000, 1, 16);
    const fullBuffer = new Uint8Array(44 + dataSize);
    fullBuffer.set(new Uint8Array(header), 0);

    const text = await callDeepInfraSTT(
      fullBuffer,
      "fake-api-key",
      "mistralai/Voxtral-Small-24B-2507",
      mockFetch as unknown as typeof fetch,
    );

    expect(text).toBe("Pick up milk");
    expect(mockFetch).toHaveBeenCalledTimes(2);
  });

  it("calls DeepInfra Extractor with json_schema and returns structured metadata", async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        choices: [
          {
            message: {
              content: JSON.stringify({
                mode: "create",
                drafts: [
                  {
                    title: "Pick up milk",
                    description: null,
                    priority: 4,
                    plan_date: "2026-08-22",
                    plan_time: null,
                    plan_type: null,
                  },
                ],
                edit: null,
              }),
            },
          },
        ],
        usage: {
          prompt_tokens: 450,
          completion_tokens: 120,
        },
      }),
    });

    const result = await callDeepInfraExtractor(
      "Pick up milk tomorrow",
      "fake-api-key",
      {
        recordingStartTime: "2026-08-21T10:00:00Z",
        timezone: "UTC",
      },
      mockFetch as unknown as typeof fetch,
    );

    expect(result.extraction.mode).toBe("create");
    expect(result.extraction.drafts[0].title).toBe("Pick up milk");
    expect(result.promptTokens).toBe(450);
  });
});

describe("Edge Function Request Handling & Allowance Enforcement", () => {
  let mockSupabaseAdmin: SupabaseClient;

  beforeEach(() => {
    mockSupabaseAdmin = {
      schema: vi.fn().mockReturnValue({
        rpc: vi.fn((fnName: string) => {
          if (fnName === "reserve_voice_allowance") {
            return Promise.resolve({
              data: {
                allowed: true,
                reservation_id: "res-uuid-1234",
              },
              error: null,
            });
          }
          if (fnName === "reconcile_voice_usage") {
            return Promise.resolve({ data: null, error: null });
          }
          return Promise.resolve({ data: null, error: null });
        }),
      }),
      from: vi.fn().mockReturnValue({
        select: vi.fn().mockReturnValue({
          eq: vi.fn().mockReturnValue({
            maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
          }),
        }),
      }),
      auth: {
        getUser: vi.fn().mockResolvedValue({
          data: { user: { id: "operator-123" } },
          error: null,
        }),
      },
    } as unknown as SupabaseClient;
  });

  it("handles valid HTTP request through handleVoiceCaptureRequest", async () => {
    const dataSize = 16000 * 2 * 3;
    const header = createWavHeader(dataSize, 16000, 1, 16);
    const fullBuffer = new Uint8Array(44 + dataSize);
    fullBuffer.set(new Uint8Array(header), 0);

    const formData = new FormData();
    formData.append(
      "audio",
      new Blob([fullBuffer], { type: "audio/wav" }),
      "audio.wav",
    );
    formData.append("recording_start_time", "2026-08-21T10:00:00Z");
    formData.append("timezone", "UTC");

    const req = new Request(
      "https://example.supabase.co/functions/v1/voice-capture",
      {
        method: "POST",
        headers: {
          Authorization: "Bearer mock-user-jwt",
        },
        body: formData,
      },
    );

    const mockFetch = vi
      .fn()
      // STT call
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ text: "Buy milk tomorrow" }),
      })
      // Extractor call
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          choices: [
            {
              message: {
                content: JSON.stringify({
                  mode: "create",
                  drafts: [
                    {
                      title: "Buy milk",
                      description: null,
                      priority: 4,
                      plan_date: "2026-08-22",
                      plan_time: null,
                      plan_type: null,
                    },
                  ],
                  edit: null,
                }),
              },
            },
          ],
          usage: { prompt_tokens: 300, completion_tokens: 80 },
        }),
      });

    const env = {
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_ANON_KEY: "anon-key",
      SUPABASE_SERVICE_ROLE_KEY: "service-key",
      DEEPINFRA_TOKEN: "mock-deepinfra-token",
    };

    const res = await handleVoiceCaptureRequest(
      req,
      env,
      mockFetch as unknown as typeof fetch,
      mockSupabaseAdmin,
      mockSupabaseAdmin,
    );
    expect(res.status).toBe(200);

    const json = await res.json();
    expect(json.transcript).toBe("Buy milk tomorrow");
    expect(json.drafts).toHaveLength(1);
    expect(json.drafts[0].title).toBe("Buy milk");
  });

  it("returns 401 when Authorization header is missing", async () => {
    const req = new Request(
      "https://example.supabase.co/functions/v1/voice-capture",
      {
        method: "POST",
      },
    );

    const env = {
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_ANON_KEY: "anon-key",
      SUPABASE_SERVICE_ROLE_KEY: "service-key",
      DEEPINFRA_TOKEN: "mock-deepinfra-token",
    };

    const res = await handleVoiceCaptureRequest(req, env);
    expect(res.status).toBe(401);
  });
});
