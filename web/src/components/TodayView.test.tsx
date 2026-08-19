import React from "react";
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TodayView } from "./TodayView";
import type { Task, Label } from "../contracts/task";

describe("TodayView Component Seam", () => {
  const sampleLabels: Label[] = [
    {
      id: "22222222-2222-2222-2222-222222222222",
      name: "Engineering",
      color: "#3b82f6",
    },
  ];

  const todayTask: Task = {
    id: "11111111-1111-1111-1111-111111111111",
    title: "Ship temporal views",
    description: "Build Today and Upcoming views",
    priority: 1,
    plan: { date: "2026-08-19" },
    labels: ["22222222-2222-2222-2222-222222222222"],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-19T08:00:00.000Z",
    updatedAt: "2026-08-19T08:00:00.000Z",
    version: 1,
  };

  const overdueTask: Task = {
    id: "22222222-2222-2222-2222-222222222222",
    title: "Overdue contract review",
    description: null,
    priority: 2,
    plan: { date: "2026-08-18" },
    labels: [],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-18T08:00:00.000Z",
    updatedAt: "2026-08-18T08:00:00.000Z",
    version: 1,
  };

  const instantTask: Task = {
    id: "33333333-3333-3333-3333-333333333333",
    title: "Global sync call",
    description: null,
    priority: 3,
    plan: { type: "instant", at: "2026-08-19T14:00:00.000Z" },
    labels: [],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-19T08:00:00.000Z",
    updatedAt: "2026-08-19T08:00:00.000Z",
    version: 1,
  };

  it("renders today's and overdue tasks in Today view", () => {
    const handleSelect = vi.fn();
    const handleComplete = vi.fn();
    const handleCreate = vi.fn();

    render(
      <TodayView
        tasks={[overdueTask, todayTask, instantTask]}
        labels={sampleLabels}
        onCreateTask={handleCreate}
        onCompleteTask={handleComplete}
        onSelectTask={handleSelect}
        now={new Date(2026, 7, 19, 12, 0, 0)}
      />,
    );

    expect(screen.getByText("Ship temporal views")).toBeInTheDocument();
    expect(screen.getByText("Overdue contract review")).toBeInTheDocument();
    expect(screen.getByText("Global sync call")).toBeInTheDocument();
    expect(screen.getByText("Engineering")).toBeInTheDocument();
  });

  it("calls onCompleteTask when complete button is clicked", () => {
    const handleComplete = vi.fn();

    render(
      <TodayView
        tasks={[todayTask]}
        labels={sampleLabels}
        onCreateTask={vi.fn()}
        onCompleteTask={handleComplete}
        onSelectTask={vi.fn()}
        now={new Date(2026, 7, 19, 12, 0, 0)}
      />,
    );

    const completeBtn = screen.getByLabelText(
      "Complete task Ship temporal views",
    );
    fireEvent.click(completeBtn);
    expect(handleComplete).toHaveBeenCalledWith(todayTask);
  });

  it("calls onSelectTask when task item is clicked", () => {
    const handleSelect = vi.fn();

    render(
      <TodayView
        tasks={[todayTask]}
        labels={sampleLabels}
        onCreateTask={vi.fn()}
        onCompleteTask={vi.fn()}
        onSelectTask={handleSelect}
        now={new Date(2026, 7, 19, 12, 0, 0)}
      />,
    );

    const item = screen.getByRole("listitem");
    fireEvent.click(item);
    expect(handleSelect).toHaveBeenCalledWith(todayTask);
  });

  it("renders empty state when no tasks are due today", () => {
    render(
      <TodayView
        tasks={[]}
        labels={[]}
        onCreateTask={vi.fn()}
        onCompleteTask={vi.fn()}
        onSelectTask={vi.fn()}
        now={new Date(2026, 7, 19, 12, 0, 0)}
      />,
    );

    expect(screen.getByText("No tasks for Today")).toBeInTheDocument();
  });
});
