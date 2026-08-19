import React, { useState, useEffect, useCallback } from "react";
import { X, CheckCircle2, Circle, AlertCircle, Loader2 } from "lucide-react";
import { PRIORITY_OPTIONS, type Priority, type Task } from "../contracts/task";
import type { UpdateTaskParams } from "../services/taskService";

export interface TaskDetailModalProps {
  readonly task: Task | null;
  readonly isOpen: boolean;
  readonly onClose: () => void;
  readonly onSave: (params: UpdateTaskParams) => Promise<void> | void;
  readonly onToggleComplete: (task: Task) => Promise<void> | void;
}

export function TaskDetailModal({
  task,
  isOpen,
  onClose,
  onSave,
  onToggleComplete,
}: TaskDetailModalProps): React.JSX.Element | null {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState<Priority>(4);
  const [isSaving, setIsSaving] = useState(false);
  const [isToggling, setIsToggling] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (task) {
      setTitle(task.title);
      setDescription(task.description ?? "");
      setPriority(task.priority);
      setError(null);
    }
  }, [task]);

  const isCompleted = task?.completedAt !== null;

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
        await onSave({
          id: task.id,
          title: trimmedTitle,
          description: description.trim() || null,
          priority,
          expectedVersion: task.version,
        });
        onClose();
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to update task");
      } finally {
        setIsSaving(false);
      }
    },
    [task, isCompleted, title, description, priority, onSave, onClose],
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

  if (!isOpen || !task) {
    return null;
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Task details"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-xs p-4"
    >
      <div className="relative w-full max-w-lg rounded-xl border border-border bg-card p-6 shadow-xl space-y-5 text-foreground animate-in fade-in zoom-in-95 duration-150">
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
      </div>
    </div>
  );
}
