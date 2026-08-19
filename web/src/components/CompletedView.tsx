import React from "react";
import { CheckCircle2, CheckSquare } from "lucide-react";
import { TaskLabelBadges } from "./TaskLabelBadges";
import type { Task, Label } from "../contracts/task";

export interface CompletedViewProps {
  readonly tasks: readonly Task[];
  readonly labels?: readonly Label[];
  readonly onUncompleteTask: (task: Task) => Promise<void> | void;
  readonly onSelectTask?: (task: Task) => void;
  readonly isLoading?: boolean;
}

export function CompletedView({
  tasks,
  labels = [],
  onUncompleteTask,
  onSelectTask,
  isLoading = false,
}: CompletedViewProps): React.JSX.Element {
  return (
    <div className="flex-1 flex flex-col h-full overflow-y-auto">
      {/* Header */}
      <header className="h-14 border-b border-border/70 flex items-center justify-between px-8 bg-background/50 backdrop-blur-xs shrink-0">
        <div className="flex items-center space-x-3">
          <h2 className="text-lg font-semibold tracking-tight text-foreground">
            Completed
          </h2>
          <span className="inline-flex items-center justify-center px-2 py-0.5 rounded-full text-xs font-medium bg-secondary text-secondary-foreground">
            {tasks.length}
          </span>
        </div>
      </header>

      {/* Main Content */}
      <div className="flex-1 p-8 max-w-3xl w-full mx-auto space-y-6">
        {isLoading && tasks.length === 0 ? (
          <div className="flex items-center justify-center py-16 text-muted-foreground text-sm">
            Loading completed tasks...
          </div>
        ) : tasks.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-center space-y-4">
            <div className="w-12 h-12 rounded-full bg-secondary/80 flex items-center justify-center text-muted-foreground">
              <CheckSquare className="h-6 w-6" />
            </div>

            <div className="space-y-1.5 max-w-sm">
              <h3 className="text-base font-medium tracking-tight text-foreground">
                No completed tasks yet
              </h3>
              <p className="text-sm text-muted-foreground leading-relaxed">
                Completed tasks will be retained here and listed newest-first.
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
                <div className="flex items-center space-x-3 min-w-0">
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onUncompleteTask(task);
                    }}
                    aria-label={`Uncomplete task ${task.title}`}
                    title="Uncomplete task"
                    className="text-emerald-600 dark:text-emerald-400 hover:text-muted-foreground transition-colors shrink-0 cursor-pointer"
                  >
                    <CheckCircle2 className="h-4 w-4" />
                  </button>
                  <div className="min-w-0 space-y-0.5">
                    <span className="text-sm font-medium text-muted-foreground line-through truncate block">
                      {task.title}
                    </span>
                    {task.description && (
                      <p className="text-xs text-muted-foreground/80 truncate">
                        {task.description}
                      </p>
                    )}
                    <TaskLabelBadges labelIds={task.labels} labels={labels} />
                  </div>
                </div>

                <div className="flex items-center space-x-2 shrink-0 text-xs text-muted-foreground">
                  {task.priority < 4 && (
                    <span className="px-1.5 py-0.5 rounded-sm bg-secondary text-secondary-foreground font-medium text-[11px]">
                      P{task.priority}
                    </span>
                  )}
                  {task.completedAt && (
                    <span className="text-[11px] text-muted-foreground hidden sm:inline">
                      {new Date(task.completedAt).toLocaleDateString()}
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
