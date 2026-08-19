import type { Plan, Task } from "../contracts/task";

export type TimedPlanType = "instant" | "floating";

export interface CreatePlanParams {
  readonly date?: string | null;
  readonly time?: string | null;
  readonly type?: TimedPlanType | null;
  readonly effectiveDefault: TimedPlanType;
  readonly now?: Date;
}

export interface PlanDisplayInfo {
  readonly dateLabel: string;
  readonly timeLabel: string | null;
  readonly typeLabel: "Instant" | "Floating" | null;
  readonly isOverdue: boolean;
}

export interface UpcomingDayGroup {
  readonly date: string;
  readonly dateLabel: string;
  readonly tasks: Task[];
}

export interface UpcomingResult {
  readonly overdue: Task[];
  readonly groups: UpcomingDayGroup[];
}

/**
 * Returns the device-local calendar date formatted as YYYY-MM-DD.
 */
export function getDeviceLocalDate(now: Date = new Date()): string {
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/**
 * Extracts the device-local calendar date (YYYY-MM-DD) representing a task plan.
 * For Date-only and Floating, this is the stored calendar date.
 * For Instant, this is the device-local date of the UTC moment.
 */
export function getPlanLocalDate(plan: Plan | null | undefined): string | null {
  if (!plan) return null;

  if ("type" in plan) {
    if (plan.type === "floating") {
      return plan.date;
    }
    if (plan.type === "instant") {
      const instantDate = new Date(plan.at);
      if (Number.isNaN(instantDate.getTime())) {
        return null;
      }
      return getDeviceLocalDate(instantDate);
    }
  }

  if ("date" in plan && typeof plan.date === "string") {
    return plan.date;
  }

  return null;
}

/**
 * Creates a contract-compliant Plan object from user inputs.
 * - If date is omitted, returns null.
 * - If time is omitted, returns Date-only plan `{ date }` without fake midnight or type.
 * - If time is provided, resolves explicit type or falls back to effective default.
 * - For Instant, converts the viewing device's local date + local time into RFC 3339 UTC ISO string.
 * - For Floating, stores the exact date and time without timezone adjustment.
 */
export function createPlanFromInputs(params: CreatePlanParams): Plan {
  const rawDate = params.date?.trim();
  if (!rawDate) {
    return null;
  }

  const rawTime = params.time?.trim();
  if (!rawTime) {
    return { date: rawDate };
  }

  // Format time as HH:mm (or HH:mm:ss)
  const timeParts = rawTime.split(":");
  if (timeParts.length < 2) {
    return { date: rawDate };
  }
  const hours = timeParts[0].padStart(2, "0");
  const minutes = timeParts[1].padStart(2, "0");
  const normalizedTime = `${hours}:${minutes}`;

  const resolvedType = params.type || params.effectiveDefault || "instant";

  if (resolvedType === "floating") {
    return {
      type: "floating",
      date: rawDate,
      time: normalizedTime,
    };
  }

  // Instant: resolve local date & time to UTC ISO string
  const [yearStr, monthStr, dayStr] = rawDate.split("-");
  const year = Number.parseInt(yearStr, 10);
  const month = Number.parseInt(monthStr, 10) - 1;
  const day = Number.parseInt(dayStr, 10);
  const h = Number.parseInt(hours, 10);
  const m = Number.parseInt(minutes, 10);

  const localDateTime = new Date(year, month, day, h, m, 0, 0);
  return {
    type: "instant",
    at: localDateTime.toISOString(),
  };
}

/**
 * Checks whether an open task is overdue relative to device's today.
 */
export function isTaskOverdue(task: Task, now: Date = new Date()): boolean {
  if (task.completedAt !== null || !task.plan) {
    return false;
  }
  const planDay = getPlanLocalDate(task.plan);
  if (!planDay) return false;

  const today = getDeviceLocalDate(now);
  return planDay < today;
}

/**
 * Formats a Plan for display in the UI using relative date semantics.
 */
export function formatPlanDisplay(
  plan: Plan | null | undefined,
  options?: { now?: Date },
): PlanDisplayInfo | null {
  if (!plan) return null;

  const now = options?.now || new Date();
  const todayStr = getDeviceLocalDate(now);

  const yesterdayDate = new Date(
    now.getFullYear(),
    now.getMonth(),
    now.getDate() - 1,
  );
  const yesterdayStr = getDeviceLocalDate(yesterdayDate);

  const tomorrowDate = new Date(
    now.getFullYear(),
    now.getMonth(),
    now.getDate() + 1,
  );
  const tomorrowStr = getDeviceLocalDate(tomorrowDate);

  const planDateStr = getPlanLocalDate(plan);
  if (!planDateStr) return null;

  let dateLabel: string;
  if (planDateStr === todayStr) {
    dateLabel = "Today";
  } else if (planDateStr === tomorrowStr) {
    dateLabel = "Tomorrow";
  } else if (planDateStr === yesterdayStr) {
    dateLabel = "Yesterday";
  } else {
    // Format friendly date, e.g. "Wed, Aug 20" or "Aug 20"
    const [y, m, d] = planDateStr.split("-").map((n) => Number.parseInt(n, 10));
    const targetDate = new Date(y, m - 1, d);
    dateLabel = targetDate.toLocaleDateString(undefined, {
      weekday: "short",
      month: "short",
      day: "numeric",
    });
  }

  let timeLabel: string | null = null;
  let typeLabel: "Instant" | "Floating" | null = null;

  if ("type" in plan) {
    if (plan.type === "floating") {
      typeLabel = "Floating";
      // Take HH:mm
      const parts = plan.time.split(":");
      timeLabel = `${parts[0]}:${parts[1]}`;
    } else if (plan.type === "instant") {
      typeLabel = "Instant";
      const instantDate = new Date(plan.at);
      const h = String(instantDate.getHours()).padStart(2, "0");
      const m = String(instantDate.getMinutes()).padStart(2, "0");
      timeLabel = `${h}:${m}`;
    }
  }

  const isOverdue = planDateStr < todayStr;

  return {
    dateLabel,
    timeLabel,
    typeLabel,
    isOverdue,
  };
}

/**
 * Filters tasks belonging to the Today view:
 * "The view of open Tasks whose plan day is today or earlier, using the viewing device's local calendar date.
 * Completed Tasks are not shown."
 */
export function filterTodayTasks(
  tasks: readonly Task[],
  now: Date = new Date(),
): Task[] {
  const todayStr = getDeviceLocalDate(now);
  return tasks
    .filter((task) => {
      if (task.completedAt !== null || !task.plan) {
        return false;
      }
      const planDay = getPlanLocalDate(task.plan);
      return planDay !== null && planDay <= todayStr;
    })
    .slice()
    .sort((a, b) => {
      const dayA = getPlanLocalDate(a.plan) || "";
      const dayB = getPlanLocalDate(b.plan) || "";
      if (dayA !== dayB) {
        return dayA.localeCompare(dayB);
      }
      return a.priority - b.priority;
    });
}

/**
 * Filters tasks belonging to the Upcoming view:
 * "The view of open dated Tasks grouped by day, from today into the future, with overdue in a strip at the top.
 * There is no 7-day window."
 */
export function filterUpcomingTasks(
  tasks: readonly Task[],
  now: Date = new Date(),
): UpcomingResult {
  const todayStr = getDeviceLocalDate(now);
  const tomorrowDate = new Date(
    now.getFullYear(),
    now.getMonth(),
    now.getDate() + 1,
  );
  const tomorrowStr = getDeviceLocalDate(tomorrowDate);

  const openDatedTasks = tasks.filter(
    (t) => t.completedAt === null && t.plan !== null,
  );

  const overdue: Task[] = [];
  const futureTasksByDate = new Map<string, Task[]>();

  for (const task of openDatedTasks) {
    const planDay = getPlanLocalDate(task.plan);
    if (!planDay) continue;

    if (planDay < todayStr) {
      overdue.push(task);
    } else {
      const existing = futureTasksByDate.get(planDay) || [];
      existing.push(task);
      futureTasksByDate.set(planDay, existing);
    }
  }

  // Sort overdue tasks by date ascending (oldest overdue first)
  overdue.sort((a, b) => {
    const dayA = getPlanLocalDate(a.plan) || "";
    const dayB = getPlanLocalDate(b.plan) || "";
    return dayA.localeCompare(dayB);
  });

  // Sort dates ascending
  const sortedDates = Array.from(futureTasksByDate.keys()).sort();
  const groups: UpcomingDayGroup[] = sortedDates.map((date) => {
    let dateLabel: string;
    if (date === todayStr) {
      dateLabel = "Today";
    } else if (date === tomorrowStr) {
      dateLabel = "Tomorrow";
    } else {
      const [y, m, d] = date.split("-").map((n) => Number.parseInt(n, 10));
      const targetDate = new Date(y, m - 1, d);
      dateLabel = targetDate.toLocaleDateString(undefined, {
        weekday: "short",
        month: "short",
        day: "numeric",
      });
    }

    const groupTasks = futureTasksByDate.get(date) || [];
    groupTasks.sort((a, b) => a.priority - b.priority);

    return {
      date,
      dateLabel,
      tasks: groupTasks,
    };
  });

  return {
    overdue,
    groups,
  };
}
