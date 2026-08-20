import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  X,
  CheckCircle2,
  Circle,
  AlertCircle,
  Loader2,
  Tag,
  MessageSquare,
  ListTree,
  Plus,
  Calendar,
  Clock,
} from "lucide-react";
import {
  PRIORITY_OPTIONS,
  type Priority,
  type Task,
  type Label,
  type Comment,
} from "../contracts/task";
import type { UpdateTaskParams } from "../services/taskService";
import {
  createPlanFromInputs,
  formatPlanDisplay,
  getDeviceLocalDate,
  type TimedPlanType,
} from "../services/temporalService";

export interface TaskDetailModalProps {
  readonly task: Task | null;
  readonly availableLabels?: readonly Label[];
  readonly comments?: readonly Comment[];
  readonly subtasks?: readonly Task[];
  readonly effectiveDefault?: TimedPlanType;
  readonly isOpen: boolean;
  readonly onClose: () => void;
  readonly onSave: (params: UpdateTaskParams) => Promise<void> | void;
  readonly onToggleComplete: (task: Task) => Promise<void> | void;
  readonly onAddComment?: (
    taskId: string,
    content: string,
  ) => Promise<void> | void;
  readonly onCreateSubtask?: (
    parentId: string,
    title: string,
  ) => Promise<void> | void;
  readonly onToggleSubtaskComplete?: (subtask: Task) => Promise<void> | void;
  readonly onSelectSubtask?: (subtask: Task) => void;
}

export function TaskDetailModal({
  task,
  availableLabels = [],
  comments = [],
  subtasks = [],
  effectiveDefault = "instant",
  isOpen,
  onClose,
  onSave,
  onToggleComplete,
  onAddComment,
  onCreateSubtask,
  onToggleSubtaskComplete,
  onSelectSubtask,
}: TaskDetailModalProps): React.JSX.Element | null {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState<Priority>(4);
  const [selectedLabels, setSelectedLabels] = useState<string[]>([]);
  const [planDate, setPlanDate] = useState<string>("");
  const [planTime, setPlanTime] = useState<string>("");
  const [planType, setPlanType] = useState<TimedPlanType>(effectiveDefault);
  const [newCommentContent, setNewCommentContent] = useState("");
  const [newSubtaskTitle, setNewSubtaskTitle] = useState("");
  const [isSaving, setIsSaving] = useState(false);
  const [isToggling, setIsToggling] = useState(false);
  const [isAddingComment, setIsAddingComment] = useState(false);
  const [isAddingSubtask, setIsAddingSubtask] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [commentError, setCommentError] = useState<string | null>(null);
  const [subtaskError, setSubtaskError] = useState<string | null>(null);
  const modalRef = useRef<HTMLDivElement>(null);

  const effectiveDefaultRef = useRef(effectiveDefault);
  useEffect(() => {
    effectiveDefaultRef.current = effectiveDefault;
  }, [effectiveDefault]);

  useEffect(() => {
    if (task) {
      setTitle(task.title);
      setDescription(task.description ?? "");
      setPriority(task.priority);
      setSelectedLabels(task.labels ? [...task.labels] : []);
      setNewCommentContent("");
      setNewSubtaskTitle("");
      setError(null);
      setCommentError(null);
      setSubtaskError(null);

      if (task.plan) {
        if ("type" in task.plan) {
          if (task.plan.type === "floating") {
            setPlanDate(task.plan.date);
            const parts = task.plan.time.split(":");
            setPlanTime(`${parts[0]}:${parts[1]}`);
            setPlanType("floating");
          } else if (task.plan.type === "instant") {
            const d = new Date(task.plan.at);
            setPlanDate(getDeviceLocalDate(d));
            const h = String(d.getHours()).padStart(2, "0");
            const m = String(d.getMinutes()).padStart(2, "0");
            setPlanTime(`${h}:${m}`);
            setPlanType("instant");
          }
        } else if ("date" in task.plan) {
          setPlanDate(task.plan.date);
          setPlanTime("");
          setPlanType(effectiveDefaultRef.current);
        }
      } else {
        setPlanDate("");
        setPlanTime("");
        setPlanType(effectiveDefaultRef.current);
      }
    }
  }, [task]);

  useEffect(() => {
    if (!isOpen || !task) return;

    const previouslyFocused = document.activeElement as HTMLElement | null;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key === "Tab" && modalRef.current) {
        const focusableElements =
          modalRef.current.querySelectorAll<HTMLElement>(
            'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
          );
        if (focusableElements.length === 0) return;
        const first = focusableElements[0];
        const last = focusableElements[focusableElements.length - 1];

        if (e.shiftKey) {
          if (document.activeElement === first) {
            e.preventDefault();
            last.focus();
          }
        } else {
          if (document.activeElement === last) {
            e.preventDefault();
            first.focus();
          }
        }
      }
    };

    window.addEventListener("keydown", handleKeyDown);

    const focusable = modalRef.current?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    if (focusable && focusable.length > 0) {
      focusable[0]?.focus();
    } else {
      modalRef.current?.focus();
    }

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      previouslyFocused?.focus();
    };
  }, [isOpen, task, onClose]);

  const isCompleted = Boolean(task?.completedAt);
  const isSubtask = Boolean(task?.parentId);

  const toggleLabel = useCallback(
    (labelId: string) => {
      if (isCompleted || isSaving) return;
      setSelectedLabels((prev) =>
        prev.includes(labelId)
          ? prev.filter((id) => id !== labelId)
          : [...prev, labelId],
      );
    },
    [isCompleted, isSaving],
  );

  const handleSave = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!task || isCompleted) return;

      const trimmedTitle = title.trim();
      if (!trimmedTitle) {
        setError("Task title cannot be empty");
        return;
      }

      setIsSaving(true);
      setError(null);
      try {
        const computedPlan = planDate.trim()
          ? createPlanFromInputs({
              date: planDate,
              time: planTime || null,
              type: planTime ? planType : null,
              effectiveDefault,
            })
          : null;

        await onSave({
          id: task.id,
          title: trimmedTitle,
          description: description.trim() || null,
          priority,
          labels: selectedLabels,
          plan: computedPlan,
          clearPlan: computedPlan === null,
          expectedVersion: task.version,
        });
        onClose();
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to update task");
      } finally {
        setIsSaving(false);
      }
    },
    [
      task,
      isCompleted,
      title,
      description,
      priority,
      selectedLabels,
      planDate,
      planTime,
      planType,
      effectiveDefault,
      onSave,
      onClose,
    ],
  );

  const handleToggle = useCallback(async () => {
    if (!task) return;
    setIsToggling(true);
    setError(null);
    try {
      await onToggleComplete(task);
      onClose();
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to update completion",
      );
    } finally {
      setIsToggling(false);
    }
  }, [task, onToggleComplete, onClose]);

  const handleAddCommentSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!task || !onAddComment) return;

      const trimmed = newCommentContent.trim();
      if (!trimmed) return;

      setIsAddingComment(true);
      setCommentError(null);
      try {
        await onAddComment(task.id, trimmed);
        setNewCommentContent("");
      } catch (err) {
        setCommentError(
          err instanceof Error ? err.message : "Failed to add comment",
        );
      } finally {
        setIsAddingComment(false);
      }
    },
    [task, onAddComment, newCommentContent],
  );

  const handleAddSubtaskSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!task || !onCreateSubtask || isSubtask) return;

      const trimmed = newSubtaskTitle.trim();
      if (!trimmed) return;

      setIsAddingSubtask(true);
      setSubtaskError(null);
      try {
        await onCreateSubtask(task.id, trimmed);
        setNewSubtaskTitle("");
      } catch (err) {
        setSubtaskError(
          err instanceof Error ? err.message : "Failed to create subtask",
        );
      } finally {
        setIsAddingSubtask(false);
      }
    },
    [task, onCreateSubtask, isSubtask, newSubtaskTitle],
  );

  if (!isOpen || !task) {
    return null;
  }

  const existingPlanDisplay = formatPlanDisplay(task.plan);

  return (
    <div
      ref={modalRef}
      role="dialog"
      aria-modal="true"
      aria-label="Task details"
      tabIndex={-1}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-xs p-4 overflow-y-auto outline-hidden"
    >
      <div className="relative w-full max-w-xl rounded-xl border border-border bg-card p-6 shadow-xl space-y-6 text-foreground animate-in fade-in zoom-in-95 duration-150 max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <button
              type="button"
              onClick={handleToggle}
              disabled={isToggling}
              aria-label={isCompleted ? "Uncomplete task" : "Complete task"}
              className={`flex items-center space-x-1.5 px-2.5 py-1 rounded-md text-xs font-medium border transition-colors cursor-pointer disabled:opacity-50 ${
                isCompleted
                  ? "border-emerald-600/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 hover:bg-emerald-500/20"
                  : "border-border text-muted-foreground hover:text-foreground hover:bg-secondary"
              }`}
            >
              {isToggling ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : isCompleted ? (
                <CheckCircle2 className="h-3.5 w-3.5" />
              ) : (
                <Circle className="h-3.5 w-3.5" />
              )}
              <span>{isCompleted ? "Completed" : "Mark complete"}</span>
            </button>

            {isSubtask && (
              <span className="inline-flex items-center space-x-1 px-2 py-0.5 rounded-md bg-secondary text-secondary-foreground text-xs font-medium border border-border/60">
                <ListTree className="h-3 w-3" />
                <span>Subtask</span>
              </span>
            )}
          </div>

          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors cursor-pointer"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Warning Banner if completed */}
        {isCompleted && (
          <div className="flex items-start space-x-2.5 p-3 rounded-lg bg-amber-500/10 border border-amber-500/20 text-amber-700 dark:text-amber-400 text-xs">
            <AlertCircle className="h-4 w-4 shrink-0 mt-0.5" />
            <div>
              <p className="font-medium">
                Completed tasks cannot be edited. Uncomplete first.
              </p>
              {task.completedAt && (
                <p className="text-[11px] opacity-80 mt-0.5">
                  Completed on {new Date(task.completedAt).toLocaleString()}
                </p>
              )}
            </div>
          </div>
        )}

        {/* Edit Form */}
        <form onSubmit={handleSave} className="space-y-4">
          <div className="space-y-1.5">
            <label
              htmlFor="task-title"
              className="text-xs font-medium text-muted-foreground"
            >
              Task Title
            </label>
            <input
              id="task-title"
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              disabled={isCompleted || isSaving}
              className="w-full rounded-md border border-border/80 bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-hidden focus:ring-1 focus:ring-ring disabled:opacity-60 disabled:cursor-not-allowed"
              placeholder="Task title..."
            />
          </div>

          <div className="space-y-1.5">
            <label
              htmlFor="task-description"
              className="text-xs font-medium text-muted-foreground"
            >
              Task Description
            </label>
            <textarea
              id="task-description"
              rows={3}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              disabled={isCompleted || isSaving}
              className="w-full rounded-md border border-border/80 bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-hidden focus:ring-1 focus:ring-ring disabled:opacity-60 disabled:cursor-not-allowed resize-none"
              placeholder="Add optional description..."
            />
          </div>

          {/* Plan Date & Time Section */}
          <div className="space-y-2 pt-2 border-t border-border/60">
            <div className="flex items-center justify-between">
              <label
                htmlFor="task-plan-date"
                className="flex items-center space-x-1.5 text-xs font-medium text-muted-foreground"
              >
                <Calendar className="h-3.5 w-3.5" />
                <span>Plan Date & Time</span>
              </label>

              {!isCompleted && planDate && (
                <button
                  type="button"
                  onClick={() => {
                    setPlanDate("");
                    setPlanTime("");
                  }}
                  className="text-xs text-muted-foreground hover:text-destructive transition-colors cursor-pointer"
                >
                  Clear date (Move to Inbox)
                </button>
              )}
            </div>

            {isCompleted ? (
              <div className="text-xs text-muted-foreground">
                {existingPlanDisplay ? (
                  <span className="inline-flex items-center space-x-1 px-2 py-1 rounded-md bg-secondary text-secondary-foreground border border-border/60">
                    <Clock className="h-3.5 w-3.5" />
                    <span>
                      {existingPlanDisplay.dateLabel}
                      {existingPlanDisplay.timeLabel &&
                        ` · ${existingPlanDisplay.timeLabel}`}
                      {existingPlanDisplay.typeLabel &&
                        ` (${existingPlanDisplay.typeLabel})`}
                    </span>
                  </span>
                ) : (
                  <span className="italic">No plan date (Inbox)</span>
                )}
              </div>
            ) : (
              <div className="space-y-2">
                <div className="flex flex-wrap items-center gap-2">
                  <input
                    id="task-plan-date"
                    type="date"
                    aria-label="Task Plan Date"
                    value={planDate}
                    onChange={(e) => setPlanDate(e.target.value)}
                    disabled={isSaving}
                    className="rounded-md border border-border/80 bg-background px-3 py-1.5 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
                  />

                  {planDate && (
                    <div className="flex items-center space-x-2">
                      <div className="flex items-center space-x-1">
                        <Clock className="h-3.5 w-3.5 text-muted-foreground" />
                        <input
                          type="time"
                          value={planTime}
                          onChange={(e) => setPlanTime(e.target.value)}
                          disabled={isSaving}
                          aria-label="Task Plan Time"
                          className="rounded-md border border-border/80 bg-background px-2.5 py-1.5 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
                        />
                      </div>

                      {planTime && (
                        <select
                          value={planType}
                          onChange={(e) =>
                            setPlanType(e.target.value as TimedPlanType)
                          }
                          disabled={isSaving}
                          aria-label="Task Plan Type"
                          className="rounded-md border border-border/80 bg-background px-2.5 py-1.5 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring cursor-pointer"
                        >
                          <option value="instant">Instant</option>
                          <option value="floating">Floating</option>
                        </select>
                      )}
                    </div>
                  )}
                </div>

                {planTime && (
                  <p className="text-[11px] text-muted-foreground/80">
                    {planType === "instant"
                      ? "Instant: exact moment on Earth stored in UTC; derived on each device."
                      : "Floating: exact clock time on calendar day, the same face in every city."}
                  </p>
                )}
              </div>
            )}
          </div>

          <div className="space-y-1.5">
            <label
              htmlFor="task-priority"
              className="text-xs font-medium text-muted-foreground"
            >
              Task Priority
            </label>
            <select
              id="task-priority"
              value={priority}
              onChange={(e) => setPriority(Number(e.target.value) as Priority)}
              disabled={isCompleted || isSaving}
              className="w-full rounded-md border border-border/80 bg-background px-3 py-2 text-sm text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer"
            >
              {PRIORITY_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>

          {/* Labels Section */}
          {availableLabels.length > 0 && (
            <div className="space-y-1.5">
              <div className="flex items-center space-x-1.5 text-xs font-medium text-muted-foreground">
                <Tag className="h-3 w-3" />
                <span>Labels</span>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {availableLabels
                  .filter(
                    (label) =>
                      !isCompleted || selectedLabels.includes(label.id),
                  )
                  .map((label) => {
                    const isSelected = selectedLabels.includes(label.id);
                    return (
                      <label
                        key={label.id}
                        className={`inline-flex items-center space-x-1.5 px-2.5 py-1 rounded-md text-xs border select-none transition-colors ${
                          isCompleted
                            ? "border-border/80 bg-secondary/80 text-foreground cursor-not-allowed opacity-75"
                            : isSelected
                              ? "border-primary bg-primary/10 text-primary font-medium cursor-pointer"
                              : "border-border/60 bg-background text-muted-foreground hover:bg-secondary/60 cursor-pointer"
                        }`}
                      >
                        <input
                          type="checkbox"
                          checked={isSelected}
                          onChange={() => toggleLabel(label.id)}
                          disabled={isCompleted || isSaving}
                          className="sr-only"
                          aria-label={label.name}
                        />
                        <span
                          className="h-2 w-2 rounded-full shrink-0"
                          style={{ backgroundColor: label.color }}
                        />
                        <span>{label.name}</span>
                      </label>
                    );
                  })}
                {isCompleted && selectedLabels.length === 0 && (
                  <span className="text-xs text-muted-foreground italic">
                    No labels attached
                  </span>
                )}
              </div>
            </div>
          )}

          {error && <p className="text-xs text-destructive">{error}</p>}

          {!isCompleted && (
            <div className="flex justify-end space-x-2 pt-2 border-t border-border/60">
              <button
                type="button"
                onClick={onClose}
                disabled={isSaving}
                className="px-3 py-1.5 rounded-md text-xs font-medium text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={!title.trim() || isSaving}
                className="inline-flex items-center space-x-1 px-3 py-1.5 rounded-md bg-primary text-primary-foreground text-xs font-medium hover:opacity-90 transition-opacity disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer shadow-xs"
              >
                {isSaving && <Loader2 className="h-3 w-3 animate-spin" />}
                <span>Save changes</span>
              </button>
            </div>
          )}
        </form>

        {/* Subtasks Section (One-Level Nesting: only rendered for top-level tasks) */}
        {!isSubtask && (
          <div className="space-y-3 pt-4 border-t border-border/60">
            <div className="flex items-center justify-between">
              <div className="flex items-center space-x-1.5 text-xs font-semibold text-foreground">
                <ListTree className="h-3.5 w-3.5 text-muted-foreground" />
                <span>Subtasks</span>
                {subtasks.length > 0 && (
                  <span className="text-muted-foreground font-normal">
                    ({subtasks.length})
                  </span>
                )}
              </div>
            </div>

            {/* Subtask list */}
            {subtasks.length > 0 && (
              <div className="space-y-1.5" role="list">
                {subtasks.map((st) => {
                  const isStCompleted = st.completedAt !== null;
                  return (
                    <div
                      key={st.id}
                      role="listitem"
                      className="flex items-center justify-between p-2 rounded-md bg-secondary/30 border border-border/50 text-xs hover:border-border transition-colors"
                    >
                      <div className="flex items-center space-x-2 min-w-0 flex-1">
                        <button
                          type="button"
                          onClick={() => onToggleSubtaskComplete?.(st)}
                          aria-label={
                            isStCompleted
                              ? `Uncomplete task ${st.title}`
                              : `Complete task ${st.title}`
                          }
                          className="text-muted-foreground hover:text-emerald-600 dark:hover:text-emerald-400 transition-colors shrink-0 cursor-pointer"
                        >
                          {isStCompleted ? (
                            <CheckCircle2 className="h-3.5 w-3.5 text-emerald-600 dark:text-emerald-400" />
                          ) : (
                            <Circle className="h-3.5 w-3.5" />
                          )}
                        </button>
                        <button
                          type="button"
                          onClick={() => onSelectSubtask?.(st)}
                          className={`truncate text-left cursor-pointer focus:outline-hidden focus:ring-1 focus:ring-ring rounded-xs ${
                            isStCompleted
                              ? "line-through text-muted-foreground"
                              : "text-foreground hover:underline"
                          }`}
                        >
                          {st.title}
                        </button>
                      </div>

                      {st.priority < 4 && (
                        <span className="px-1.5 py-0.5 rounded-xs bg-secondary text-secondary-foreground text-[10px] font-medium shrink-0 ml-2">
                          P{st.priority}
                        </span>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            {/* Add Subtask Form */}
            {!isCompleted && onCreateSubtask && (
              <form onSubmit={handleAddSubtaskSubmit} className="space-y-1.5">
                <div className="flex space-x-2">
                  <input
                    type="text"
                    value={newSubtaskTitle}
                    onChange={(e) => setNewSubtaskTitle(e.target.value)}
                    aria-label="Add a subtask"
                    placeholder="Add subtask..."
                    disabled={isAddingSubtask}
                    className="flex-1 rounded-md border border-border/80 bg-background px-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
                  />
                  <button
                    type="submit"
                    disabled={!newSubtaskTitle.trim() || isAddingSubtask}
                    className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-md bg-secondary text-secondary-foreground text-xs font-medium hover:bg-secondary/80 transition-colors disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
                  >
                    {isAddingSubtask ? (
                      <Loader2 className="h-3 w-3 animate-spin" />
                    ) : (
                      <Plus className="h-3 w-3" />
                    )}
                    <span>Add subtask</span>
                  </button>
                </div>
                {subtaskError && (
                  <p className="text-xs text-destructive">{subtaskError}</p>
                )}
              </form>
            )}
          </div>
        )}

        {/* Comments Section */}
        <div className="space-y-3 pt-4 border-t border-border/60">
          <div className="flex items-center space-x-1.5 text-xs font-semibold text-foreground">
            <MessageSquare className="h-3.5 w-3.5 text-muted-foreground" />
            <span>Comments</span>
            {comments.length > 0 && (
              <span className="text-muted-foreground font-normal">
                ({comments.length})
              </span>
            )}
          </div>

          {/* Comment List */}
          {comments.length > 0 ? (
            <div className="space-y-2 max-h-48 overflow-y-auto pr-1">
              {comments.map((comment) => (
                <div
                  key={comment.id}
                  className="p-2.5 rounded-md bg-secondary/40 border border-border/50 text-xs space-y-1"
                >
                  <p className="text-foreground whitespace-pre-wrap leading-relaxed">
                    {comment.content}
                  </p>
                  <span className="text-[10px] text-muted-foreground block">
                    {new Date(comment.createdAt).toLocaleString()}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground italic">
              No comments attached yet
            </p>
          )}

          {/* Add Comment Form */}
          {onAddComment && (
            <form onSubmit={handleAddCommentSubmit} className="space-y-2">
              <textarea
                rows={2}
                value={newCommentContent}
                onChange={(e) => setNewCommentContent(e.target.value)}
                aria-label="Add a comment"
                placeholder="Add a comment..."
                disabled={isAddingComment}
                className="w-full rounded-md border border-border/80 bg-background px-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-hidden focus:ring-1 focus:ring-ring resize-none"
              />
              {commentError && (
                <p className="text-xs text-destructive">{commentError}</p>
              )}
              <div className="flex justify-end">
                <button
                  type="submit"
                  disabled={!newCommentContent.trim() || isAddingComment}
                  className="inline-flex items-center space-x-1 px-3 py-1.5 rounded-md bg-secondary text-secondary-foreground text-xs font-medium hover:bg-secondary/80 transition-colors disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
                >
                  {isAddingComment && (
                    <Loader2 className="h-3 w-3 animate-spin" />
                  )}
                  <span>Add comment</span>
                </button>
              </div>
            </form>
          )}
        </div>
      </div>
    </div>
  );
}
