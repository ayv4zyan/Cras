import React, { useState, useCallback, useEffect, useRef } from "react";
import { X, Plus, Trash2, Edit2, Check, Loader2, Tag } from "lucide-react";
import { LABEL_COLORS, type Label } from "../contracts/task";
import type {
  CreateLabelParams,
  UpdateLabelParams,
} from "../services/labelService";

export interface LabelManagerModalProps {
  readonly isOpen: boolean;
  readonly labels: readonly Label[];
  readonly onClose: () => void;
  readonly onCreateLabel: (params: CreateLabelParams) => Promise<void>;
  readonly onUpdateLabel: (params: UpdateLabelParams) => Promise<void>;
  readonly onDeleteLabel: (labelId: string) => Promise<void>;
}

export function LabelManagerModal({
  isOpen,
  labels,
  onClose,
  onCreateLabel,
  onUpdateLabel,
  onDeleteLabel,
}: LabelManagerModalProps): React.JSX.Element | null {
  const [newName, setNewName] = useState("");
  const [newColor, setNewColor] = useState(LABEL_COLORS[0].value);
  const [isCreating, setIsCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState("");
  const [editColor, setEditColor] = useState("");
  const [isUpdating, setIsUpdating] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isOpen) {
      setCreateError(null);
      setEditError(null);
      setDeleteError(null);
      setEditingId(null);
      return;
    }

    const previouslyFocused = document.activeElement as HTMLElement | null;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key === "Tab" && dialogRef.current) {
        const focusableElements =
          dialogRef.current.querySelectorAll<HTMLElement>(
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

    const focusable = dialogRef.current?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    if (focusable && focusable.length > 0) {
      focusable[0]?.focus();
    } else {
      dialogRef.current?.focus();
    }

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      previouslyFocused?.focus();
    };
  }, [isOpen, onClose]);

  const handleStartEdit = useCallback((label: Label) => {
    setEditingId(label.id);
    setEditName(label.name);
    setEditColor(label.color);
    setEditError(null);
  }, []);

  const handleCancelEdit = useCallback(() => {
    setEditingId(null);
    setEditName("");
    setEditColor("");
    setEditError(null);
  }, []);

  const handleCreate = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      const trimmed = newName.trim();
      if (!trimmed) {
        setCreateError("Label name cannot be empty");
        return;
      }

      setIsCreating(true);
      setCreateError(null);
      try {
        await onCreateLabel({
          name: trimmed,
          color: newColor,
        });
        setNewName("");
      } catch (err) {
        setCreateError(
          err instanceof Error ? err.message : "Failed to create label",
        );
      } finally {
        setIsCreating(false);
      }
    },
    [newName, newColor, onCreateLabel],
  );

  const handleSaveEdit = useCallback(
    async (labelId: string) => {
      const trimmed = editName.trim();
      if (!trimmed) {
        setEditError("Label name cannot be empty");
        return;
      }

      setIsUpdating(true);
      setEditError(null);
      try {
        await onUpdateLabel({
          id: labelId,
          name: trimmed,
          color: editColor,
        });
        setEditingId(null);
      } catch (err) {
        setEditError(
          err instanceof Error ? err.message : "Failed to update label",
        );
      } finally {
        setIsUpdating(false);
      }
    },
    [editName, editColor, onUpdateLabel],
  );

  const handleDelete = useCallback(
    async (labelId: string) => {
      setDeletingId(labelId);
      setDeleteError(null);
      try {
        await onDeleteLabel(labelId);
      } catch (err) {
        setDeleteError(
          err instanceof Error ? err.message : "Failed to delete label",
        );
      } finally {
        setDeletingId(null);
      }
    },
    [onDeleteLabel],
  );

  if (!isOpen) return null;

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-label="Manage Labels"
      tabIndex={-1}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-xs p-4 outline-hidden"
    >
      <div className="relative w-full max-w-lg rounded-xl border border-border bg-card p-6 shadow-xl space-y-5 text-foreground animate-in fade-in zoom-in-95 duration-150">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border/60 pb-3">
          <div className="flex items-center space-x-2">
            <Tag className="h-5 w-5 text-primary" />
            <h2 className="text-base font-semibold tracking-tight">
              Manage Labels
            </h2>
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

        {/* Create Label Form */}
        <form
          onSubmit={handleCreate}
          className="space-y-3 p-3 rounded-lg bg-secondary/30 border border-border/60"
        >
          <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
            Create new label
          </h3>

          <div className="flex items-center space-x-2">
            <input
              type="text"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder="New label name..."
              disabled={isCreating}
              className="flex-1 rounded-md border border-border/80 bg-background px-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-hidden focus:ring-1 focus:ring-ring disabled:opacity-60"
            />

            <button
              type="submit"
              disabled={!newName.trim() || isCreating}
              className="inline-flex items-center space-x-1 px-3 py-1.5 rounded-md bg-primary text-primary-foreground text-xs font-medium hover:opacity-90 transition-opacity disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer shrink-0 shadow-xs"
            >
              {isCreating ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <Plus className="h-3.5 w-3.5" />
              )}
              <span>Add label</span>
            </button>
          </div>

          {/* Color Picker Palette */}
          <div className="space-y-1">
            <span className="text-[11px] text-muted-foreground">Color</span>
            <div className="flex items-center flex-wrap gap-1.5">
              {LABEL_COLORS.map((col) => (
                <button
                  key={col.value}
                  type="button"
                  onClick={() => setNewColor(col.value)}
                  aria-label={`Select ${col.name} color`}
                  title={col.name}
                  className={`h-5 w-5 rounded-full transition-transform cursor-pointer flex items-center justify-center ${
                    newColor === col.value
                      ? "ring-2 ring-primary ring-offset-2 scale-110"
                      : "hover:scale-105"
                  }`}
                  style={{ backgroundColor: col.value }}
                >
                  {newColor === col.value && (
                    <Check className="h-3 w-3 text-white drop-shadow-xs" />
                  )}
                </button>
              ))}
            </div>
          </div>

          {createError && (
            <p className="text-xs text-destructive">{createError}</p>
          )}
        </form>

        {/* Existing Labels List */}
        <div className="space-y-2">
          <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
            Your labels ({labels.length})
          </h3>

          {deleteError && (
            <p className="text-xs text-destructive">{deleteError}</p>
          )}

          <div className="max-h-60 overflow-y-auto space-y-1.5 pr-1">
            {labels.length === 0 ? (
              <p className="text-xs text-muted-foreground py-4 text-center">
                No labels created yet. Create labels to organize your tasks.
              </p>
            ) : (
              labels.map((label) => {
                const isEditing = editingId === label.id;
                const isDeleting = deletingId === label.id;

                if (isEditing) {
                  return (
                    <div
                      key={label.id}
                      className="p-2.5 rounded-lg border border-primary/40 bg-secondary/40 space-y-2"
                    >
                      <div className="flex items-center space-x-2">
                        <input
                          type="text"
                          value={editName}
                          onChange={(e) => setEditName(e.target.value)}
                          disabled={isUpdating}
                          className="flex-1 rounded-md border border-border/80 bg-background px-2.5 py-1 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
                        />
                        <button
                          type="button"
                          onClick={() => handleSaveEdit(label.id)}
                          disabled={!editName.trim() || isUpdating}
                          aria-label="Save label"
                          className="p-1 rounded-md bg-primary text-primary-foreground text-xs hover:opacity-90 transition-opacity cursor-pointer disabled:opacity-50"
                        >
                          {isUpdating ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          ) : (
                            <Check className="h-3.5 w-3.5" />
                          )}
                        </button>
                        <button
                          type="button"
                          onClick={handleCancelEdit}
                          disabled={isUpdating}
                          aria-label="Cancel editing"
                          className="p-1 rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors cursor-pointer"
                        >
                          <X className="h-3.5 w-3.5" />
                        </button>
                      </div>

                      {/* Recolor palette */}
                      <div className="flex items-center flex-wrap gap-1">
                        {LABEL_COLORS.map((col) => (
                          <button
                            key={col.value}
                            type="button"
                            onClick={() => setEditColor(col.value)}
                            aria-label={`Select ${col.name} color`}
                            title={col.name}
                            className={`h-4 w-4 rounded-full transition-transform cursor-pointer flex items-center justify-center ${
                              editColor === col.value
                                ? "ring-2 ring-primary ring-offset-1 scale-110"
                                : "hover:scale-105"
                            }`}
                            style={{ backgroundColor: col.value }}
                          >
                            {editColor === col.value && (
                              <Check className="h-2.5 w-2.5 text-white" />
                            )}
                          </button>
                        ))}
                      </div>

                      {editError && (
                        <p className="text-xs text-destructive">{editError}</p>
                      )}
                    </div>
                  );
                }

                return (
                  <div
                    key={label.id}
                    className="flex items-center justify-between p-2 rounded-md border border-border/60 bg-card hover:bg-secondary/30 transition-colors"
                  >
                    <div className="flex items-center space-x-2 min-w-0">
                      <span
                        className="h-3 w-3 rounded-full shrink-0"
                        style={{ backgroundColor: label.color }}
                      />
                      <span className="text-xs font-medium text-foreground truncate">
                        {label.name}
                      </span>
                    </div>

                    <div className="flex items-center space-x-1 shrink-0">
                      <button
                        type="button"
                        onClick={() => handleStartEdit(label)}
                        aria-label={`Edit label ${label.name}`}
                        title="Edit label"
                        className="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors cursor-pointer"
                      >
                        <Edit2 className="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => handleDelete(label.id)}
                        disabled={isDeleting}
                        aria-label={`Delete label ${label.name}`}
                        title="Delete label"
                        className="p-1 rounded-md text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors cursor-pointer disabled:opacity-50"
                      >
                        {isDeleting ? (
                          <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        ) : (
                          <Trash2 className="h-3.5 w-3.5" />
                        )}
                      </button>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>

        {/* Footer */}
        <div className="flex justify-end pt-2 border-t border-border/60">
          <button
            type="button"
            onClick={onClose}
            className="px-3 py-1.5 rounded-md text-xs font-medium text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors cursor-pointer"
          >
            Done
          </button>
        </div>
      </div>
    </div>
  );
}
