import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { CompletedView } from "./CompletedView";
import type { Task } from "../contracts/task";

describe("CompletedView Component", () => {
  const completedTask1: Task = {
    id: "550e8400-e29b-41d4-a716-446655440021",
    title: "Finish report",
    description: "Quarterly summary",
    priority: 1,
    plan: null,
    labels: [],
    parentId: null,
    completedAt: "2026-08-18T16:00:00.000Z",
    createdAt: "2026-08-18T10:00:00.000Z",
    updatedAt: "2026-08-18T16:00:00.000Z",
    version: 2,
  };

  const completedTask2: Task = {
    id: "550e8400-e29b-41d4-a716-446655440022",
    title: "Buy coffee",
    description: null,
    priority: 4,
    plan: null,
    labels: [],
    parentId: null,
    completedAt: "2026-08-18T14:00:00.000Z",
    createdAt: "2026-08-18T09:00:00.000Z",
    updatedAt: "2026-08-18T14:00:00.000Z",
    version: 2,
  };

  it("renders empty state when there are no completed tasks", () => {
    render(
      <CompletedView
        tasks={[]}
        onUncompleteTask={vi.fn()}
        onSelectTask={vi.fn()}
      />,
    );

    expect(
      screen.getByRole("heading", { level: 2, name: /completed/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(/no completed tasks yet/i)).toBeInTheDocument();
  });

  it("renders list of completed tasks with details and priority badges", () => {
    render(
      <CompletedView
        tasks={[completedTask1, completedTask2]}
        onUncompleteTask={vi.fn()}
        onSelectTask={vi.fn()}
      />,
    );

    expect(screen.getByText("Finish report")).toBeInTheDocument();
    expect(screen.getByText("Quarterly summary")).toBeInTheDocument();
    expect(screen.getByText("P1")).toBeInTheDocument();
    expect(screen.getByText("Buy coffee")).toBeInTheDocument();
  });

  it("calls onUncompleteTask when uncomplete checkmark button is clicked", () => {
    const handleUncomplete = vi.fn();
    render(
      <CompletedView
        tasks={[completedTask1]}
        onUncompleteTask={handleUncomplete}
        onSelectTask={vi.fn()}
      />,
    );

    const uncompleteBtn = screen.getByRole("button", {
      name: /uncomplete task finish report/i,
    });
    fireEvent.click(uncompleteBtn);
    expect(handleUncomplete).toHaveBeenCalledWith(completedTask1);
  });

  it("calls onSelectTask when task item is clicked", () => {
    const handleSelect = vi.fn();
    render(
      <CompletedView
        tasks={[completedTask1]}
        onUncompleteTask={vi.fn()}
        onSelectTask={handleSelect}
      />,
    );

    const taskItem = screen.getByTestId(`task-item-${completedTask1.id}`);
    fireEvent.click(taskItem);
    expect(handleSelect).toHaveBeenCalledWith(completedTask1);
  });
});
