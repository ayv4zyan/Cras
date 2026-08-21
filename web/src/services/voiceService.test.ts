import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  createDraftTaskFromExtracted,
  switchDraftTimedPlanType,
  sendVoiceCapture,
  type ExtractedDraftPayload,
  type DraftTask,
} from "./voiceService";
import type { Task } from "../contracts/task";
import type { SupabaseClient } from "@supabase/supabase-js";
import { formatPlanDate, formatPlanTime } from "./temporalService";

describe("Voice Service - Draft Task Creation & Plan Semantics", () => {
  it("creates a Date-only Draft when only plan_date is present (no type)", () => {
    const payload: ExtractedDraftPayload = {
      title: "Buy groceries",
      description: "Apples, bananas, milk",
      priority: 2,
      plan_date: "2026-08-25",
      plan_time: null,
      plan_type: null,
    };

    const draft = createDraftTaskFromExtracted(payload, "instant");

    expect(draft.title).toBe("Buy groceries");
    expect(draft.description).toBe("Apples, bananas, milk");
    expect(draft.priority).toBe(2);
    expect(draft.plan).toEqual({ date: "2026-08-25" });
    expect(draft.validationError).toBeNull();
  });

  it("applies effective default (Instant) when plan has date and time but unstated type", () => {
    const payload: ExtractedDraftPayload = {
      title: "Team standup",
      plan_date: "2026-08-25",
      plan_time: "09:30:00",
      plan_type: null,
    };

    const draft = createDraftTaskFromExtracted(payload, "instant");

    expect(draft.plan).not.toBeNull();
    expect(draft.plan?.type).toBe("instant");
    if (draft.plan && "at" in draft.plan) {
      expect(draft.plan.at).toBeDefined();
    }
    expect(draft.validationError).toBeNull();
  });

  it("applies effective default (Floating) when plan has date and time and default is floating", () => {
    const payload: ExtractedDraftPayload = {
      title: "Team standup",
      plan_date: "2026-08-25",
      plan_time: "09:30:00",
      plan_type: null,
    };

    const draft = createDraftTaskFromExtracted(payload, "floating");

    expect(draft.plan).toEqual({
      type: "floating",
      date: "2026-08-25",
      time: "09:30",
    });
    expect(draft.validationError).toBeNull();
  });

  it("honors explicit spoken Instant type overriding Floating default", () => {
    const payload: ExtractedDraftPayload = {
      title: "Flight departure",
      plan_date: "2026-08-25",
      plan_time: "14:00:00",
      plan_type: "instant",
    };

    const draft = createDraftTaskFromExtracted(payload, "floating");

    expect(draft.plan?.type).toBe("instant");
    if (draft.plan && "at" in draft.plan) {
      expect(draft.plan.at).toBeDefined();
    }
  });

  it("flags validation error when speech explicitly requests Instant/Floating without a clock time", () => {
    const payload: ExtractedDraftPayload = {
      title: "Invalid explicit floating task",
      plan_date: "2026-08-25",
      plan_time: null,
      plan_type: "floating",
    };

    const draft = createDraftTaskFromExtracted(payload, "instant");

    expect(draft.validationError).toBe(
      "An explicit Instant or Floating plan requires a clock time. Please provide a time or change to Date-only.",
    );
    expect(draft.plan).toEqual({ date: "2026-08-25" });
  });

  it("switching Instant <-> Floating preserves displayed calendar date and clock time", () => {
    const initialPayload: ExtractedDraftPayload = {
      title: "Preserve displayed time test",
      plan_date: "2026-08-25",
      plan_time: "19:00:00",
      plan_type: "instant",
    };

    const draftInstant = createDraftTaskFromExtracted(
      initialPayload,
      "instant",
    );
    expect(draftInstant.plan?.type).toBe("instant");
    const displayedDateBefore = formatPlanDate(draftInstant.plan);
    const displayedTimeBefore = formatPlanTime(draftInstant.plan);

    const draftFloating = switchDraftTimedPlanType(
      draftInstant,
      "floating",
      "instant",
    );
    expect(draftFloating.plan?.type).toBe("floating");
    expect(formatPlanDate(draftFloating.plan)).toBe(displayedDateBefore);
    expect(formatPlanTime(draftFloating.plan)).toBe(displayedTimeBefore);

    const draftInstantAgain = switchDraftTimedPlanType(
      draftFloating,
      "instant",
      "instant",
    );
    expect(draftInstantAgain.plan?.type).toBe("instant");
    expect(formatPlanDate(draftInstantAgain.plan)).toBe(displayedDateBefore);
    expect(formatPlanTime(draftInstantAgain.plan)).toBe(displayedTimeBefore);
  });
});

describe("Voice Service - API & Error Handling", () => {
  let mockSupabaseClient: SupabaseClient;
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockSupabaseClient = {
      supabaseUrl: "https://example.supabase.co",
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: {
            session: { access_token: "mock-valid-token" },
          },
        }),
      },
      from: vi.fn(),
    } as unknown as SupabaseClient;

    mockFetch = vi.fn();
    (globalThis as unknown as { fetch: typeof fetch }).fetch =
      mockFetch as unknown as typeof fetch;
  });

  it("handles successful multi-draft voice create response", async () => {
    mockFetch.mockResolvedValue({
      ok: true,
      json: async () => ({
        transcript: "Buy milk tomorrow and call doctor at 3pm",
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
          {
            title: "Call doctor",
            description: null,
            priority: 2,
            plan_date: "2026-08-21",
            plan_time: "15:00:00",
            plan_type: null,
          },
        ],
      }),
    });

    const result = await sendVoiceCapture(mockSupabaseClient, {
      audioBlob: new Blob(["mock-wav"], { type: "audio/wav" }),
      recordingStartTime: "2026-08-21T10:00:00Z",
      timezone: "UTC",
      effectiveDefaultTimedPlanType: "instant",
    });

    expect(result.transcript).toBe(
      "Buy milk tomorrow and call doctor at 3pm",
    );
    expect(result.drafts).toHaveLength(2);
    expect(result.drafts[0].title).toBe("Buy milk");
    expect(result.drafts[0].plan).toEqual({ date: "2026-08-22" });
    expect(result.drafts[1].title).toBe("Call doctor");
    expect(result.drafts[1].priority).toBe(2);
    expect(result.drafts[1].plan?.type).toBe("instant");
  });

  it("handles voice edit when focused task is provided, preserving existing timed type unless changed", async () => {
    const existingTask: Task = {
      id: "task-1234",
      title: "Existing Task",
      description: "Original description",
      priority: 3,
      plan: {
        type: "floating",
        date: "2026-08-20",
        time: "10:00:00",
      },
      labels: [],
      parentId: null,
      completedAt: null,
      createdAt: "2026-08-20T00:00:00Z",
      updatedAt: "2026-08-20T00:00:00Z",
      version: 1,
    };

    mockFetch.mockResolvedValue({
      ok: true,
      json: async () => ({
        transcript: "Move to tomorrow at 11am",
        mode: "edit",
        edit: {
          title: null,
          description: null,
          priority: null,
          plan_date: "2026-08-22",
          plan_time: "11:00:00",
          plan_type: null, // Did not explicitly change type -> preserve floating!
          clear_plan: null,
        },
      }),
    });

    const result = await sendVoiceCapture(mockSupabaseClient, {
      audioBlob: new Blob(["mock-wav"], { type: "audio/wav" }),
      recordingStartTime: "2026-08-21T10:00:00Z",
      timezone: "UTC",
      focusedTask: existingTask,
      effectiveDefaultTimedPlanType: "instant",
    });

    expect(result.mode).toBe("edit");
    expect(result.drafts).toHaveLength(1);
    const editDraft = result.drafts[0];
    expect(editDraft.originalTaskId).toBe("task-1234");
    expect(editDraft.title).toBe("Existing Task");
    expect(editDraft.plan?.type).toBe("floating"); // Preserved floating!
    expect(
      editDraft.plan && "time" in editDraft.plan ? editDraft.plan.time : null,
    ).toBe("11:00");
  });

  it("handles voice correction across existing drafts", async () => {
    const existingDrafts: DraftTask[] = [
      {
        id: "draft-1",
        title: "Buy milk",
        description: null,
        priority: 4,
        plan: { date: "2026-08-22" },
        labels: [],
        parentId: null,
        validationError: null,
      },
      {
        id: "draft-2",
        title: "Go to pool",
        description: null,
        priority: 4,
        plan: { date: "2026-08-25" },
        labels: [],
        parentId: null,
        validationError: null,
      },
    ];

    mockFetch.mockResolvedValue({
      ok: true,
      json: async () => ({
        transcript: "No, buy milk today, not tomorrow",
        mode: "create",
        drafts: [
          {
            target_draft_index: 0,
            title: "Buy milk",
            description: null,
            priority: 4,
            plan_date: "2026-08-21",
            plan_time: null,
            plan_type: null,
          },
        ],
      }),
    });

    const result = await sendVoiceCapture(mockSupabaseClient, {
      audioBlob: new Blob(["mock-wav"], { type: "audio/wav" }),
      recordingStartTime: "2026-08-21T10:00:00Z",
      timezone: "UTC",
      existingDrafts,
      effectiveDefaultTimedPlanType: "instant",
    });

    expect(result.drafts).toHaveLength(2);
    expect(result.drafts[0].title).toBe("Buy milk");
    expect(result.drafts[0].plan).toEqual({ date: "2026-08-21" }); // updated!
    expect(result.drafts[1].title).toBe("Go to pool");
    expect(result.drafts[1].plan).toEqual({ date: "2026-08-25" }); // untouched!
  });

  it("throws VoiceError on 429 rate limit with earliest retry timestamp", async () => {
    mockFetch.mockResolvedValue({
      ok: false,
      status: 429,
      json: async () => ({
        error: "Rate limit exceeded: maximum 3 requests per minute.",
        code: "rate_limit_minute",
        earliest_retry_at: "2026-08-21T10:01:00Z",
        retry_after_seconds: 45,
      }),
    });

    await expect(
      sendVoiceCapture(mockSupabaseClient, {
        audioBlob: new Blob(["mock-wav"], { type: "audio/wav" }),
        recordingStartTime: "2026-08-21T10:00:00Z",
        timezone: "UTC",
        effectiveDefaultTimedPlanType: "instant",
      }),
    ).rejects.toMatchObject({
      status: 429,
      code: "rate_limit_minute",
      earliestRetryAt: "2026-08-21T10:01:00Z",
      retryAfterSeconds: 45,
    });
  });

  it("throws VoiceError on 503 circuit breaker", async () => {
    mockFetch.mockResolvedValue({
      ok: false,
      status: 503,
      json: async () => ({
        error: "Voice capture is temporarily unavailable. Please try again later.",
        code: "circuit_breaker_daily",
      }),
    });

    await expect(
      sendVoiceCapture(mockSupabaseClient, {
        audioBlob: new Blob(["mock-wav"], { type: "audio/wav" }),
        recordingStartTime: "2026-08-21T10:00:00Z",
        timezone: "UTC",
        effectiveDefaultTimedPlanType: "instant",
      }),
    ).rejects.toMatchObject({
      status: 503,
      code: "circuit_breaker_daily",
    });
  });

  it("flags isNetworkError when network fetch fails so local recording is preserved", async () => {
    mockFetch.mockRejectedValue(new TypeError("Failed to fetch"));

    await expect(
      sendVoiceCapture(mockSupabaseClient, {
        audioBlob: new Blob(["mock-wav"], { type: "audio/wav" }),
        recordingStartTime: "2026-08-21T10:00:00Z",
        timezone: "UTC",
        effectiveDefaultTimedPlanType: "instant",
      }),
    ).rejects.toMatchObject({
      isNetworkError: true,
      code: "network_error",
    });
  });
});
