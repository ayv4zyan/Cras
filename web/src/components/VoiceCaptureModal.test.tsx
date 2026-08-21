import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { VoiceCaptureModal } from "./VoiceCaptureModal";
import type { Task } from "../contracts/task";
import * as voiceService from "../services/voiceService";

import type { SupabaseClient } from "@supabase/supabase-js";
import type { AudioRecorder } from "../services/audioRecorder";

describe("VoiceCaptureModal Component", () => {
  let mockClient: SupabaseClient;
  let mockRecorder: {
    start: ReturnType<typeof vi.fn>;
    stop: ReturnType<typeof vi.fn>;
    cancel: ReturnType<typeof vi.fn>;
    isRecording: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    mockClient = {
      supabaseUrl: "https://example.supabase.co",
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: { session: { access_token: "test-token" } },
        }),
      },
    } as unknown as SupabaseClient;

    mockRecorder = {
      start: vi.fn().mockResolvedValue(undefined),
      stop: vi.fn().mockResolvedValue({
        blob: new Blob(["fake-wav"], { type: "audio/wav" }),
        durationSeconds: 5,
        sizeBytes: 160000,
      }),
      cancel: vi.fn(),
      isRecording: vi.fn().mockReturnValue(true),
    };

    vi.spyOn(
      await import("../services/audioRecorder"),
      "AudioRecorder",
    ).mockImplementation(() => mockRecorder as unknown as AudioRecorder);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("starts recording immediately when opened", async () => {
    render(
      <VoiceCaptureModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
        onAcceptDrafts={vi.fn()}
      />,
    );

    expect(screen.getByText(/listening/i)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /done recording/i }),
    ).toBeInTheDocument();
  });

  it("stops recording, processes voice, and displays editable drafts", async () => {
    const handleAcceptDrafts = vi.fn().mockResolvedValue(undefined);
    const handleClose = vi.fn();

    vi.spyOn(voiceService, "sendVoiceCapture").mockResolvedValue({
      transcript: "Schedule review meeting tomorrow at 2pm",
      mode: "create",
      drafts: [
        {
          id: "draft-uuid-1",
          title: "Schedule review meeting",
          description: null,
          priority: 3,
          plan: {
            type: "instant",
            at: "2026-08-22T14:00:00.000Z",
          },
          labels: [],
          parentId: null,
          validationError: null,
        },
      ],
    });

    render(
      <VoiceCaptureModal
        isOpen={true}
        onClose={handleClose}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
        onAcceptDrafts={handleAcceptDrafts}
      />,
    );

    const doneButton = screen.getByRole("button", { name: /done recording/i });
    fireEvent.click(doneButton);

    await waitFor(() => {
      expect(
        screen.getByText(/schedule review meeting tomorrow at 2pm/i),
      ).toBeInTheDocument();
    });

    const titleInput = screen.getByLabelText(/draft 1 title/i);
    expect(titleInput).toHaveValue("Schedule review meeting");

    const acceptButton = screen.getByRole("button", { name: /accept all/i });
    fireEvent.click(acceptButton);

    await waitFor(() => {
      expect(handleAcceptDrafts).toHaveBeenCalledTimes(1);
      const submitted = handleAcceptDrafts.mock.calls[0][0];
      expect(submitted[0].title).toBe("Schedule review meeting");
      expect(handleClose).toHaveBeenCalledTimes(1);
    });
  });

  it("allows editing draft fields before accepting", async () => {
    const handleAcceptDrafts = vi.fn().mockResolvedValue(undefined);

    vi.spyOn(voiceService, "sendVoiceCapture").mockResolvedValue({
      transcript: "Draft title",
      mode: "create",
      drafts: [
        {
          id: "draft-1",
          title: "Initial title",
          description: null,
          priority: 4,
          plan: null,
          labels: [],
          parentId: null,
          validationError: null,
        },
      ],
    });

    render(
      <VoiceCaptureModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
        onAcceptDrafts={handleAcceptDrafts}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /done recording/i }));

    await waitFor(() => {
      expect(screen.getByLabelText(/draft 1 title/i)).toBeInTheDocument();
    });

    const titleInput = screen.getByLabelText(/draft 1 title/i);
    fireEvent.change(titleInput, { target: { value: "Manually Edited Title" } });

    const prioritySelect = screen.getByLabelText(/draft 1 priority/i);
    fireEvent.change(prioritySelect, { target: { value: "1" } });

    fireEvent.click(screen.getByRole("button", { name: /accept all/i }));

    await waitFor(() => {
      expect(handleAcceptDrafts).toHaveBeenCalledWith([
        expect.objectContaining({
          title: "Manually Edited Title",
          priority: 1,
        }),
      ]);
    });
  });

  it("switching Instant <-> Floating in draft preserves displayed date and time", async () => {
    vi.spyOn(voiceService, "sendVoiceCapture").mockResolvedValue({
      transcript: "Dinner on August 25 at 19:00",
      mode: "create",
      drafts: [
        {
          id: "draft-1",
          title: "Dinner",
          description: null,
          priority: 4,
          plan: {
            type: "instant",
            at: "2026-08-25T19:00:00.000Z",
          },
          labels: [],
          parentId: null,
          validationError: null,
        },
      ],
    });

    render(
      <VoiceCaptureModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
        onAcceptDrafts={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /done recording/i }));

    await waitFor(() => {
      expect(screen.getByLabelText(/draft 1 plan type/i)).toBeInTheDocument();
    });

    const dateInput = screen.getByLabelText(/draft 1 date/i);
    const timeInput = screen.getByLabelText(/draft 1 time/i);
    const displayedDateBefore = (dateInput as HTMLInputElement).value;
    const displayedTimeBefore = (timeInput as HTMLInputElement).value;

    const typeSelect = screen.getByLabelText(/draft 1 plan type/i);
    expect(typeSelect).toHaveValue("instant");

    // Switch to floating
    fireEvent.change(typeSelect, { target: { value: "floating" } });

    expect(typeSelect).toHaveValue("floating");
    expect(screen.getByLabelText(/draft 1 date/i)).toHaveValue(
      displayedDateBefore,
    );
    expect(screen.getByLabelText(/draft 1 time/i)).toHaveValue(
      displayedTimeBefore,
    );
  });

  it("disables Accept All when a draft has a validation error", async () => {
    vi.spyOn(voiceService, "sendVoiceCapture").mockResolvedValue({
      transcript: "Explicit instant without time",
      mode: "create",
      drafts: [
        {
          id: "draft-1",
          title: "Invalid explicit instant task",
          description: null,
          priority: 4,
          plan: { date: "2026-08-25" },
          labels: [],
          parentId: null,
          validationError:
            "An explicit Instant or Floating plan requires a clock time. Please provide a time or change to Date-only.",
        },
      ],
    });

    render(
      <VoiceCaptureModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
        onAcceptDrafts={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /done recording/i }));

    await waitFor(() => {
      expect(
        screen.getAllByText(
          /an explicit instant or floating plan requires a clock time/i,
        ).length,
      ).toBeGreaterThan(0);
    });

    const acceptButton = screen.getByRole("button", { name: /accept all/i });
    expect(acceptButton).toBeDisabled();
  });

  it("handles voice edit mode when focusedTask is provided", async () => {
    const focusedTask: Task = {
      id: "focused-task-1",
      title: "Existing Task",
      description: "Existing desc",
      priority: 2,
      plan: null,
      labels: [],
      parentId: null,
      completedAt: null,
      createdAt: "2026-08-20T00:00:00Z",
      updatedAt: "2026-08-20T00:00:00Z",
      version: 1,
    };

    const handleAcceptEdit = vi.fn().mockResolvedValue(undefined);

    vi.spyOn(voiceService, "sendVoiceCapture").mockResolvedValue({
      transcript: "Change priority to urgent and add tomorrow",
      mode: "edit",
      drafts: [],
      editProposal: {
        id: "draft-edit-1",
        originalTaskId: "focused-task-1",
        title: "Existing Task",
        description: "Existing desc",
        priority: 1,
        plan: { date: "2026-08-22" },
        labels: [],
        parentId: null,
        validationError: null,
      },
    });

    render(
      <VoiceCaptureModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        focusedTask={focusedTask}
        effectiveDefaultTimedPlanType="instant"
        onAcceptDrafts={vi.fn()}
        onAcceptEdit={handleAcceptEdit}
      />,
    );

    expect(screen.getByText(/voice edit task/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /done recording/i }));

    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /accept changes/i }),
      ).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole("button", { name: /accept changes/i }));

    await waitFor(() => {
      expect(handleAcceptEdit).toHaveBeenCalledWith(
        expect.objectContaining({
          originalTaskId: "focused-task-1",
          priority: 1,
        }),
      );
    });
  });

  it("handles error state and allows retry with saved audio", async () => {
    vi.spyOn(voiceService, "sendVoiceCapture")
      .mockRejectedValueOnce({
        status: 0,
        code: "network_error",
        message: "Network error: unable to reach Voice service. Your recording is preserved.",
        isNetworkError: true,
      })
      .mockResolvedValueOnce({
        transcript: "Retried successfully",
        mode: "create",
        drafts: [
          {
            id: "draft-1",
            title: "Retried task",
            description: null,
            priority: 4,
            plan: null,
            labels: [],
            parentId: null,
            validationError: null,
          },
        ],
      });

    render(
      <VoiceCaptureModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
        onAcceptDrafts={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /done recording/i }));

    await waitFor(() => {
      expect(
        screen.getByText(/unable to reach voice service/i),
      ).toBeInTheDocument();
    });

    const retryButton = screen.getByRole("button", {
      name: /retry with saved audio/i,
    });
    fireEvent.click(retryButton);

    await waitFor(() => {
      expect(screen.getByText(/retried successfully/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/draft 1 title/i)).toHaveValue(
        "Retried task",
      );
    });
  });
});
