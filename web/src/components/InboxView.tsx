import React from "react";
import { CheckSquare, Circle } from "lucide-react";
import { CreateTaskInput } from "./CreateTaskInput";
import { TaskLabelBadges } from "./TaskLabelBadges";
import { type Priority, type Task, type Label } from "../contracts/task";
import type { CreateTaskParams } from "../services/taskService";
import type { TimedPlanType } from "../services/temporalService";

export interface InboxViewProps {
  readonly tasks: readonly Task[];
  readonly labels?: readonly Label[];
  readonly onCreateTask: (
    params: CreateTaskParams | string,
    description?: string | null,
    priority?: Priority,
  ) => Promise<void> | void;
  readonly onCompleteTask?: (task: Task) => Promise<void> | void;
  readonly onSelectTask?: (task: Task) => void;
  readonly isLoading?: boolean;
  readonly effectiveDefault?: TimedPlanType;
  readonly onOpenVoiceCapture?: () => void;
}

export function InboxView({
  tasks,
  labels = [],
  onCreateTask,
  onCompleteTask,
  onSelectTask,
  isLoading = false,
  effectiveDefault,
  onOpenVoiceCapture,
}: InboxViewProps): React.JSX.Element {
  return (
    <div className="flex-1 flex flex-col h-full overflow-y-auto">
      {/* Header */}
      <header className="h-14 border-b border-border/70 flex items-center justify-between px-8 bg-background/50 backdrop-blur-xs shrink-0">
        <div className="flex items-center space-x-3">
          <h2 className="text-lg font-semibold tracking-tight text-foreground">
            Inbox
          </h2>
          <span className="inline-flex items-center justify-center px-2 py-0.5 rounded-full text-xs font-medium bg-secondary text-secondary-foreground">
            {tasks.length}
          </span>
        </div>
      </header>

      {/* Main Inbox Body */}
      <div className="flex-1 p-8 max-w-3xl w-full mx-auto space-y-6">
        {/* Quick Task Creation */}
        <div className="space-y-1">
          <CreateTaskInput
            onCreateTask={onCreateTask}
            availableLabels={labels}
            effectiveDefault={effectiveDefault}
            onOpenVoiceCapture={onOpenVoiceCapture}
          />
        </div>

        {/* Task List / Empty State */}
        {isLoading && tasks.length === 0 ? (
          <div className="flex items-center justify-center py-16 text-muted-foreground text-sm">
            Loading tasks...
          </div>
        ) : tasks.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-center space-y-4">
            <div className="w-12 h-12 rounded-full bg-secondary/80 flex items-center justify-center text-muted-foreground">
              <CheckSquare className="h-6 w-6" />
            </div>

            <div className="space-y-1.5 max-w-sm">
              <h3 className="text-base font-medium tracking-tight text-foreground">
                No tasks in Inbox
              </h3>
              <p className="text-sm text-muted-foreground leading-relaxed">
                Your task space is clear. Capture a new task or use Voice
                capture to propose drafts.
              </p>
            </div>
          </div>
        ) : (
          <div className="space-y-2" role="list">
            {tasks.map((task) => (
              <div
                key={task.id}
                data-testid={`task-item-${task.id}`}
                role="listitem"
                onClick={() => onSelectTask?.(task)}
                className="group flex items-center justify-between p-3.5 rounded-lg border border-border/70 bg-card hover:border-border transition-colors shadow-xs cursor-pointer"
              >
                <div className="flex items-center space-x-3 min-w-0 flex-1">
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      void Promise.resolve(onCompleteTask?.(task)).catch(
                        () => {},
                      );
                    }}
                    aria-label={`Complete task ${task.title}`}
                    title="Complete task"
                    className="text-muted-foreground hover:text-emerald-600 dark:hover:text-emerald-400 transition-colors shrink-0 cursor-pointer"
                  >
                    <Circle className="h-4 w-4" />
                  </button>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onSelectTask?.(task);
                    }}
                    aria-label={`Select task ${task.title}`}
                    className="min-w-0 space-y-0.5 text-left flex-1 bg-transparent border-0 p-0 focus:outline-hidden focus-visible:ring-1 focus-visible:ring-ring rounded-xs cursor-pointer"
                  >
                    <span className="text-sm font-medium text-foreground truncate block">
                      {task.title}
                    </span>
                    {task.description && (
                      <p className="text-xs text-muted-foreground truncate">
                        {task.description}
                      </p>
                    )}
                    <TaskLabelBadges labelIds={task.labels} labels={labels} />
                  </button>
                </div>

                <div className="flex items-center space-x-2 shrink-0 text-xs text-muted-foreground ml-2">
                  {task.priority < 4 && (
                    <span className="px-1.5 py-0.5 rounded-sm bg-secondary text-secondary-foreground font-medium text-[11px]">
                      P{task.priority}
                    </span>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
