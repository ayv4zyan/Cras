import React, { useState, useCallback } from "react";
import { Plus, Loader2 } from "lucide-react";

export interface CreateTaskInputProps {
  readonly onCreateTask: (title: string) => Promise<void> | void;
  readonly placeholder?: string;
  readonly className?: string;
}

export function CreateTaskInput({
  onCreateTask,
  placeholder = "Create a task in Inbox...",
  className = "",
}: CreateTaskInputProps): React.JSX.Element {
  const [title, setTitle] = useState("");
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
        await onCreateTask(trimmed);
        setTitle("");
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to create task");
      } finally {
        setIsSubmitting(false);
      }
    },
    [title, isSubmitting, onCreateTask],
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
      <div className="group relative flex items-center rounded-lg border border-border/80 bg-card px-3 py-1.5 shadow-xs transition-all focus-within:border-ring focus-within:ring-1 focus-within:ring-ring">
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
          type="submit"
          disabled={!title.trim() || isSubmitting}
          aria-label="Create task"
          className="inline-flex items-center space-x-1 rounded-md bg-primary px-2.5 py-1 font-medium text-xs text-primary-foreground shadow-xs transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40 cursor-pointer"
        >
          {isSubmitting ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
          ) : (
            <Plus className="h-3.5 w-3.5" />
          )}
          <span>Create task</span>
        </button>
      </div>
      {error && <p className="px-1 text-xs text-destructive">{error}</p>}
    </form>
  );
}
