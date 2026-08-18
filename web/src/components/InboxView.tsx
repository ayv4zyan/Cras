import React from "react";
import { Layers, Circle } from "lucide-react";
import type { Task } from "../contracts/task";
import { CreateTaskInput } from "./CreateTaskInput";

export interface InboxViewProps {
  readonly tasks: readonly Task[];
  readonly onCreateTask: (title: string) => Promise<void> | void;
  readonly isLoading?: boolean;
}

export function InboxView({
  tasks,
  onCreateTask,
  isLoading = false,
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
          <CreateTaskInput onCreateTask={onCreateTask} />
        </div>

        {/* Task List / Empty State */}
        {isLoading && tasks.length === 0 ? (
          <div className="flex items-center justify-center py-16 text-muted-foreground text-sm">
            Loading tasks...
          </div>
        ) : tasks.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-center space-y-4">
            <div className="w-12 h-12 rounded-full bg-secondary/80 flex items-center justify-center text-muted-foreground">
              <Layers className="h-6 w-6" />
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
                className="group flex items-center justify-between p-3.5 rounded-lg border border-border/70 bg-card hover:border-border transition-colors shadow-xs"
              >
                <div className="flex items-center space-x-3 min-w-0">
                  <button
                    type="button"
                    aria-label={`Task ${task.title}`}
                    className="text-muted-foreground hover:text-foreground transition-colors shrink-0 cursor-pointer"
                  >
                    <Circle className="h-4 w-4" />
                  </button>
                  <span className="text-sm font-medium text-foreground truncate">
                    {task.title}
                  </span>
                </div>

                <div className="flex items-center space-x-2 shrink-0 text-xs text-muted-foreground">
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
