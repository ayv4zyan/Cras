import React, { useMemo } from "react";
import {
  Circle,
  AlertCircle,
  CalendarDays,
  Clock,
  Loader2,
} from "lucide-react";
import { TaskLabelBadges } from "./TaskLabelBadges";
import type { Task, Label } from "../contracts/task";
import {
  filterUpcomingTasks,
  formatPlanDisplay,
} from "../services/temporalService";

export interface UpcomingViewProps {
  readonly tasks: readonly Task[];
  readonly labels?: readonly Label[];
  readonly onCompleteTask: (task: Task) => Promise<void> | void;
  readonly onSelectTask: (task: Task) => void;
  readonly isLoading?: boolean;
  readonly now?: Date;
}

export function UpcomingView({
  tasks,
  labels = [],
  onCompleteTask,
  onSelectTask,
  isLoading = false,
  now,
}: UpcomingViewProps): React.JSX.Element {
  const currentDate = useMemo(() => now || new Date(), [now]);

  const { overdue, groups } = useMemo(
    () => filterUpcomingTasks(tasks, currentDate),
    [tasks, currentDate],
  );

  const totalUpcomingCount =
    overdue.length + groups.reduce((acc, g) => acc + g.tasks.length, 0);

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      {/* Header */}
      <header className="h-14 border-b border-border/70 flex items-center justify-between px-8 bg-background/50 backdrop-blur-xs">
        <div className="flex items-center space-x-3">
          <CalendarDays className="h-5 w-5 text-blue-600 dark:text-blue-400" />
          <h2 className="text-base font-semibold tracking-tight">Upcoming</h2>
          {totalUpcomingCount > 0 && (
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-blue-500/10 text-blue-600 dark:text-blue-400">
              {totalUpcomingCount}
            </span>
          )}
        </div>
      </header>

      {/* Main Content Area */}
      <div className="flex-1 overflow-y-auto px-8 py-6 space-y-6">
        {isLoading ? (
          <div className="flex items-center justify-center py-16 text-muted-foreground">
            <Loader2 className="h-5 w-5 animate-spin mr-2" />
            <span className="text-sm">Loading upcoming tasks...</span>
          </div>
        ) : totalUpcomingCount === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-center">
            <div className="h-12 w-12 rounded-full bg-blue-500/10 text-blue-600 dark:text-blue-400 flex items-center justify-center mb-3">
              <CalendarDays className="h-6 w-6" />
            </div>
            <h3 className="text-base font-medium tracking-tight">
              No upcoming tasks
            </h3>
            <p className="text-sm text-muted-foreground mt-1 max-w-sm">
              Your task space is clear. Plan a future task to see it here.
            </p>
          </div>
        ) : (
          <div className="space-y-6 max-w-2xl">
            {/* Overdue Strip at top */}
            {overdue.length > 0 && (
              <div className="space-y-2 rounded-lg border border-red-500/30 bg-red-500/5 p-4">
                <div className="flex items-center space-x-2 text-xs font-semibold uppercase tracking-wider text-red-600 dark:text-red-400">
                  <AlertCircle className="h-4 w-4" />
                  <span>Overdue ({overdue.length})</span>
                </div>
                <div
                  className="space-y-2 pt-1"
                  role="list"
                  aria-label="Overdue tasks"
                >
                  {overdue.map((task) => {
                    const display = formatPlanDisplay(task.plan, {
                      now: currentDate,
                    });
                    return (
                      <div
                        key={task.id}
                        role="listitem"
                        className="group flex items-start justify-between p-3 rounded-md bg-card border border-red-500/20 text-card-foreground shadow-xs hover:border-red-500/40 transition-all"
                      >
                        <div className="flex items-start space-x-3 min-w-0 flex-1 mr-2">
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

                          <button
                            type="button"
                            onClick={() => onSelectTask(task)}
                            aria-label={`Select task ${task.title}`}
                            className="space-y-1 min-w-0 flex-1 text-left bg-transparent border-0 p-0 focus:outline-hidden focus-visible:ring-1 focus-visible:ring-ring rounded-xs cursor-pointer"
                          >
                            <p className="text-sm font-medium leading-snug text-foreground truncate">
                              {task.title}
                            </p>

                            {task.description && (
                              <p className="text-xs text-muted-foreground line-clamp-1">
                                {task.description}
                              </p>
                            )}

                            <div className="flex flex-wrap items-center gap-1.5 pt-0.5">
                              {task.priority < 4 && (
                                <span className="px-1.5 py-0.5 rounded-xs text-[10px] font-semibold bg-red-500/15 text-red-700 dark:text-red-400 border border-red-500/20 uppercase">
                                  P{task.priority}
                                </span>
                              )}

                              {display && (
                                <span className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded-xs text-[10px] font-medium bg-red-500/10 border border-red-500/20 text-red-600 dark:text-red-400">
                                  <Clock className="h-3 w-3 shrink-0" />
                                  <span>
                                    {display.dateLabel}
                                    {display.timeLabel &&
                                      ` · ${display.timeLabel}`}
                                    {display.typeLabel &&
                                      ` (${display.typeLabel})`}
                                  </span>
                                </span>
                              )}

                              <TaskLabelBadges
                                labelIds={task.labels}
                                labels={labels}
                              />
                            </div>
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Day Groups from today forward */}
            {groups.map((group) => (
              <div key={group.date} className="space-y-2">
                <div className="flex items-center space-x-2 px-1 pt-2">
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    {group.dateLabel}
                  </h3>
                  <span className="text-[10px] text-muted-foreground/80 font-normal">
                    {group.date}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    ({group.tasks.length})
                  </span>
                </div>

                <div
                  className="space-y-2"
                  role="list"
                  aria-label={`Tasks for ${group.dateLabel}`}
                >
                  {group.tasks.map((task) => {
                    const display = formatPlanDisplay(task.plan, {
                      now: currentDate,
                    });

                    return (
                      <div
                        key={task.id}
                        role="listitem"
                        className="group flex items-start justify-between p-3.5 rounded-lg border border-border/60 bg-card text-card-foreground shadow-xs hover:bg-secondary/40 hover:border-border transition-all"
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

                          <button
                            type="button"
                            onClick={() => onSelectTask(task)}
                            aria-label={`Select task ${task.title}`}
                            className="space-y-1 min-w-0 flex-1 text-left bg-transparent border-0 p-0 focus:outline-hidden focus-visible:ring-1 focus-visible:ring-ring rounded-xs cursor-pointer"
                          >
                            <p className="text-sm font-medium leading-snug text-foreground truncate">
                              {task.title}
                            </p>

                            {task.description && (
                              <p className="text-xs text-muted-foreground line-clamp-2 leading-relaxed">
                                {task.description}
                              </p>
                            )}

                            <div className="flex flex-wrap items-center gap-1.5 pt-1">
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

                              {display && (
                                <span className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded-xs text-[10px] font-medium bg-secondary text-secondary-foreground border border-border/60">
                                  <Clock className="h-3 w-3 shrink-0 text-muted-foreground" />
                                  <span>
                                    {display.dateLabel}
                                    {display.timeLabel &&
                                      ` · ${display.timeLabel}`}
                                    {display.typeLabel &&
                                      ` (${display.typeLabel})`}
                                  </span>
                                </span>
                              )}

                              <TaskLabelBadges
                                labelIds={task.labels}
                                labels={labels}
                              />
                            </div>
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
