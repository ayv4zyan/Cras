import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { UpcomingView } from "./UpcomingView";
import type { Task, Label } from "../contracts/task";

describe("UpcomingView Component Seam", () => {
  const sampleLabels: Label[] = [
    {
      id: "22222222-2222-2222-2222-222222222222",
      name: "Engineering",
      color: "#3b82f6",
    },
  ];

  const overdueTask: Task = {
    id: "99999999-9999-9999-9999-999999999999",
    title: "Overdue task",
    description: null,
    priority: 1,
    plan: { date: "2026-08-17" },
    labels: [],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-17T08:00:00.000Z",
    updatedAt: "2026-08-17T08:00:00.000Z",
    version: 1,
  };

  const todayTask: Task = {
    id: "11111111-1111-1111-1111-111111111111",
    title: "Today task",
    description: "Today's priority item",
    priority: 2,
    plan: { date: "2026-08-19" },
    labels: ["22222222-2222-2222-2222-222222222222"],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-19T08:00:00.000Z",
    updatedAt: "2026-08-19T08:00:00.000Z",
    version: 1,
  };

  const tomorrowTask: Task = {
    id: "33333333-3333-3333-3333-333333333333",
    title: "Tomorrow task",
    description: null,
    priority: 3,
    plan: { type: "floating", date: "2026-08-20", time: "11:00" },
    labels: [],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-19T08:00:00.000Z",
    updatedAt: "2026-08-19T08:00:00.000Z",
    version: 1,
  };

  const futureTask: Task = {
    id: "44444444-4444-4444-4444-444444444444",
    title: "Future 30-day task",
    description: null,
    priority: 4,
    plan: { date: "2026-09-19" },
    labels: [],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-19T08:00:00.000Z",
    updatedAt: "2026-08-19T08:00:00.000Z",
    version: 1,
  };

  it("renders overdue strip at top and future day groupings without 7-day cap", () => {
    const handleSelect = vi.fn();
    const handleComplete = vi.fn();

    render(
      <UpcomingView
        tasks={[futureTask, todayTask, overdueTask, tomorrowTask]}
        labels={sampleLabels}
        onCompleteTask={handleComplete}
        onSelectTask={handleSelect}
        now={new Date(2026, 7, 19, 12, 0, 0)}
      />,
    );

    // Overdue strip
    expect(screen.getByText("Overdue (1)")).toBeInTheDocument();
    expect(screen.getByText("Overdue task")).toBeInTheDocument();

    // Day groups render in chronological order
    const titles = screen
      .getAllByText(/task$/i)
      .map((node) => node.textContent);
    expect(titles).toEqual([
      "Overdue task",
      "Today task",
      "Tomorrow task",
      "Future 30-day task",
    ]);
  });

  it("calls onCompleteTask when task checkbox clicked in upcoming view", () => {
    const handleComplete = vi.fn();

    render(
      <UpcomingView
        tasks={[tomorrowTask]}
        labels={sampleLabels}
        onCompleteTask={handleComplete}
        onSelectTask={vi.fn()}
        now={new Date(2026, 7, 19, 12, 0, 0)}
      />,
    );

    const completeBtn = screen.getByLabelText("Complete task Tomorrow task");
    fireEvent.click(completeBtn);
    expect(handleComplete).toHaveBeenCalledWith(tomorrowTask);
  });

  it("calls onSelectTask when task selection button is clicked", () => {
    const handleSelect = vi.fn();

    render(
      <UpcomingView
        tasks={[tomorrowTask]}
        labels={sampleLabels}
        onCompleteTask={vi.fn()}
        onSelectTask={handleSelect}
        now={new Date(2026, 7, 19, 12, 0, 0)}
      />,
    );

    const selectBtn = screen.getByRole("button", {
      name: `Select task ${tomorrowTask.title}`,
    });
    fireEvent.click(selectBtn);
    expect(handleSelect).toHaveBeenCalledWith(tomorrowTask);
  });

  it("renders empty state when no upcoming tasks", () => {
    render(
      <UpcomingView
        tasks={[]}
        labels={[]}
        onCompleteTask={vi.fn()}
        onSelectTask={vi.fn()}
        now={new Date(2026, 7, 19, 12, 0, 0)}
      />,
    );

    expect(screen.getByText("No upcoming tasks")).toBeInTheDocument();
  });
});
