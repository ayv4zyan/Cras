import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { InboxView } from "./InboxView";
import type { Task } from "../contracts/task";

describe("InboxView Component", () => {
  const task1: Task = {
    id: "550e8400-e29b-41d4-a716-446655440001",
    title: "Buy groceries",
    description: null,
    priority: 4,
    plan: null,
    labels: [],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-18T20:00:00.000Z",
    updatedAt: "2026-08-18T20:00:00.000Z",
    version: 1,
  };

  const task2: Task = {
    id: "550e8400-e29b-41d4-a716-446655440002",
    title: "Buy groceries", // Same title, distinct id!
    description: null,
    priority: 4,
    plan: null,
    labels: [],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-18T20:05:00.000Z",
    updatedAt: "2026-08-18T20:05:00.000Z",
    version: 1,
  };

  it("renders empty state when there are no tasks", () => {
    const handleCreate = vi.fn();
    render(
      <InboxView tasks={[]} onCreateTask={handleCreate} isLoading={false} />,
    );

    expect(
      screen.getByRole("heading", { level: 2, name: /inbox/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(/no tasks in inbox/i)).toBeInTheDocument();
  });

  it("renders task items with distinct stable identities when titles are identical", () => {
    const handleCreate = vi.fn();
    render(
      <InboxView
        tasks={[task1, task2]}
        onCreateTask={handleCreate}
        isLoading={false}
      />,
    );

    const taskElements = screen.getAllByText("Buy groceries");
    expect(taskElements).toHaveLength(2);

    expect(screen.getByTestId(`task-item-${task1.id}`)).toBeInTheDocument();
    expect(screen.getByTestId(`task-item-${task2.id}`)).toBeInTheDocument();
  });

  it("delegates task creation from the input to onCreateTask", async () => {
    const handleCreate = vi.fn().mockResolvedValue(undefined);
    render(
      <InboxView tasks={[]} onCreateTask={handleCreate} isLoading={false} />,
    );

    const input = screen.getByPlaceholderText(/add a task to inbox/i);
    fireEvent.change(input, { target: { value: "New task item" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /add task/i }));
    });

    expect(handleCreate).toHaveBeenCalledWith("New task item");
    expect((input as HTMLInputElement).value).toBe("");
  });
});
