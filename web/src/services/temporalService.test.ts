import { describe, it, expect } from "vitest";
import {
  getDeviceLocalDate,
  getPlanLocalDate,
  createPlanFromInputs,
  formatPlanDisplay,
  filterTodayTasks,
  filterUpcomingTasks,
  isTaskOverdue,
} from "./temporalService";
import type { Task, Plan } from "../contracts/task";

describe("Temporal Service Seam", () => {
  const baseTask: Task = {
    id: "550e8400-e29b-41d4-a716-446655440000",
    title: "Review deployment",
    description: null,
    priority: 4,
    plan: null,
    labels: [],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-19T10:00:00.000Z",
    updatedAt: "2026-08-19T10:00:00.000Z",
    version: 1,
  };

  describe("getDeviceLocalDate", () => {
    it("returns YYYY-MM-DD in local time", () => {
      const fixedDate = new Date(2026, 7, 19, 14, 30, 0); // month is 0-indexed: 7 is August
      const localDateStr = getDeviceLocalDate(fixedDate);
      expect(localDateStr).toBe("2026-08-19");
    });
  });

  describe("createPlanFromInputs", () => {
    it("returns null when date is empty or null", () => {
      expect(
        createPlanFromInputs({
          date: null,
          time: null,
          type: null,
          effectiveDefault: "instant",
        }),
      ).toBeNull();

      expect(
        createPlanFromInputs({
          date: "   ",
          time: "10:00",
          type: null,
          effectiveDefault: "instant",
        }),
      ).toBeNull();
    });

    it("returns Date-only plan when date is provided without time (never an Instant, no fake midnight)", () => {
      const plan = createPlanFromInputs({
        date: "2026-08-20",
        time: null,
        type: null,
        effectiveDefault: "instant",
      });

      expect(plan).toEqual({ date: "2026-08-20" });
      expect(plan).not.toHaveProperty("type");
      expect(plan).not.toHaveProperty("at");
      expect(plan).not.toHaveProperty("time");
    });

    it("creates Floating plan when type is explicitly 'floating'", () => {
      const plan = createPlanFromInputs({
        date: "2026-08-20",
        time: "15:30",
        type: "floating",
        effectiveDefault: "instant",
      });

      expect(plan).toEqual({
        type: "floating",
        date: "2026-08-20",
        time: "15:30",
      });
    });

    it("creates Instant plan when type is explicitly 'instant'", () => {
      const plan = createPlanFromInputs({
        date: "2026-08-20",
        time: "15:30",
        type: "instant",
        effectiveDefault: "floating",
      });

      expect(plan?.type).toBe("instant");
      if (plan && plan.type === "instant") {
        expect(plan.at).toMatch(
          /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/,
        );
      }
    });

    it("uses effective default ('instant') when no explicit type is provided", () => {
      const plan = createPlanFromInputs({
        date: "2026-08-20",
        time: "09:00",
        type: null,
        effectiveDefault: "instant",
      });

      expect(plan?.type).toBe("instant");
    });

    it("uses effective default ('floating') when no explicit type is provided", () => {
      const plan = createPlanFromInputs({
        date: "2026-08-20",
        time: "09:00",
        type: null,
        effectiveDefault: "floating",
      });

      expect(plan).toEqual({
        type: "floating",
        date: "2026-08-20",
        time: "09:00",
      });
    });

    it("removing clock time produces Date-only plan without fake midnight", () => {
      const plan = createPlanFromInputs({
        date: "2026-08-20",
        time: "",
        type: null,
        effectiveDefault: "instant",
      });

      expect(plan).toEqual({ date: "2026-08-20" });
    });
  });

  describe("getPlanLocalDate", () => {
    it("returns null for null plan", () => {
      expect(getPlanLocalDate(null)).toBeNull();
    });

    it("returns exact date for Date-only plan", () => {
      expect(getPlanLocalDate({ date: "2026-08-20" })).toBe("2026-08-20");
    });

    it("returns exact date for Floating plan", () => {
      expect(
        getPlanLocalDate({
          type: "floating",
          date: "2026-08-20",
          time: "10:00",
        }),
      ).toBe("2026-08-20");
    });

    it("returns device-local date for Instant plan UTC timestamp", () => {
      const plan: Plan = {
        type: "instant",
        at: "2026-08-19T23:30:00.000Z",
      };
      const localDate = getPlanLocalDate(plan);
      expect(localDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    });
  });

  describe("filterTodayTasks", () => {
    it("returns open tasks whose device-local plan day is today or earlier", () => {
      const todayDate = "2026-08-19";
      const now = new Date(2026, 7, 19, 12, 0, 0);

      const todayTask: Task = {
        ...baseTask,
        id: "11111111-1111-1111-1111-111111111111",
        plan: { date: todayDate },
      };
      const overdueTask: Task = {
        ...baseTask,
        id: "22222222-2222-2222-2222-222222222222",
        plan: { date: "2026-08-18" },
      };
      const futureTask: Task = {
        ...baseTask,
        id: "33333333-3333-3333-3333-333333333333",
        plan: { date: "2026-08-20" },
      };
      const undatedTask: Task = {
        ...baseTask,
        id: "44444444-4444-4444-4444-444444444444",
        plan: null,
      };
      const completedTodayTask: Task = {
        ...baseTask,
        id: "55555555-5555-5555-5555-555555555555",
        plan: { date: todayDate },
        completedAt: "2026-08-19T11:00:00Z",
      };

      const result = filterTodayTasks(
        [todayTask, overdueTask, futureTask, undatedTask, completedTodayTask],
        now,
      );

      expect(result.map((t) => t.id)).toEqual([overdueTask.id, todayTask.id]);
    });
  });

  describe("filterUpcomingTasks", () => {
    it("groups open dated tasks from today forward and separates overdue tasks with no 7-day cap", () => {
      const now = new Date(2026, 7, 19, 12, 0, 0);

      const overdueTask: Task = {
        ...baseTask,
        id: "22222222-2222-2222-2222-222222222222",
        title: "Overdue task",
        plan: { date: "2026-08-17" },
      };
      const todayTask: Task = {
        ...baseTask,
        id: "11111111-1111-1111-1111-111111111111",
        title: "Today task",
        plan: { date: "2026-08-19" },
      };
      const tomorrowTask: Task = {
        ...baseTask,
        id: "33333333-3333-3333-3333-333333333333",
        title: "Tomorrow task",
        plan: { date: "2026-08-20" },
      };
      const farFutureTask: Task = {
        ...baseTask,
        id: "66666666-6666-6666-6666-666666666666",
        title: "Far future task (30 days)",
        plan: { date: "2026-09-19" },
      };
      const undatedTask: Task = {
        ...baseTask,
        id: "44444444-4444-4444-4444-444444444444",
        plan: null,
      };
      const completedTask: Task = {
        ...baseTask,
        id: "55555555-5555-5555-5555-555555555555",
        plan: { date: "2026-08-20" },
        completedAt: "2026-08-19T10:00:00Z",
      };

      const result = filterUpcomingTasks(
        [
          farFutureTask,
          todayTask,
          overdueTask,
          tomorrowTask,
          undatedTask,
          completedTask,
        ],
        now,
      );

      expect(result.overdue.map((t) => t.id)).toEqual([overdueTask.id]);
      expect(result.groups).toHaveLength(3);
      expect(result.groups[0].date).toBe("2026-08-19");
      expect(result.groups[0].tasks.map((t) => t.id)).toEqual([todayTask.id]);
      expect(result.groups[1].date).toBe("2026-08-20");
      expect(result.groups[1].tasks.map((t) => t.id)).toEqual([
        tomorrowTask.id,
      ]);
      expect(result.groups[2].date).toBe("2026-09-19");
      expect(result.groups[2].tasks.map((t) => t.id)).toEqual([
        farFutureTask.id,
      ]);
    });
  });

  describe("formatPlanDisplay", () => {
    it("formats Date-only plan correctly", () => {
      const now = new Date(2026, 7, 19, 12, 0, 0);
      const display = formatPlanDisplay({ date: "2026-08-19" }, { now });

      expect(display).toEqual({
        dateLabel: "Today",
        timeLabel: null,
        typeLabel: null,
        isOverdue: false,
      });
    });

    it("formats Instant plan deriving device local time and labeling type", () => {
      const instantMoment = new Date(2026, 7, 20, 14, 45, 0); // Local 14:45
      const now = new Date(2026, 7, 19, 12, 0, 0);
      const display = formatPlanDisplay(
        { type: "instant", at: instantMoment.toISOString() },
        { now },
      );

      expect(display?.dateLabel).toBe("Tomorrow");
      expect(display?.timeLabel).toBe("14:45");
      expect(display?.typeLabel).toBe("Instant");
      expect(display?.isOverdue).toBe(false);
    });

    it("flags overdue tasks appropriately", () => {
      const now = new Date(2026, 7, 19, 12, 0, 0);
      const display = formatPlanDisplay({ date: "2026-08-18" }, { now });

      expect(display?.isOverdue).toBe(true);
      expect(display?.dateLabel).toBe("Yesterday");
    });
  });

  describe("Controlled-Clock Edge Cases", () => {
    it("resolves relative date immediately at midnight boundary", () => {
      const beforeMidnight = new Date(2026, 7, 19, 23, 59, 59);
      const afterMidnight = new Date(2026, 7, 20, 0, 0, 1);

      expect(getDeviceLocalDate(beforeMidnight)).toBe("2026-08-19");
      expect(getDeviceLocalDate(afterMidnight)).toBe("2026-08-20");

      const task: Task = {
        ...baseTask,
        plan: { date: "2026-08-19" },
      };

      // Before midnight, task is today (not overdue)
      expect(isTaskOverdue(task, beforeMidnight)).toBe(false);
      // After midnight, task becomes overdue
      expect(isTaskOverdue(task, afterMidnight)).toBe(true);
    });

    it("resolves Instant plan date according to viewing device local date", () => {
      // 2026-08-19T22:30:00.000Z
      const utcIso = "2026-08-19T22:30:00.000Z";
      const instantPlan: Plan = {
        type: "instant",
        at: utcIso,
      };

      const dateObj = new Date(utcIso);
      const expectedLocalDate = getDeviceLocalDate(dateObj);
      expect(getPlanLocalDate(instantPlan)).toBe(expectedLocalDate);
    });

    it("preserves Floating date and time regardless of timezone or DST", () => {
      const floatingPlan: Plan = {
        type: "floating",
        date: "2026-03-29",
        time: "02:30",
      };

      expect(getPlanLocalDate(floatingPlan)).toBe("2026-03-29");
      const display = formatPlanDisplay(floatingPlan, {
        now: new Date(2026, 2, 28, 12, 0, 0),
      });
      expect(display?.timeLabel).toBe("02:30");
      expect(display?.typeLabel).toBe("Floating");
    });
  });
});
