import React, { useState, useCallback } from "react";
import { Plus, Loader2, SlidersHorizontal, ChevronUp } from "lucide-react";
import { PRIORITY_OPTIONS, type Priority } from "../contracts/task";
import type { CreateTaskParams } from "../services/taskService";

export interface CreateTaskInputProps {
  readonly onCreateTask: (
    params: CreateTaskParams | string,
    description?: string | null,
    priority?: Priority,
  ) => Promise<void> | void;
  readonly placeholder?: string;
  readonly className?: string;
}

export function CreateTaskInput({
  onCreateTask,
  placeholder = "Create a task in Inbox...",
  className = "",
}: CreateTaskInputProps): React.JSX.Element {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState<Priority>(4);
  const [isExpanded, setIsExpanded] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
        if (desc !== null || prio !== undefined) {
          await onCreateTask(trimmed, desc, prio);
        } else {
          await onCreateTask(trimmed);
        }
        setTitle("");
        setDescription("");
        setPriority(4);
        setIsExpanded(false);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to create task");
      } finally {
        setIsSubmitting(false);
      }
    },
    [title, description, priority, isSubmitting, onCreateTask],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === "Enter" && !e.shiftKey && !isExpanded) {
        e.preventDefault();
        handleSubmit();
      }
    },
    [handleSubmit, isExpanded],
  );

  return (
    <form onSubmit={handleSubmit} className={`w-full space-y-2 ${className}`}>
      <div className="group rounded-lg border border-border/80 bg-card p-2 shadow-xs transition-all focus-within:border-ring focus-within:ring-1 focus-within:ring-ring space-y-2">
        <div className="flex items-center space-x-2 px-1">
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={placeholder}
            disabled={isSubmitting}
            className="flex-1 bg-transparent py-1 text-sm text-foreground placeholder:text-muted-foreground focus:outline-hidden disabled:opacity-50"
            data-testid="create-task-input"
          />

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
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Add description..."
                rows={2}
                disabled={isSubmitting}
                className="w-full rounded-md border border-border/70 bg-background/60 px-2.5 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-hidden focus:ring-1 focus:ring-ring resize-none"
              />
            </div>

            <div className="flex items-center space-x-3">
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
          </div>
        )}
      </div>
      {error && <p className="px-1 text-xs text-destructive">{error}</p>}
    </form>
  );
}
