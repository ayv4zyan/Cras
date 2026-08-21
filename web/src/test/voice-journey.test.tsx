import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import React from "react";
import { AuthenticatedApp } from "../App";
import type { Task, Label } from "../contracts/task";
import * as voiceService from "../services/voiceService";
import * as taskService from "../services/taskService";
import * as labelService from "../services/labelService";
import * as settingsService from "../services/settingsService";
import { clearOutbox } from "../services/outboxService";
import * as audioRecorderModule from "../services/audioRecorder";

import type { SupabaseClient, User } from "@supabase/supabase-js";

describe("Voice Capture & Task Management Journey on Web (Issue #53)", () => {
  let mockClient: SupabaseClient;
  let mockUser: User;
  let mockTasks: Task[];
  let mockLabels: Label[];
  let mockRecorder: {
    start: ReturnType<typeof vi.fn>;
    stop: ReturnType<typeof vi.fn>;
    cancel: ReturnType<typeof vi.fn>;
    isRecording: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    localStorage.clear();
    clearOutbox("operator-uuid-1");
    localStorage.setItem("cras_notifications_permission_explained", "true");

    mockTasks = [
      {
        id: "task-existing-1",
        title: "Existing Task 1",
        description: "Initial description",
        priority: 3,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-20T10:00:00.000Z",
        updatedAt: "2026-08-20T10:00:00.000Z",
        version: 1,
      },
    ];

    mockLabels = [
      {
        id: "label-1",
        name: "Work",
        color: "#3b82f6",
        createdAt: "2026-08-20T10:00:00.000Z",
        updatedAt: "2026-08-20T10:00:00.000Z",
      },
    ];

    mockUser = {
      id: "operator-uuid-1",
      email: "operator@example.com",
    };

    mockClient = {
      supabaseUrl: "https://example.supabase.co",
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: { session: { access_token: "mock-jwt" } },
        }),
      },
      from: vi.fn((table: string) => {
        if (table === "settings") {
          return {
            select: vi.fn().mockReturnValue({
              maybeSingle: vi.fn().mockResolvedValue({
                data: {
                  operator_id: "operator-uuid-1",
                  default_timed_plan_type: "instant",
                  missed_delivery_enabled: false,
                  stt_model_key: "voxtral-small",
                  extractor_model_key: "gemma-4-26b-a4b-it",
                  custom_extractor_prompt: null,
                },
                error: null,
              }),
            }),
            upsert: vi.fn().mockResolvedValue({ error: null }),
          };
        }
        if (table === "deployment_config") {
          return {
            select: vi.fn().mockReturnValue({
              maybeSingle: vi.fn().mockResolvedValue({
                data: {
                  id: 1,
                  default_timed_plan_type: "instant",
                  voice_enabled: true,
                },
                error: null,
              }),
            }),
          };
        }
        if (table === "voice_model_catalog") {
          return {
            select: vi.fn().mockReturnValue({
              eq: vi.fn().mockResolvedValue({
                data: [
                  {
                    key: "voxtral-small",
                    type: "stt",
                    name: "Voxtral Small",
                    is_default: true,
                    is_enabled: true,
                  },
                  {
                    key: "gemma-4-26b-a4b-it",
                    type: "extractor",
                    name: "Gemma 4 26B-A4B-it",
                    is_default: true,
                    is_enabled: true,
                  },
                ],
                error: null,
              }),
            }),
          };
        }
        return {
          select: vi.fn().mockReturnValue({
            maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
          }),
        };
      }),
      schema: vi.fn().mockReturnValue({
        from: vi.fn((table: string) => {
          if (table === "tasks") {
            return {
              select: vi.fn().mockResolvedValue({
                data: mockTasks,
                error: null,
              }),
            };
          }
          if (table === "comments") {
            return {
              select: vi.fn().mockReturnValue({
                eq: vi.fn().mockResolvedValue({ data: [], error: null }),
              }),
            };
          }
          return {
            select: vi.fn().mockResolvedValue({ data: [], error: null }),
          };
        }),
        rpc: vi.fn().mockResolvedValue({ data: null, error: null }),
      }),
      rpc: vi.fn().mockResolvedValue({ data: null, error: null }),
      channel: vi.fn().mockReturnValue({
        on: vi.fn().mockReturnThis(),
        subscribe: vi.fn().mockReturnValue({ unsubscribe: vi.fn() }),
      }),
    };

    mockRecorder = {
      start: vi.fn().mockResolvedValue(undefined),
      stop: vi.fn().mockResolvedValue({
        blob: new Blob(["fake-pcm-wav"], { type: "audio/wav" }),
        durationSeconds: 6.5,
        sizeBytes: 208000,
      }),
      cancel: vi.fn(),
      isRecording: vi.fn().mockReturnValue(true),
    };

    vi.spyOn(audioRecorderModule, "AudioRecorder").mockImplementation(
      () => mockRecorder as unknown as audioRecorderModule.AudioRecorder,
    );

    vi.spyOn(taskService, "fetchTasks").mockImplementation(async () => [
      ...mockTasks,
    ]);
    vi.spyOn(labelService, "fetchLabels").mockImplementation(async () => [
      ...mockLabels,
    ]);
    vi.spyOn(settingsService, "fetchOperatorSettings").mockResolvedValue({
      operator_id: "operator-uuid-1",
      default_timed_plan_type: "instant",
      missed_delivery_enabled: false,
      stt_model_key: "voxtral-small",
      extractor_model_key: "gemma-4-26b-a4b-it",
      custom_extractor_prompt: null,
    });
    vi.spyOn(settingsService, "fetchDeploymentConfig").mockResolvedValue({
      id: 1,
      default_timed_plan_type: "instant",
      voice_enabled: true,
    });
    vi.spyOn(voiceService, "fetchVoiceModelCatalog").mockResolvedValue([
      {
        key: "voxtral-small",
        type: "stt",
        name: "Voxtral Small",
        is_default: true,
        is_enabled: true,
      },
      {
        key: "gemma-4-26b-a4b-it",
        type: "extractor",
        name: "Gemma 4 26B-A4B-it",
        is_default: true,
        is_enabled: true,
      },
    ]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("Acceptance Criterion: Operator can record voice, receive editable draft, customize fields, and accept create", async () => {
    const handleSignOut = vi.fn().mockResolvedValue(undefined);

    vi.spyOn(taskService, "createTask").mockImplementation(
      async (_, params) => {
        const newTask: Task = {
          id: "new-task-uuid-" + Math.random().toString(36).substring(2, 9),
          title: typeof params === "string" ? params : params.title,
          description:
            typeof params === "object" ? params.description || null : null,
          priority: typeof params === "object" ? params.priority || 4 : 4,
          plan: typeof params === "object" ? params.plan || null : null,
          labels: typeof params === "object" ? params.labels || [] : [],
          parentId: null,
          completedAt: null,
          createdAt: "2026-08-21T10:00:00Z",
          updatedAt: "2026-08-21T10:00:00Z",
          version: 1,
        };
        mockTasks.push(newTask);
        return newTask;
      },
    );

    vi.spyOn(voiceService, "sendVoiceCapture").mockResolvedValue({
      transcript: "Buy groceries tomorrow at 5pm and prepare dinner",
      mode: "create",
      drafts: [
        {
          id: "draft-1",
          title: "Buy groceries",
          description: null,
          priority: 2,
          plan: {
            type: "instant",
            at: "2026-08-22T17:00:00.000Z",
          },
          labels: [],
          parentId: null,
          validationError: null,
        },
        {
          id: "draft-2",
          title: "Prepare dinner",
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
      <AuthenticatedApp
        client={mockClient}
        user={mockUser}
        onSignOut={handleSignOut}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText("Existing Task 1")).toBeInTheDocument();
    });

    // Click Voice Capture button in sidebar or CreateTaskInput
    const voiceButtons = screen.getAllByRole("button", {
      name: /voice capture/i,
    });
    fireEvent.click(voiceButtons[0]);

    // Modal opens and shows recording indicator
    expect(screen.getByText(/listening/i)).toBeInTheDocument();

    // Click Done Recording
    fireEvent.click(screen.getByRole("button", { name: /done recording/i }));

    // Wait for drafts to be extracted and presented
    await waitFor(() => {
      expect(
        screen.getByText(/buy groceries tomorrow at 5pm and prepare dinner/i),
      ).toBeInTheDocument();
    });

    expect(screen.getByDisplayValue("Buy groceries")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Prepare dinner")).toBeInTheDocument();

    // Edit the second draft description
    const descTextarea = screen.getByLabelText(/draft 2 description/i);
    fireEvent.change(descTextarea, {
      target: { value: "Pasta with mushrooms" },
    });

    // Accept All drafts
    const acceptButton = screen.getByRole("button", { name: /accept all/i });
    fireEvent.click(acceptButton);

    // Verify both tasks were submitted with user modifications
    await waitFor(() => {
      expect(taskService.createTask).toHaveBeenCalledWith(
        mockClient,
        expect.objectContaining({
          title: "Buy groceries",
        }),
      );
      expect(taskService.createTask).toHaveBeenCalledWith(
        mockClient,
        expect.objectContaining({
          title: "Prepare dinner",
          description: "Pasta with mushrooms",
        }),
      );
    });
  });

  it("Acceptance Criterion: Voice Edit on focused task proposes changes, preserves existing timed type, and allows accept", async () => {
    const handleSignOut = vi.fn().mockResolvedValue(undefined);

    vi.spyOn(taskService, "updateTask").mockImplementation(
      async (_, params) => {
        const existing = mockTasks.find((t) => t.id === params.id)!;
        const updated: Task = {
          ...existing,
          title: params.title ?? existing.title,
          description: params.description ?? existing.description,
          priority: params.priority ?? existing.priority,
          plan: params.clearPlan ? null : (params.plan ?? existing.plan),
          version: existing.version + 1,
          updatedAt: "2026-08-21T11:00:00Z",
        };
        const idx = mockTasks.findIndex((t) => t.id === params.id);
        mockTasks[idx] = updated;
        return updated;
      },
    );

    vi.spyOn(voiceService, "sendVoiceCapture").mockResolvedValue({
      transcript: "Change priority to urgent and move to tomorrow 2pm",
      mode: "edit",
      drafts: [],
      editProposal: {
        id: "draft-edit-1",
        originalTaskId: "task-existing-1",
        title: "Existing Task 1",
        description: "Initial description",
        priority: 1,
        plan: {
          type: "floating", // Preserved floating!
          date: "2026-08-22",
          time: "14:00:00",
        },
        labels: [],
        parentId: null,
        validationError: null,
      },
    });

    render(
      <AuthenticatedApp
        client={mockClient}
        user={mockUser}
        onSignOut={handleSignOut}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText("Existing Task 1")).toBeInTheDocument();
    });

    // Click on the task to open TaskDetailModal
    fireEvent.click(screen.getByText("Existing Task 1"));

    await waitFor(() => {
      expect(screen.getByLabelText(/task details/i)).toBeInTheDocument();
    });

    // Click Voice Edit button in TaskDetailModal header
    const voiceEditBtn = screen.getByRole("button", {
      name: /voice edit task/i,
    });
    fireEvent.click(voiceEditBtn);

    // Voice Capture Modal opens in edit mode
    expect(screen.getByText(/voice edit task/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /done recording/i }));

    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /accept changes/i }),
      ).toBeInTheDocument();
    });

    // Verify priority 1 and floating type preserved
    expect(screen.getByLabelText(/draft 1 priority/i)).toHaveValue("1");
    expect(screen.getByLabelText(/draft 1 plan type/i)).toHaveValue("floating");

    // Click Accept Changes
    fireEvent.click(screen.getByRole("button", { name: /accept changes/i }));

    // Task is updated
    await waitFor(() => {
      expect(taskService.updateTask).toHaveBeenCalledWith(
        mockClient,
        expect.objectContaining({
          id: "task-existing-1",
          priority: 1,
        }),
      );
    });
  });

  it("Acceptance Criterion: Ordinary task features remain fully functional when Voice fails or is rate-limited", async () => {
    const handleSignOut = vi.fn().mockResolvedValue(undefined);

    vi.spyOn(voiceService, "sendVoiceCapture").mockRejectedValue({
      status: 429,
      code: "rate_limit_minute",
      message: "Rate limit exceeded: maximum 3 requests per minute.",
      earliestRetryAt: "2026-08-21T10:01:00Z",
    });

    vi.spyOn(taskService, "createTask").mockImplementation(
      async (_, params) => {
        const newTask: Task = {
          id: "fallback-task-id",
          title: typeof params === "string" ? params : params.title,
          description: null,
          priority: 4,
          plan: null,
          labels: [],
          parentId: null,
          completedAt: null,
          createdAt: "2026-08-21T10:00:00Z",
          updatedAt: "2026-08-21T10:00:00Z",
          version: 1,
        };
        mockTasks.push(newTask);
        return newTask;
      },
    );

    render(
      <AuthenticatedApp
        client={mockClient}
        user={mockUser}
        onSignOut={handleSignOut}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText("Existing Task 1")).toBeInTheDocument();
    });

    // Try voice capture and hit 429
    const voiceButtons = screen.getAllByRole("button", {
      name: /voice capture/i,
    });
    fireEvent.click(voiceButtons[0]);
    fireEvent.click(screen.getByRole("button", { name: /done recording/i }));

    await waitFor(() => {
      expect(screen.getByText(/rate limit exceeded/i)).toBeInTheDocument();
    });

    // Close the error modal
    fireEvent.click(
      screen.getByRole("button", { name: /close voice capture dialog/i }),
    );

    // Verify ordinary task creation in input still works without issue!
    const textInput = screen.getByTestId("create-task-input");
    fireEvent.change(textInput, {
      target: { value: "Manual task while voice is down" },
    });
    fireEvent.keyDown(textInput, { key: "Enter", code: "Enter" });

    await waitFor(() => {
      expect(
        screen.getByText("Manual task while voice is down"),
      ).toBeInTheDocument();
    });
  });
});
