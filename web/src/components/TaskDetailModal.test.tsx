import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { TaskDetailModal } from "./TaskDetailModal";
import type { Task } from "../contracts/task";

describe("TaskDetailModal Component", () => {
  const openTask: Task = {
    id: "550e8400-e29b-41d4-a716-446655440001",
    title: "Write documentation",
    description: "Document all public APIs and components",
    priority: 2,
    plan: null,
    labels: [],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-18T10:00:00.000Z",
    updatedAt: "2026-08-18T10:00:00.000Z",
    version: 1,
  };

  const completedTask: Task = {
    id: "550e8400-e29b-41d4-a716-446655440002",
    title: "Done task",
    description: "Already finished task",
    priority: 1,
    plan: null,
    labels: [],
    parentId: null,
    completedAt: "2026-08-18T15:30:00.000Z",
    createdAt: "2026-08-18T10:00:00.000Z",
    updatedAt: "2026-08-18T15:30:00.000Z",
    version: 2,
  };

  it("renders open task fields and allows editing title, description, and priority", async () => {
    const handleSave = vi.fn().mockResolvedValue(undefined);
    const handleClose = vi.fn();
    const handleComplete = vi.fn().mockResolvedValue(undefined);

    render(
      <TaskDetailModal
        task={openTask}
        isOpen={true}
        onClose={handleClose}
        onSave={handleSave}
        onToggleComplete={handleComplete}
      />,
    );

    const titleInput = screen.getByLabelText(/task title/i);
    const descriptionInput = screen.getByLabelText(/task description/i);
    const prioritySelect = screen.getByLabelText(/task priority/i);
    const saveButton = screen.getByRole("button", { name: /save changes/i });

    expect(titleInput).toHaveValue("Write documentation");
    expect(descriptionInput).toHaveValue(
      "Document all public APIs and components",
    );
    expect(prioritySelect).toHaveValue("2");
    expect(titleInput).not.toBeDisabled();
    expect(descriptionInput).not.toBeDisabled();
    expect(prioritySelect).not.toBeDisabled();

    fireEvent.change(titleInput, {
      target: { value: "Updated Documentation" },
    });
    fireEvent.change(descriptionInput, {
      target: { value: "Updated description text" },
    });
    fireEvent.change(prioritySelect, { target: { value: "1" } });

    fireEvent.click(saveButton);

    await waitFor(() => {
      expect(handleSave).toHaveBeenCalledWith({
        id: openTask.id,
        title: "Updated Documentation",
        description: "Updated description text",
        priority: 1,
        labels: [],
        expectedVersion: openTask.version,
      });
    });
  });

  it("renders completed task as read-only with warning banner and disabled edit inputs", async () => {
    const handleSave = vi.fn();
    const handleClose = vi.fn();
    const handleToggleComplete = vi.fn();

    render(
      <TaskDetailModal
        task={completedTask}
        isOpen={true}
        onClose={handleClose}
        onSave={handleSave}
        onToggleComplete={handleToggleComplete}
      />,
    );

    expect(
      screen.getByText(
        /completed tasks cannot be edited\. uncomplete first\./i,
      ),
    ).toBeInTheDocument();

    const titleInput = screen.getByLabelText(/task title/i);
    const descriptionInput = screen.getByLabelText(/task description/i);
    const prioritySelect = screen.getByLabelText(/task priority/i);

    expect(titleInput).toBeDisabled();
    expect(descriptionInput).toBeDisabled();
    expect(prioritySelect).toBeDisabled();
    expect(
      screen.queryByRole("button", { name: /save changes/i }),
    ).not.toBeInTheDocument();

    const uncompleteBtn = screen.getByRole("button", {
      name: /uncomplete task/i,
    });
    expect(uncompleteBtn).toBeInTheDocument();
    fireEvent.click(uncompleteBtn);
    await waitFor(() => {
      expect(handleToggleComplete).toHaveBeenCalledWith(completedTask);
    });
  });

  it("calls onToggleComplete when complete button is clicked on an open task", async () => {
    const handleToggleComplete = vi.fn().mockResolvedValue(undefined);
    render(
      <TaskDetailModal
        task={openTask}
        isOpen={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        onToggleComplete={handleToggleComplete}
      />,
    );

    const completeBtn = screen.getByRole("button", { name: /complete task/i });
    fireEvent.click(completeBtn);
    await waitFor(() => {
      expect(handleToggleComplete).toHaveBeenCalledWith(openTask);
    });
  });

  it("renders labels and allows adding and removing labels on an open task", async () => {
    const handleSave = vi.fn().mockResolvedValue(undefined);
    const availableLabels = [
      {
        id: "22222222-2222-2222-2222-222222222222",
        name: "Urgent",
        color: "#ef4444",
      },
      {
        id: "33333333-3333-3333-3333-333333333333",
        name: "Work",
        color: "#3b82f6",
      },
    ];

    const taskWithLabel: Task = {
      ...openTask,
      labels: ["22222222-2222-2222-2222-222222222222"],
    };

    render(
      <TaskDetailModal
        task={taskWithLabel}
        isOpen={true}
        availableLabels={availableLabels}
        onClose={vi.fn()}
        onSave={handleSave}
        onToggleComplete={vi.fn()}
      />,
    );

    // Urgent is currently checked, Work is not
    const urgentCheckbox = screen.getByLabelText("Urgent");
    const workCheckbox = screen.getByLabelText("Work");

    expect(urgentCheckbox).toBeChecked();
    expect(workCheckbox).not.toBeChecked();

    // Toggle Work on
    fireEvent.click(workCheckbox);

    const saveButton = screen.getByRole("button", { name: /save changes/i });
    fireEvent.click(saveButton);

    await waitFor(() => {
      expect(handleSave).toHaveBeenCalledWith({
        id: taskWithLabel.id,
        title: taskWithLabel.title,
        description: taskWithLabel.description,
        priority: taskWithLabel.priority,
        expectedVersion: taskWithLabel.version,
        labels: [
          "22222222-2222-2222-2222-222222222222",
          "33333333-3333-3333-3333-333333333333",
        ],
      });
    });
  });
});
