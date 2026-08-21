import React, { useState, useCallback, useRef, useMemo } from "react";
import {
  Plus,
  Loader2,
  SlidersHorizontal,
  ChevronUp,
  Tag,
  Calendar,
  Clock,
  Mic,
} from "lucide-react";
import { PRIORITY_OPTIONS, type Priority, type Label } from "../contracts/task";
import type { CreateTaskParams } from "../services/taskService";
import {
  createPlanFromInputs,
  getDeviceLocalDate,
  type TimedPlanType,
} from "../services/temporalService";
import {
  loadUnsubmittedTaskInput,
  saveUnsubmittedTaskInput,
  clearUnsubmittedTaskInput,
} from "../services/offlineShellService";

export interface CreateTaskInputProps {
  readonly onCreateTask: (
    params: CreateTaskParams | string,
    description?: string | null,
    priority?: Priority,
  ) => Promise<void> | void;
  readonly availableLabels?: readonly Label[];
  readonly placeholder?: string;
  readonly className?: string;
  readonly defaultDate?: string | null;
  readonly effectiveDefault?: TimedPlanType;
  readonly onOpenVoiceCapture?: () => void;
}

export function CreateTaskInput({
  onCreateTask,
  availableLabels = [],
  placeholder = "Create a task in Inbox...",
  className = "",
  defaultDate = null,
  effectiveDefault = "instant",
  onOpenVoiceCapture,
}: CreateTaskInputProps): React.JSX.Element {
  const initialUnsubmitted = useMemo(() => loadUnsubmittedTaskInput(), []);
  const [title, setTitle] = useState(initialUnsubmitted?.title ?? "");
  const [description, setDescription] = useState(
    initialUnsubmitted?.description ?? "",
  );
  const [priority, setPriority] = useState<Priority>(4);
  const [selectedLabels, setSelectedLabels] = useState<string[]>([]);
  const [planDate, setPlanDate] = useState<string>(defaultDate ?? "");
  const [planTime, setPlanTime] = useState<string>("");
  const [timedType, setTimedType] = useState<"default" | TimedPlanType>(
    "default",
  );
  const [isExpanded, setIsExpanded] = useState(
    Boolean(initialUnsubmitted?.description),
  );
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleTitleChange = useCallback(
    (newTitle: string) => {
      setTitle(newTitle);
      if (newTitle || description) {
        saveUnsubmittedTaskInput({ title: newTitle, description });
      } else {
        clearUnsubmittedTaskInput();
      }
    },
    [description],
  );

  const handleDescriptionChange = useCallback(
    (newDesc: string) => {
      setDescription(newDesc);
      if (title || newDesc) {
        saveUnsubmittedTaskInput({ title, description: newDesc });
      } else {
        clearUnsubmittedTaskInput();
      }
    },
    [title],
  );

  const lastDefaultDate = useRef(defaultDate);
  if (lastDefaultDate.current !== defaultDate) {
    lastDefaultDate.current = defaultDate;
    setPlanDate(defaultDate ?? "");
  }

  const todayDateStr = useMemo(() => getDeviceLocalDate(new Date()), []);
  const tomorrowDateStr = useMemo(() => {
    const now = new Date();
    const tomorrow = new Date(
      now.getFullYear(),
      now.getMonth(),
      now.getDate() + 1,
    );
    return getDeviceLocalDate(tomorrow);
  }, []);

  const toggleLabel = useCallback((labelId: string) => {
    setSelectedLabels((prev) =>
      prev.includes(labelId)
        ? prev.filter((id) => id !== labelId)
        : [...prev, labelId],
    );
  }, []);

  const handleSetQuickDate = useCallback(
    (type: "none" | "today" | "tomorrow") => {
      if (type === "none") {
        setPlanDate("");
        setPlanTime("");
      } else if (type === "today") {
        setPlanDate(todayDateStr);
      } else if (type === "tomorrow") {
        setPlanDate(tomorrowDateStr);
      }
    },
    [todayDateStr, tomorrowDateStr],
  );

  const handleSubmit = useCallback(
    async (e?: React.FormEvent) => {
      if (e) {
        e.preventDefault();
      }

      const trimmed = title.trim();
      if (!trimmed || isSubmitting) {
        return;
      }

      setIsSubmitting(true);
      setError(null);

      try {
        const desc = description.trim() || null;
        const prio = priority === 4 ? undefined : priority;

        const plan = createPlanFromInputs({
          date: planDate || null,
          time: planTime || null,
          type: timedType === "default" ? null : timedType,
          effectiveDefault,
        });

        if (selectedLabels.length > 0 || plan !== null) {
          await onCreateTask({
            title: trimmed,
            description: desc,
            priority: prio ?? 4,
            plan,
            labels: selectedLabels,
          });
        } else if (desc !== null || prio !== undefined) {
          await onCreateTask(trimmed, desc, prio);
        } else {
          await onCreateTask(trimmed);
        }

        setTitle("");
        setDescription("");
        clearUnsubmittedTaskInput();
        setPriority(4);

        setSelectedLabels([]);
        setPlanDate(defaultDate ?? "");
        setPlanTime("");
        setTimedType("default");
        setIsExpanded(false);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to create task");
      } finally {
        setIsSubmitting(false);
      }
    },
    [
      title,
      description,
      priority,
      selectedLabels,
      planDate,
      planTime,
      timedType,
      effectiveDefault,
      defaultDate,
      isSubmitting,
      onCreateTask,
    ],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        handleSubmit();
      }
    },
    [handleSubmit],
  );

  return (
    <form onSubmit={handleSubmit} className={`w-full space-y-2 ${className}`}>
      <div className="group rounded-lg border border-border/80 bg-card p-2 shadow-xs transition-all focus-within:border-ring focus-within:ring-1 focus-within:ring-ring space-y-2">
        <div className="flex items-center space-x-2 px-1">
          <input
            type="text"
            value={title}
            onChange={(e) => handleTitleChange(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={placeholder}
            disabled={isSubmitting}
            className="flex-1 bg-transparent py-1 text-sm text-foreground placeholder:text-muted-foreground focus:outline-hidden disabled:opacity-50"
            data-testid="create-task-input"
          />

          {onOpenVoiceCapture && (
            <button
              type="button"
              onClick={onOpenVoiceCapture}
              aria-label="Voice capture"
              title="Voice capture (dictate tasks)"
              className="p-1.5 rounded-md text-muted-foreground hover:text-primary hover:bg-secondary transition-colors cursor-pointer"
            >
              <Mic className="h-4 w-4" />
            </button>
          )}

          <button
            type="button"
            onClick={() => setIsExpanded((prev) => !prev)}
            aria-label={isExpanded ? "Hide details" : "Add details"}
            title={isExpanded ? "Hide details" : "Add details"}
            className="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors cursor-pointer"
          >
            {isExpanded ? (
              <ChevronUp className="h-4 w-4" />
            ) : (
              <SlidersHorizontal className="h-4 w-4" />
            )}
          </button>

          <button
            type="submit"
            disabled={!title.trim() || isSubmitting}
            aria-label="Create task"
            className="inline-flex items-center space-x-1 rounded-md bg-primary px-2.5 py-1.5 font-medium text-xs text-primary-foreground shadow-xs transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40 cursor-pointer"
          >
            {isSubmitting ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <Plus className="h-3.5 w-3.5" />
            )}
            <span>Create task</span>
          </button>
        </div>

        {isExpanded && (
          <div className="pt-2 border-t border-border/60 space-y-2.5 px-1 animate-in fade-in-50 duration-100">
            <div>
              <textarea
                aria-label="Task description"
                value={description}
                onChange={(e) => handleDescriptionChange(e.target.value)}
                placeholder="Add description..."
                rows={2}
                disabled={isSubmitting}
                className="w-full rounded-md border border-border/70 bg-background/60 px-2.5 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-hidden focus:ring-1 focus:ring-ring resize-none"
              />
            </div>

            {/* Plan / Date / Time Section */}
            <div className="space-y-2 pt-1 border-t border-border/40">
              <div className="flex items-center space-x-1.5 text-xs font-medium text-muted-foreground">
                <Calendar className="h-3.5 w-3.5" />
                <span>Plan Date & Time:</span>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                {/* Quick Date Buttons */}
                <div className="flex items-center space-x-1">
                  <button
                    type="button"
                    onClick={() => handleSetQuickDate("none")}
                    className={`px-2 py-1 rounded-md text-xs border transition-colors cursor-pointer ${
                      !planDate
                        ? "border-primary bg-primary/10 text-primary font-medium"
                        : "border-border/60 bg-background text-muted-foreground hover:bg-secondary"
                    }`}
                  >
                    Inbox
                  </button>
                  <button
                    type="button"
                    onClick={() => handleSetQuickDate("today")}
                    className={`px-2 py-1 rounded-md text-xs border transition-colors cursor-pointer ${
                      planDate === todayDateStr
                        ? "border-primary bg-primary/10 text-primary font-medium"
                        : "border-border/60 bg-background text-muted-foreground hover:bg-secondary"
                    }`}
                  >
                    Today
                  </button>
                  <button
                    type="button"
                    onClick={() => handleSetQuickDate("tomorrow")}
                    className={`px-2 py-1 rounded-md text-xs border transition-colors cursor-pointer ${
                      planDate && planDate === tomorrowDateStr
                        ? "border-primary bg-primary/10 text-primary font-medium"
                        : "border-border/60 bg-background text-muted-foreground hover:bg-secondary"
                    }`}
                  >
                    Tomorrow
                  </button>
                </div>

                {/* Custom Date Input */}
                <input
                  type="date"
                  value={planDate}
                  onChange={(e) => setPlanDate(e.target.value)}
                  disabled={isSubmitting}
                  aria-label="Plan Date"
                  className="rounded-md border border-border/70 bg-background/60 px-2 py-1 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
                />

                {/* Time Input (only active if date is set) */}
                {planDate && (
                  <div className="flex items-center space-x-1.5 animate-in fade-in-50">
                    <Clock className="h-3.5 w-3.5 text-muted-foreground" />
                    <input
                      type="time"
                      value={planTime}
                      onChange={(e) => setPlanTime(e.target.value)}
                      disabled={isSubmitting}
                      aria-label="Plan Time"
                      className="rounded-md border border-border/70 bg-background/60 px-2 py-1 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
                    />

                    {/* Timed Type Dropdown when time is entered */}
                    {planTime && (
                      <select
                        value={timedType}
                        onChange={(e) =>
                          setTimedType(
                            e.target.value as "default" | TimedPlanType,
                          )
                        }
                        disabled={isSubmitting}
                        aria-label="Plan Type"
                        className="rounded-md border border-border/70 bg-background/60 px-2 py-1 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring cursor-pointer"
                      >
                        <option value="default">
                          Default (
                          {effectiveDefault === "instant"
                            ? "Instant"
                            : "Floating"}
                          )
                        </option>
                        <option value="instant">Instant</option>
                        <option value="floating">Floating</option>
                      </select>
                    )}
                  </div>
                )}
              </div>
            </div>

            {/* Priority Section */}
            <div className="flex items-center space-x-3 pt-1">
              <label
                htmlFor="create-task-priority"
                className="text-xs font-medium text-muted-foreground"
              >
                Priority:
              </label>
              <select
                id="create-task-priority"
                value={priority}
                onChange={(e) =>
                  setPriority(Number(e.target.value) as Priority)
                }
                disabled={isSubmitting}
                className="rounded-md border border-border/70 bg-background/60 px-2 py-1 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring cursor-pointer"
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
              <div className="space-y-1.5 pt-1">
                <div className="flex items-center space-x-1.5 text-xs text-muted-foreground">
                  <Tag className="h-3 w-3" />
                  <span>Labels:</span>
                </div>
                <div className="flex flex-wrap gap-1.5">
                  {availableLabels.map((label) => {
                    const isSelected = selectedLabels.includes(label.id);
                    return (
                      <label
                        key={label.id}
                        className={`inline-flex items-center space-x-1.5 px-2 py-1 rounded-md text-xs border cursor-pointer select-none transition-colors ${
                          isSelected
                            ? "border-primary bg-primary/10 text-primary font-medium"
                            : "border-border/60 bg-background text-muted-foreground hover:bg-secondary/60"
                        }`}
                      >
                        <input
                          type="checkbox"
                          checked={isSelected}
                          onChange={() => toggleLabel(label.id)}
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
                </div>
              </div>
            )}
          </div>
        )}
      </div>
      {error && <p className="px-1 text-xs text-destructive">{error}</p>}
    </form>
  );
}
