import React, { useMemo } from "react";
import { Circle, AlertCircle, Calendar, Clock, Loader2 } from "lucide-react";
import { CreateTaskInput } from "./CreateTaskInput";
import { TaskLabelBadges } from "./TaskLabelBadges";
import type { Task, Label, Priority } from "../contracts/task";
import type { CreateTaskParams } from "../services/taskService";
import {
  filterTodayTasks,
  formatPlanDisplay,
  getDeviceLocalDate,
  isTaskOverdue,
  type TimedPlanType,
} from "../services/temporalService";

export interface TodayViewProps {
  readonly tasks: readonly Task[];
  readonly labels?: readonly Label[];
  readonly onCreateTask: (
    params: CreateTaskParams | string,
    description?: string | null,
    priority?: Priority,
  ) => Promise<void> | void;
  readonly onCompleteTask: (task: Task) => Promise<void> | void;
  readonly onSelectTask: (task: Task) => void;
  readonly isLoading?: boolean;
  readonly now?: Date;
  readonly effectiveDefault?: TimedPlanType;
}

export function TodayView({
  tasks,
  labels = [],
  onCreateTask,
  onCompleteTask,
  onSelectTask,
  isLoading = false,
  now,
  effectiveDefault,
}: TodayViewProps): React.JSX.Element {
  const currentDate = useMemo(() => now || new Date(), [now]);
  const todayDateStr = useMemo(
    () => getDeviceLocalDate(currentDate),
    [currentDate],
  );

  const todayTasks = useMemo(
    () => filterTodayTasks(tasks, currentDate),
    [tasks, currentDate],
  );

  const todayDateFormatted = useMemo(() => {
    return currentDate.toLocaleDateString(undefined, {
      weekday: "long",
      month: "short",
      day: "numeric",
    });
  }, [currentDate]);

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      {/* Header */}
      <header className="h-14 border-b border-border/70 flex items-center justify-between px-8 bg-background/50 backdrop-blur-xs">
        <div className="flex items-center space-x-3">
          <Calendar className="h-5 w-5 text-emerald-600 dark:text-emerald-400" />
          <div>
            <div className="flex items-center space-x-2">
              <h2 className="text-base font-semibold tracking-tight">Today</h2>
              <span className="text-xs text-muted-foreground font-medium">
                {todayDateFormatted}
              </span>
            </div>
          </div>
          {todayTasks.length > 0 && (
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
              {todayTasks.length}
            </span>
          )}
        </div>
      </header>

      {/* Main Content Area */}
      <div className="flex-1 overflow-y-auto px-8 py-6 space-y-6">
        {/* Quick Task Creation with default date set to Today */}
        <div className="max-w-2xl">
          <CreateTaskInput
            onCreateTask={onCreateTask}
            availableLabels={labels}
            placeholder="Add a task for Today..."
            defaultDate={todayDateStr}
            effectiveDefault={effectiveDefault}
          />
        </div>

        {/* Task List / Empty State */}
        {isLoading ? (
          <div className="flex items-center justify-center py-16 text-muted-foreground">
            <Loader2 className="h-5 w-5 animate-spin mr-2" />
            <span className="text-sm">Loading today's tasks...</span>
          </div>
        ) : todayTasks.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-center">
            <div className="h-12 w-12 rounded-full bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 flex items-center justify-center mb-3">
              <Calendar className="h-6 w-6" />
            </div>
            <h3 className="text-base font-medium tracking-tight">
              No tasks for Today
            </h3>
            <p className="text-sm text-muted-foreground mt-1 max-w-sm">
              Your task space is clear. Plan a task for today or relax.
            </p>
          </div>
        ) : (
          <div className="space-y-2 max-w-2xl" role="list">
            {todayTasks.map((task) => {
              const display = formatPlanDisplay(task.plan, {
                now: currentDate,
              });
              const overdue = isTaskOverdue(task, currentDate);

              return (
                <div
                  key={task.id}
                  role="listitem"
                  tabIndex={0}
                  onClick={() => onSelectTask(task)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      if (e.target === e.currentTarget) {
                        e.preventDefault();
                        onSelectTask(task);
                      }
                    }
                  }}
                  className={`group relative flex items-start justify-between p-3.5 rounded-lg border bg-card text-card-foreground shadow-xs transition-all hover:border-border cursor-pointer focus:outline-hidden focus-visible:ring-1 focus-visible:ring-ring ${
                    overdue
                      ? "border-amber-500/30 bg-amber-500/5 hover:bg-amber-500/10"
                      : "border-border/60 hover:bg-secondary/40"
                  }`}
                >
                  <div className="flex items-start space-x-3 min-w-0 flex-1 mr-3">
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        onCompleteTask(task);
                      }}
                      aria-label={`Complete task ${task.title}`}
                      className="mt-0.5 rounded-full text-muted-foreground hover:text-emerald-600 dark:hover:text-emerald-400 transition-colors shrink-0 cursor-pointer"
                    >
                      <Circle className="h-4 w-4" />
                    </button>

                    <div className="space-y-1 min-w-0 flex-1">
                      <div className="flex items-center space-x-2">
                        <p className="text-sm font-medium leading-snug tracking-tight text-foreground truncate">
                          {task.title}
                        </p>
                      </div>

                      {task.description && (
                        <p className="text-xs text-muted-foreground line-clamp-2 leading-relaxed">
                          {task.description}
                        </p>
                      )}

                      <div className="flex flex-wrap items-center gap-1.5 pt-1">
                        {/* Priority Badge */}
                        {task.priority < 4 && (
                          <span
                            className={`inline-flex items-center px-1.5 py-0.5 rounded-xs text-[10px] font-semibold uppercase tracking-wider ${
                              task.priority === 1
                                ? "bg-red-500/15 text-red-700 dark:text-red-400 border border-red-500/20"
                                : task.priority === 2
                                  ? "bg-amber-500/15 text-amber-700 dark:text-amber-400 border border-amber-500/20"
                                  : "bg-blue-500/15 text-blue-700 dark:text-blue-400 border border-blue-500/20"
                            }`}
                          >
                            P{task.priority}
                          </span>
                        )}

                        {/* Plan / Time Badge */}
                        {display && (
                          <span
                            className={`inline-flex items-center space-x-1 px-1.5 py-0.5 rounded-xs text-[10px] font-medium border ${
                              overdue
                                ? "bg-red-500/10 border-red-500/20 text-red-600 dark:text-red-400"
                                : "bg-secondary text-secondary-foreground border-border/60"
                            }`}
                          >
                            {overdue ? (
                              <AlertCircle className="h-3 w-3 shrink-0" />
                            ) : (
                              <Clock className="h-3 w-3 shrink-0 text-muted-foreground" />
                            )}
                            <span>
                              {overdue
                                ? `${display.dateLabel} (Overdue)`
                                : display.dateLabel}
                              {display.timeLabel && ` · ${display.timeLabel}`}
                              {display.typeLabel && ` (${display.typeLabel})`}
                            </span>
                          </span>
                        )}

                        {/* Labels Badges */}
                        <TaskLabelBadges
                          labelIds={task.labels}
                          labels={labels}
                        />
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
