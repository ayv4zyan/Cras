import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  Mic,
  RefreshCw,
  Trash2,
  Check,
  X,
  AlertTriangle,
  Loader2,
  Calendar,
  Clock,
  Sparkles,
  RotateCcw,
} from "lucide-react";
import { AudioRecorder } from "../services/audioRecorder";
import {
  sendVoiceCapture,
  switchDraftTimedPlanType,
  type DraftTask,
  type VoiceError,
} from "../services/voiceService";
import {
  createPlanFromInputs,
  formatPlanDate,
  formatPlanTime,
  type TimedPlanType,
} from "../services/temporalService";
import { PRIORITY_OPTIONS, type Priority, type Task } from "../contracts/task";
import type { SupabaseClient } from "@supabase/supabase-js";

export interface VoiceCaptureModalProps {
  readonly isOpen: boolean;
  readonly onClose: () => void;
  readonly client: SupabaseClient;
  readonly effectiveDefaultTimedPlanType: TimedPlanType;
  readonly focusedTask?: Task | null;
  readonly onAcceptDrafts: (
    drafts: readonly DraftTask[],
  ) => Promise<void> | void;
  readonly onAcceptEdit?: (draft: DraftTask) => Promise<void> | void;
}

type ModalState = "idle" | "recording" | "processing" | "drafts" | "error";

export function VoiceCaptureModal({
  isOpen,
  onClose,
  client,
  effectiveDefaultTimedPlanType,
  focusedTask = null,
  onAcceptDrafts,
  onAcceptEdit,
}: VoiceCaptureModalProps): React.JSX.Element | null {
  const [state, setState] = useState<ModalState>("idle");
  const [recordingDurationMs, setRecordingDurationMs] = useState(0);
  const [lastAudioBlob, setLastAudioBlob] = useState<Blob | null>(null);
  const [recordingStartTimeStr, setRecordingStartTimeStr] =
    useState<string>("");
  const [transcript, setTranscript] = useState<string>("");
  const [drafts, setDrafts] = useState<DraftTask[]>([]);
  const [error, setError] = useState<VoiceError | null>(null);
  const [isSaving, setIsSaving] = useState(false);

  const recorderRef = useRef<AudioRecorder | null>(null);
  const durationTimerRef = useRef<number | null>(null);
  const modalRef = useRef<HTMLDivElement>(null);

  // Initialize or reset recorder
  const getRecorder = useCallback(() => {
    if (!recorderRef.current) {
      recorderRef.current = new AudioRecorder();
    }
    return recorderRef.current;
  }, []);

  const handleStopAndProcess = useCallback(
    async (isCorrection = false) => {
      const recorder = getRecorder();
      if (durationTimerRef.current) {
        clearInterval(durationTimerRef.current);
        durationTimerRef.current = null;
      }

      try {
        const recordingResult = await recorder.stop();
        setLastAudioBlob(recordingResult.blob);
        setState("processing");

        const response = await sendVoiceCapture(client, {
          audioBlob: recordingResult.blob,
          recordingStartTime: recordingStartTimeStr || new Date().toISOString(),
          focusedTask: isCorrection ? null : focusedTask,
          existingDrafts: isCorrection ? drafts : null,
          effectiveDefaultTimedPlanType,
        });

        setTranscript(response.transcript);

        if (response.mode === "edit" && response.editProposal) {
          setDrafts([response.editProposal]);
        } else {
          setDrafts([...response.drafts]);
        }

        setState("drafts");
      } catch (err: unknown) {
        const vErr =
          err && typeof err === "object" && "status" in err
            ? (err as VoiceError)
            : {
                status: 0,
                message: err instanceof Error ? err.message : String(err),
              };
        setError(vErr);
        setState("error");
      }
    },
    [
      getRecorder,
      client,
      recordingStartTimeStr,
      focusedTask,
      drafts,
      effectiveDefaultTimedPlanType,
    ],
  );

  const handleStartRecording = useCallback(async () => {
    setError(null);
    setState("recording");
    const recorder = getRecorder();
    try {
      const startTime = new Date();
      setRecordingStartTimeStr(startTime.toISOString());
      setRecordingDurationMs(0);

      await recorder.start({
        onAutoStop: () => {
          void handleStopAndProcess();
        },
      });

      const startTimeMs = Date.now();
      durationTimerRef.current = window.setInterval(() => {
        setRecordingDurationMs(Date.now() - startTimeMs);
      }, 100);
    } catch (err) {
      setError({
        status: 0,
        message:
          err instanceof Error
            ? err.message
            : "Failed to access microphone. Please grant permission.",
      });
      setState("error");
    }
  }, [getRecorder, handleStopAndProcess]);

  const handleRetryProcessing = useCallback(async () => {
    if (!lastAudioBlob) {
      handleStartRecording();
      return;
    }

    setError(null);
    setState("processing");

    try {
      const response = await sendVoiceCapture(client, {
        audioBlob: lastAudioBlob,
        recordingStartTime: recordingStartTimeStr || new Date().toISOString(),
        focusedTask,
        existingDrafts: drafts.length > 0 ? drafts : null,
        effectiveDefaultTimedPlanType,
      });

      setTranscript(response.transcript);

      if (response.mode === "edit" && response.editProposal) {
        setDrafts([response.editProposal]);
      } else {
        setDrafts([...response.drafts]);
      }

      setState("drafts");
    } catch (err: unknown) {
      const vErr =
        err && typeof err === "object" && "status" in err
          ? (err as VoiceError)
          : {
              status: 0,
              message: err instanceof Error ? err.message : String(err),
            };
      setError(vErr);
      setState("error");
    }
  }, [
    lastAudioBlob,
    client,
    recordingStartTimeStr,
    focusedTask,
    drafts,
    effectiveDefaultTimedPlanType,
    handleStartRecording,
  ]);

  const handleCancelRecording = useCallback(() => {
    if (durationTimerRef.current) {
      clearInterval(durationTimerRef.current);
      durationTimerRef.current = null;
    }
    const recorder = getRecorder();
    recorder.cancel();
    setRecordingDurationMs(0);
    setState("idle");
  }, [getRecorder]);

  // Start recording on modal open, reset on close
  useEffect(() => {
    if (isOpen) {
      void handleStartRecording();
    } else {
      handleCancelRecording();
      setState("idle");
      setDrafts([]);
      setTranscript("");
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  // Clean up timer on unmount
  useEffect(() => {
    return () => {
      if (durationTimerRef.current) {
        clearInterval(durationTimerRef.current);
      }
      if (recorderRef.current) {
        recorderRef.current.cancel();
      }
    };
  }, []);

  const handleUpdateDraft = useCallback(
    (index: number, updates: Partial<DraftTask>) => {
      setDrafts((prev) => {
        const next = [...prev];
        next[index] = { ...next[index], ...updates };
        return next;
      });
    },
    [],
  );

  const handleRemoveDraft = useCallback((index: number) => {
    setDrafts((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const handleSwitchType = useCallback(
    (index: number, newType: TimedPlanType) => {
      setDrafts((prev) => {
        const next = [...prev];
        next[index] = switchDraftTimedPlanType(
          next[index],
          newType,
          effectiveDefaultTimedPlanType,
        );
        return next;
      });
    },
    [effectiveDefaultTimedPlanType],
  );

  const handleStartOver = useCallback(() => {
    setDrafts([]);
    setTranscript("");
    setError(null);
    handleStartRecording();
  }, [handleStartRecording]);

  const handleAcceptAll = useCallback(async () => {
    if (isSaving || drafts.length === 0) return;
    // Disallow accepting drafts with validation errors
    if (drafts.some((d) => Boolean(d.validationError))) {
      return;
    }

    setIsSaving(true);
    try {
      if (focusedTask && drafts.length === 1 && onAcceptEdit) {
        await onAcceptEdit(drafts[0]);
      } else {
        await onAcceptDrafts(drafts);
      }
      onClose();
    } catch (err) {
      setError({
        status: 0,
        message:
          err instanceof Error
            ? err.message
            : "Failed to save accepted drafts.",
      });
    } finally {
      setIsSaving(false);
    }
  }, [isSaving, drafts, focusedTask, onAcceptEdit, onAcceptDrafts, onClose]);

  if (!isOpen) {
    return null;
  }

  const hasValidationErrors = drafts.some((d) => Boolean(d.validationError));
  const formatSeconds = (ms: number) => {
    const totalSec = Math.floor(ms / 1000);
    const m = Math.floor(totalSec / 60);
    const s = totalSec % 60;
    return `${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`;
  };

  return (
    <div
      ref={modalRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="voice-capture-modal-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-xs animate-in fade-in-50"
    >
      <div className="relative w-full max-w-2xl rounded-xl border border-border bg-card p-6 shadow-xl space-y-5 text-card-foreground">
        {/* Header */}
        <div className="flex items-center justify-between pb-2 border-b border-border/60">
          <div className="flex items-center space-x-2.5">
            <div className="p-1.5 rounded-lg bg-primary/10 text-primary">
              <Mic className="h-5 w-5" />
            </div>
            <div>
              <h2
                id="voice-capture-modal-title"
                className="text-base font-semibold text-foreground"
              >
                {focusedTask ? "Voice Edit Task" : "Voice Capture"}
              </h2>
              <p className="text-xs text-muted-foreground">
                {focusedTask
                  ? `Dictate changes to "${focusedTask.title}"`
                  : "Dictate tasks naturally with title, description, priority, and dates"}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close voice capture dialog"
            className="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors cursor-pointer"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* State 1: Recording */}
        {state === "recording" && (
          <div className="py-8 flex flex-col items-center justify-center space-y-6 text-center">
            <div className="relative flex items-center justify-center">
              <span className="absolute inline-flex h-20 w-20 animate-ping rounded-full bg-primary/20 opacity-75" />
              <div className="relative flex h-16 w-16 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-md">
                <Mic className="h-8 w-8 animate-pulse" />
              </div>
            </div>

            <div className="space-y-1">
              <div className="text-xl font-mono font-semibold text-foreground">
                {formatSeconds(recordingDurationMs)} / 02:00
              </div>
              <p className="text-xs text-muted-foreground">
                Listening... Speak naturally. Say "Done" or click finish when
                ready.
              </p>
            </div>

            <div className="flex items-center space-x-3">
              <button
                type="button"
                onClick={() => handleStopAndProcess(drafts.length > 0)}
                className="inline-flex items-center space-x-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-medium hover:opacity-90 transition-opacity cursor-pointer shadow-xs"
              >
                <Check className="h-4 w-4" />
                <span>Done Recording</span>
              </button>
              <button
                type="button"
                onClick={handleCancelRecording}
                className="inline-flex items-center space-x-1.5 px-3.5 py-2 rounded-lg border border-border text-muted-foreground text-sm hover:bg-secondary transition-colors cursor-pointer"
              >
                <X className="h-4 w-4" />
                <span>Cancel</span>
              </button>
            </div>
          </div>
        )}

        {/* State 2: Processing */}
        {state === "processing" && (
          <div className="py-12 flex flex-col items-center justify-center space-y-3 text-center">
            <Loader2 className="h-8 w-8 animate-spin text-primary" />
            <div className="space-y-1">
              <p className="text-sm font-medium text-foreground">
                Transcribing and extracting task metadata...
              </p>
              <p className="text-xs text-muted-foreground">
                Voxtral Small & Gemma 4 are structuring your tasks
              </p>
            </div>
          </div>
        )}

        {/* State 3: Error */}
        {state === "error" && error && (
          <div className="space-y-4">
            <div className="rounded-lg bg-destructive/10 border border-destructive/20 p-4 space-y-2 text-destructive">
              <div className="flex items-center space-x-2">
                <AlertTriangle className="h-5 w-5 shrink-0" />
                <span className="font-semibold text-sm">
                  Voice Capture Unavailable
                </span>
              </div>
              <p className="text-xs leading-relaxed">{error.message}</p>
              {error.earliestRetryAt && (
                <p className="text-xs font-mono">
                  Earliest retry:{" "}
                  {new Date(error.earliestRetryAt).toLocaleTimeString()}
                </p>
              )}
            </div>

            <div className="flex items-center justify-end space-x-3 pt-2">
              {lastAudioBlob && (
                <button
                  type="button"
                  onClick={handleRetryProcessing}
                  className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-md bg-primary text-primary-foreground text-xs font-medium hover:opacity-90 cursor-pointer"
                >
                  <RefreshCw className="h-3.5 w-3.5" />
                  <span>Retry with Saved Audio</span>
                </button>
              )}
              <button
                type="button"
                onClick={handleStartRecording}
                className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-md border border-border text-foreground text-xs hover:bg-secondary cursor-pointer"
              >
                <Mic className="h-3.5 w-3.5" />
                <span>Record Again</span>
              </button>
              <button
                type="button"
                onClick={onClose}
                className="px-3 py-1.5 rounded-md text-xs text-muted-foreground hover:bg-secondary cursor-pointer"
              >
                Close
              </button>
            </div>
          </div>
        )}

        {/* State 4: Drafts on Screen */}
        {state === "drafts" && (
          <div className="space-y-4 max-h-[70vh] overflow-y-auto pr-1">
            {/* Transcript Card */}
            {transcript && (
              <div className="rounded-lg bg-secondary/40 border border-border/70 p-3 space-y-1">
                <div className="flex items-center space-x-1.5 text-xs font-medium text-muted-foreground">
                  <Sparkles className="h-3.5 w-3.5 text-primary" />
                  <span>Heard:</span>
                </div>
                <p className="text-xs text-foreground italic">"{transcript}"</p>
              </div>
            )}

            {/* Validation Banner */}
            {hasValidationErrors && (
              <div className="rounded-md bg-amber-500/10 border border-amber-500/30 p-2.5 text-xs text-amber-800 dark:text-amber-300">
                Please correct invalid plan entries below (an explicit Instant
                or Floating plan requires a clock time) before accepting.
              </div>
            )}

            {/* Proposed Drafts List */}
            <div className="space-y-3">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>
                  {focusedTask
                    ? "Proposed Change (Editable Draft):"
                    : `Proposed Tasks (${drafts.length}):`}
                </span>
                <span className="text-[11px] italic">
                  Not saved until you click Accept
                </span>
              </div>

              {drafts.map((draft, idx) => {
                const planDateStr = formatPlanDate(draft.plan) || "";
                const planTimeStr = formatPlanTime(draft.plan) || "";
                const isTimed = Boolean(planDateStr && planTimeStr);
                const currentType: TimedPlanType =
                  draft.plan && "type" in draft.plan && draft.plan.type
                    ? draft.plan.type
                    : effectiveDefaultTimedPlanType;

                return (
                  <div
                    key={draft.id}
                    className={`rounded-lg border p-3.5 space-y-3 bg-background/60 transition-all ${
                      draft.validationError
                        ? "border-destructive/60 ring-1 ring-destructive/40"
                        : "border-border/80"
                    }`}
                  >
                    {/* Draft Header & Remove button */}
                    <div className="flex items-start justify-between space-x-2">
                      <div className="flex-1 space-y-1.5">
                        <input
                          type="text"
                          value={draft.title}
                          onChange={(e) =>
                            handleUpdateDraft(idx, { title: e.target.value })
                          }
                          aria-label={`Draft ${idx + 1} Title`}
                          placeholder="Task title"
                          className="w-full font-medium text-sm text-foreground bg-transparent border-b border-border/40 pb-1 focus:outline-hidden focus:border-ring"
                        />
                      </div>
                      {drafts.length > 1 && (
                        <button
                          type="button"
                          onClick={() => handleRemoveDraft(idx)}
                          aria-label="Remove draft"
                          title="Remove draft"
                          className="p-1 rounded-md text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors cursor-pointer"
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      )}
                    </div>

                    {/* Description */}
                    <div>
                      <textarea
                        value={draft.description || ""}
                        onChange={(e) =>
                          handleUpdateDraft(idx, {
                            description: e.target.value || null,
                          })
                        }
                        aria-label={`Draft ${idx + 1} Description`}
                        placeholder="Optional description..."
                        rows={1}
                        className="w-full text-xs text-foreground placeholder:text-muted-foreground bg-secondary/20 rounded-md border border-border/50 p-1.5 focus:outline-hidden focus:ring-1 focus:ring-ring resize-none"
                      />
                    </div>

                    {/* Plan and Priority Controls */}
                    <div className="flex flex-wrap items-center gap-3 pt-1 border-t border-border/40 text-xs">
                      {/* Priority */}
                      <div className="flex items-center space-x-1.5">
                        <span className="text-muted-foreground">Priority:</span>
                        <select
                          value={draft.priority}
                          onChange={(e) =>
                            handleUpdateDraft(idx, {
                              priority: Number(e.target.value) as Priority,
                            })
                          }
                          aria-label={`Draft ${idx + 1} Priority`}
                          className="rounded-md border border-border/60 bg-background px-2 py-1 text-xs text-foreground cursor-pointer focus:outline-hidden focus:ring-1 focus:ring-ring"
                        >
                          {PRIORITY_OPTIONS.map((p) => (
                            <option key={p.value} value={p.value}>
                              {p.label}
                            </option>
                          ))}
                        </select>
                      </div>

                      {/* Date & Time */}
                      <div className="flex items-center space-x-2">
                        <Calendar className="h-3.5 w-3.5 text-muted-foreground" />
                        <input
                          type="date"
                          value={planDateStr}
                          onChange={(e) => {
                            const newDate = e.target.value;
                            if (!newDate) {
                              handleUpdateDraft(idx, {
                                plan: null,
                                validationError: null,
                              });
                            } else {
                              const plan = createPlanFromInputs({
                                date: newDate,
                                time: planTimeStr || null,
                                type: isTimed ? currentType : null,
                                effectiveDefault: effectiveDefaultTimedPlanType,
                              });
                              handleUpdateDraft(idx, {
                                plan,
                                validationError: null,
                              });
                            }
                          }}
                          aria-label={`Draft ${idx + 1} Date`}
                          className="rounded-md border border-border/60 bg-background px-2 py-1 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
                        />

                        {planDateStr && (
                          <div className="flex items-center space-x-1.5">
                            <Clock className="h-3.5 w-3.5 text-muted-foreground" />
                            <input
                              type="time"
                              value={planTimeStr}
                              onChange={(e) => {
                                const newTime = e.target.value;
                                const plan = createPlanFromInputs({
                                  date: planDateStr,
                                  time: newTime || null,
                                  type: newTime ? currentType : null,
                                  effectiveDefault:
                                    effectiveDefaultTimedPlanType,
                                });
                                handleUpdateDraft(idx, {
                                  plan,
                                  validationError: null,
                                });
                              }}
                              aria-label={`Draft ${idx + 1} Time`}
                              className="rounded-md border border-border/60 bg-background px-2 py-1 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
                            />

                            {/* Plan Type Selector when timed */}
                            {planTimeStr && (
                              <select
                                value={currentType}
                                onChange={(e) =>
                                  handleSwitchType(
                                    idx,
                                    e.target.value as TimedPlanType,
                                  )
                                }
                                aria-label={`Draft ${idx + 1} Plan Type`}
                                className="rounded-md border border-border/60 bg-background px-2 py-1 text-xs text-foreground cursor-pointer focus:outline-hidden focus:ring-1 focus:ring-ring"
                              >
                                <option value="instant">Instant</option>
                                <option value="floating">Floating</option>
                              </select>
                            )}
                          </div>
                        )}
                      </div>
                    </div>

                    {/* Draft Validation Error */}
                    {draft.validationError && (
                      <p className="text-xs text-destructive">
                        {draft.validationError}
                      </p>
                    )}
                  </div>
                );
              })}
            </div>

            {/* Bottom Actions Bar */}
            <div className="flex flex-wrap items-center justify-between gap-3 pt-3 border-t border-border/60">
              <div className="flex items-center space-x-2">
                {/* Voice Correction Button */}
                <button
                  type="button"
                  onClick={() => handleStartRecording()}
                  title="Speak again to correct or refine drafts"
                  className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-md border border-border bg-background text-xs font-medium text-foreground hover:bg-secondary transition-colors cursor-pointer"
                >
                  <Mic className="h-3.5 w-3.5 text-primary" />
                  <span>Correct by Voice</span>
                </button>

                {/* Start Over */}
                <button
                  type="button"
                  onClick={handleStartOver}
                  title="Clear all drafts and record again"
                  className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-md text-xs text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors cursor-pointer"
                >
                  <RotateCcw className="h-3.5 w-3.5" />
                  <span>Start over</span>
                </button>
              </div>

              <div className="flex items-center space-x-2">
                <button
                  type="button"
                  onClick={onClose}
                  className="px-3 py-1.5 rounded-md border border-border text-xs text-muted-foreground hover:bg-secondary transition-colors cursor-pointer"
                >
                  Discard
                </button>

                <button
                  type="button"
                  onClick={handleAcceptAll}
                  disabled={
                    isSaving || hasValidationErrors || drafts.length === 0
                  }
                  className="inline-flex items-center space-x-1.5 px-4 py-1.5 rounded-md bg-primary text-primary-foreground text-xs font-medium hover:opacity-90 transition-opacity disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer shadow-xs"
                >
                  {isSaving ? (
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  ) : (
                    <Check className="h-3.5 w-3.5" />
                  )}
                  <span>{focusedTask ? "Accept Changes" : "Accept All"}</span>
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
