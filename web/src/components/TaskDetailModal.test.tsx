import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { TaskDetailModal } from "./TaskDetailModal";
import type { Task, Label } from "../contracts/task";

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
        plan: null,
        clearPlan: true,
        expectedVersion: openTask.version,
      });
    });
  });

  it("preserves existing Instant plan when saved without editing plan controls", async () => {
    const handleSave = vi.fn().mockResolvedValue(undefined);
    const instantTask: Task = {
      ...openTask,
      plan: {
        type: "instant",
        at: "2026-08-25T14:30:00.000Z",
      },
    };

    render(
      <TaskDetailModal
        task={instantTask}
        isOpen={true}
        onClose={vi.fn()}
        onSave={handleSave}
        onToggleComplete={vi.fn()}
      />,
    );

    const saveButton = screen.getByRole("button", { name: /save changes/i });
    fireEvent.click(saveButton);

    await waitFor(() => {
      expect(handleSave).toHaveBeenCalledWith(
        expect.objectContaining({
          id: instantTask.id,
          clearPlan: false,
          plan: expect.objectContaining({
            type: "instant",
          }),
        }),
      );
    });
  });

  it("allows setting and saving a Date-only plan", async () => {
    const handleSave = vi.fn().mockResolvedValue(undefined);

    render(
      <TaskDetailModal
        task={openTask}
        isOpen={true}
        onClose={vi.fn()}
        onSave={handleSave}
        onToggleComplete={vi.fn()}
      />,
    );

    const dateInput = screen.getByLabelText(/task plan date/i);
    fireEvent.change(dateInput, { target: { value: "2026-08-25" } });

    const saveButton = screen.getByRole("button", { name: /save changes/i });
    fireEvent.click(saveButton);

    await waitFor(() => {
      expect(handleSave).toHaveBeenCalledWith(
        expect.objectContaining({
          id: openTask.id,
          plan: { date: "2026-08-25" },
          clearPlan: false,
        }),
      );
    });
  });

  it("allows setting and saving a Floating timed plan", async () => {
    const handleSave = vi.fn().mockResolvedValue(undefined);

    render(
      <TaskDetailModal
        task={openTask}
        isOpen={true}
        effectiveDefault="instant"
        onClose={vi.fn()}
        onSave={handleSave}
        onToggleComplete={vi.fn()}
      />,
    );

    const dateInput = screen.getByLabelText(/task plan date/i);
    fireEvent.change(dateInput, { target: { value: "2026-08-25" } });

    const timeInput = screen.getByLabelText(/task plan time/i);
    fireEvent.change(timeInput, { target: { value: "15:00" } });

    const typeSelect = screen.getByLabelText(/task plan type/i);
    fireEvent.change(typeSelect, { target: { value: "floating" } });

    const saveButton = screen.getByRole("button", { name: /save changes/i });
    fireEvent.click(saveButton);

    await waitFor(() => {
      expect(handleSave).toHaveBeenCalledWith(
        expect.objectContaining({
          id: openTask.id,
          plan: {
            type: "floating",
            date: "2026-08-25",
            time: "15:00",
          },
          clearPlan: false,
        }),
      );
    });
  });

  it("allows clearing an existing plan to move task back to Inbox", async () => {
    const handleSave = vi.fn().mockResolvedValue(undefined);
    const plannedTask: Task = {
      ...openTask,
      plan: { date: "2026-08-20" },
    };

    render(
      <TaskDetailModal
        task={plannedTask}
        isOpen={true}
        onClose={vi.fn()}
        onSave={handleSave}
        onToggleComplete={vi.fn()}
      />,
    );

    const clearDateBtn = screen.getByRole("button", {
      name: /clear date \(move to inbox\)/i,
    });
    fireEvent.click(clearDateBtn);

    const saveButton = screen.getByRole("button", { name: /save changes/i });
    fireEvent.click(saveButton);

    await waitFor(() => {
      expect(handleSave).toHaveBeenCalledWith(
        expect.objectContaining({
          id: plannedTask.id,
          plan: null,
          clearPlan: true,
        }),
      );
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
    const availableLabels: Label[] = [
      {
        id: "22222222-2222-2222-2222-222222222222",
        name: "Urgent",
        color: "#ef4444",
        createdAt: "2026-08-18T10:00:00.000Z",
        updatedAt: "2026-08-18T10:00:00.000Z",
      },
      {
        id: "33333333-3333-3333-3333-333333333333",
        name: "Work",
        color: "#3b82f6",
        createdAt: "2026-08-18T11:00:00.000Z",
        updatedAt: "2026-08-18T11:00:00.000Z",
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
      expect(handleSave).toHaveBeenCalledWith(
        expect.objectContaining({
          id: taskWithLabel.id,
          title: taskWithLabel.title,
          description: taskWithLabel.description,
          priority: taskWithLabel.priority,
          expectedVersion: taskWithLabel.version,
          labels: [
            "22222222-2222-2222-2222-222222222222",
            "33333333-3333-3333-3333-333333333333",
          ],
        }),
      );
    });
  });

  it("renders dated comments distinct from description and allows adding comments", async () => {
    const handleAddComment = vi.fn().mockResolvedValue(undefined);
    const comments = [
      {
        id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        taskId: openTask.id,
        content: "First remark on this task",
        createdAt: "2026-08-18T12:00:00.000Z",
      },
    ];

    render(
      <TaskDetailModal
        task={openTask}
        isOpen={true}
        comments={comments}
        onClose={vi.fn()}
        onSave={vi.fn()}
        onToggleComplete={vi.fn()}
        onAddComment={handleAddComment}
      />,
    );

    // Verify description and comments are both visible and distinct
    expect(
      screen.getByDisplayValue("Document all public APIs and components"),
    ).toBeInTheDocument();
    expect(screen.getByText("First remark on this task")).toBeInTheDocument();
    expect(screen.getByText(/comments/i)).toBeInTheDocument();

    const commentInput = screen.getByLabelText("Add a comment");
    const addCommentBtn = screen.getByRole("button", { name: /add comment/i });

    fireEvent.change(commentInput, {
      target: { value: "Second remark added by operator" },
    });
    fireEvent.click(addCommentBtn);

    await waitFor(() => {
      expect(handleAddComment).toHaveBeenCalledWith(
        openTask.id,
        "Second remark added by operator",
      );
    });
  });

  it("renders subtasks under top-level task and allows adding subtasks and navigating to subtask", async () => {
    const handleCreateSubtask = vi.fn().mockResolvedValue(undefined);
    const handleToggleSubtask = vi.fn().mockResolvedValue(undefined);
    const handleSelectSubtask = vi.fn();

    const subtasks: Task[] = [
      {
        ...openTask,
        id: "550e8400-e29b-41d4-a716-446655440099",
        title: "Subtask 1",
        parentId: openTask.id,
        completedAt: null,
      },
    ];

    render(
      <TaskDetailModal
        task={openTask}
        isOpen={true}
        subtasks={subtasks}
        onClose={vi.fn()}
        onSave={vi.fn()}
        onToggleComplete={vi.fn()}
        onCreateSubtask={handleCreateSubtask}
        onToggleSubtaskComplete={handleToggleSubtask}
        onSelectSubtask={handleSelectSubtask}
      />,
    );

    expect(screen.getByText("Subtask 1")).toBeInTheDocument();

    // Clicking subtask title triggers onSelectSubtask
    fireEvent.click(screen.getByText("Subtask 1"));
    expect(handleSelectSubtask).toHaveBeenCalledWith(subtasks[0]);

    const subtaskInput = screen.getByLabelText("Add a subtask");
    const addSubtaskBtn = screen.getByRole("button", { name: /add subtask/i });

    fireEvent.change(subtaskInput, {
      target: { value: "Subtask 2" },
    });
    fireEvent.click(addSubtaskBtn);

    await waitFor(() => {
      expect(handleCreateSubtask).toHaveBeenCalledWith(
        openTask.id,
        "Subtask 2",
      );
    });

    const completeSubtaskBtn = screen.getByLabelText(
      /complete task subtask 1/i,
    );
    fireEvent.click(completeSubtaskBtn);
    await waitFor(() => {
      expect(handleToggleSubtask).toHaveBeenCalledWith(subtasks[0]);
    });
  });

  it("forbids adding subtasks when viewing a subtask (one-level nesting only)", () => {
    const subtask: Task = {
      ...openTask,
      id: "550e8400-e29b-41d4-a716-446655440099",
      title: "I am a subtask",
      parentId: "550e8400-e29b-41d4-a716-446655440001",
    };

    render(
      <TaskDetailModal
        task={subtask}
        isOpen={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        onToggleComplete={vi.fn()}
        onCreateSubtask={vi.fn()}
      />,
    );

    expect(screen.getByText("Subtask")).toBeInTheDocument();
    expect(
      screen.queryByPlaceholderText(/add subtask/i),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /add subtask/i }),
    ).not.toBeInTheDocument();
  });

  it("surfaces comment and subtask creation errors next to their respective sections", async () => {
    const handleAddCommentFail = vi
      .fn()
      .mockRejectedValue(new Error("Comment server error"));
    const handleCreateSubtaskFail = vi
      .fn()
      .mockRejectedValue(new Error("Subtask limit reached"));

    render(
      <TaskDetailModal
        task={openTask}
        isOpen={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        onToggleComplete={vi.fn()}
        onAddComment={handleAddCommentFail}
        onCreateSubtask={handleCreateSubtaskFail}
      />,
    );

    // Trigger comment failure
    fireEvent.change(screen.getByLabelText("Add a comment"), {
      target: { value: "Failing comment" },
    });
    fireEvent.click(screen.getByRole("button", { name: /add comment/i }));

    await waitFor(() => {
      expect(screen.getByText("Comment server error")).toBeInTheDocument();
    });

    // Trigger subtask failure
    fireEvent.change(screen.getByLabelText("Add a subtask"), {
      target: { value: "Failing subtask" },
    });
    fireEvent.click(screen.getByRole("button", { name: /add subtask/i }));

    await waitFor(() => {
      expect(screen.getByText("Subtask limit reached")).toBeInTheDocument();
      // Comment error should still be visible in its section
      expect(screen.getByText("Comment server error")).toBeInTheDocument();
    });
  });

  it("displays conflict error when save rejects with version conflict", async () => {
    const handleSave = vi
      .fn()
      .mockRejectedValue(
        new Error("Task version conflict: expected 1, found 2"),
      );

    render(
      <TaskDetailModal
        task={openTask}
        isOpen={true}
        onClose={vi.fn()}
        onSave={handleSave}
        onToggleComplete={vi.fn()}
      />,
    );

    const saveButton = screen.getByRole("button", { name: /save changes/i });
    fireEvent.click(saveButton);

    await waitFor(() => {
      expect(
        screen.getByText(/task version conflict: expected 1, found 2/i),
      ).toBeInTheDocument();
    });
  });

  it("updates form inputs and renders latest state when task prop updates due to concurrent invalidation", () => {
    const { rerender } = render(
      <TaskDetailModal
        task={openTask}
        isOpen={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        onToggleComplete={vi.fn()}
      />,
    );

    expect(screen.getByLabelText(/task title/i)).toHaveValue(
      "Write documentation",
    );

    const updatedTaskFromOtherSession: Task = {
      ...openTask,
      title: "Write documentation and tutorials",
      priority: 1,
      version: 2,
    };

    rerender(
      <TaskDetailModal
        task={updatedTaskFromOtherSession}
        isOpen={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        onToggleComplete={vi.fn()}
      />,
    );

    expect(screen.getByLabelText(/task title/i)).toHaveValue(
      "Write documentation and tutorials",
    );
    expect(screen.getByLabelText(/task priority/i)).toHaveValue("1");
  });

  it("closes modal on Escape key press", () => {
    const handleClose = vi.fn();
    render(
      <TaskDetailModal
        task={openTask}
        isOpen={true}
        onClose={handleClose}
        onSave={vi.fn()}
        onToggleComplete={vi.fn()}
      />,
    );

    fireEvent.keyDown(window, { key: "Escape", code: "Escape" });
    expect(handleClose).toHaveBeenCalled();
  });

  it("returns null when task is null or isOpen is false", () => {
    const { container: container1 } = render(
      <TaskDetailModal
        task={null}
        isOpen={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        onToggleComplete={vi.fn()}
      />,
    );
    expect(container1.firstChild).toBeNull();

    const { container: container2 } = render(
      <TaskDetailModal
        task={openTask}
        isOpen={false}
        onClose={vi.fn()}
        onSave={vi.fn()}
        onToggleComplete={vi.fn()}
      />,
    );
    expect(container2.firstChild).toBeNull();
  });
});
