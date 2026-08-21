import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { CreateTaskInput } from "./CreateTaskInput";
import type { Label } from "../contracts/task";

describe("CreateTaskInput Component", () => {
  it("renders input field and add button", () => {
    const handleCreate = vi.fn().mockResolvedValue(undefined);
    render(<CreateTaskInput onCreateTask={handleCreate} />);

    expect(
      screen.getByPlaceholderText(/create a task in inbox/i),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /create task/i }),
    ).toBeInTheDocument();
  });

  it("submits trimmed title and clears input field", async () => {
    const handleCreate = vi.fn().mockResolvedValue(undefined);
    render(<CreateTaskInput onCreateTask={handleCreate} />);

    const input = screen.getByPlaceholderText(/create a task in inbox/i);
    const submitBtn = screen.getByRole("button", { name: /create task/i });

    fireEvent.change(input, { target: { value: "  Ship MVP features  " } });
    fireEvent.click(submitBtn);

    expect(handleCreate).toHaveBeenCalledWith("Ship MVP features");

    await waitFor(() => {
      expect((input as HTMLInputElement).value).toBe("");
    });
  });

  it("submits on Enter key press", async () => {
    const handleCreate = vi.fn().mockResolvedValue(undefined);
    render(<CreateTaskInput onCreateTask={handleCreate} />);

    const input = screen.getByPlaceholderText(/create a task in inbox/i);

    fireEvent.change(input, { target: { value: "Review PR" } });
    fireEvent.keyDown(input, { key: "Enter", code: "Enter" });

    expect(handleCreate).toHaveBeenCalledWith("Review PR");

    await waitFor(() => {
      expect((input as HTMLInputElement).value).toBe("");
    });
  });

  it("does not submit empty or whitespace-only input", () => {
    const handleCreate = vi.fn().mockResolvedValue(undefined);
    render(<CreateTaskInput onCreateTask={handleCreate} />);

    const input = screen.getByPlaceholderText(/create a task in inbox/i);
    const submitBtn = screen.getByRole("button", { name: /create task/i });

    fireEvent.change(input, { target: { value: "   " } });
    fireEvent.click(submitBtn);

    expect(handleCreate).not.toHaveBeenCalled();
  });

  it("submits title, optional description, and priority level when expanded", async () => {
    const handleCreate = vi.fn().mockResolvedValue(undefined);
    render(<CreateTaskInput onCreateTask={handleCreate} />);

    const expandBtn = screen.getByRole("button", { name: /add details/i });
    fireEvent.click(expandBtn);

    const titleInput = screen.getByPlaceholderText(/create a task in inbox/i);
    const descInput = screen.getByPlaceholderText(/add description/i);
    const prioritySelect = screen.getByLabelText(/priority/i);
    const submitBtn = screen.getByRole("button", { name: /create task/i });

    fireEvent.change(titleInput, { target: { value: "Detailed Task" } });
    fireEvent.change(descInput, { target: { value: "Detailed Description" } });
    fireEvent.change(prioritySelect, { target: { value: "1" } });
    fireEvent.click(submitBtn);

    expect(handleCreate).toHaveBeenCalledWith(
      "Detailed Task",
      "Detailed Description",
      1,
    );

    await waitFor(() => {
      expect((titleInput as HTMLInputElement).value).toBe("");
      expect(
        screen.queryByPlaceholderText(/add description/i),
      ).not.toBeInTheDocument();
    });
  });

  it("submits selected labels when creating a task in expanded mode", async () => {
    const handleCreate = vi.fn().mockResolvedValue(undefined);
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

    render(
      <CreateTaskInput
        onCreateTask={handleCreate}
        availableLabels={availableLabels}
      />,
    );

    const expandBtn = screen.getByRole("button", { name: /add details/i });
    fireEvent.click(expandBtn);

    const titleInput = screen.getByPlaceholderText(/create a task in inbox/i);
    const urgentLabelCheckbox = screen.getByLabelText("Urgent");
    const submitBtn = screen.getByRole("button", { name: /create task/i });

    fireEvent.change(titleInput, { target: { value: "Task With Label" } });
    fireEvent.click(urgentLabelCheckbox);
    fireEvent.click(submitBtn);

    expect(handleCreate).toHaveBeenCalledWith(
      expect.objectContaining({
        title: "Task With Label",
        labels: ["22222222-2222-2222-2222-222222222222"],
      }),
    );
  });

  it("submits planned task with Date-only when date is picked", async () => {
    const handleCreate = vi.fn().mockResolvedValue(undefined);
    render(<CreateTaskInput onCreateTask={handleCreate} />);

    const expandBtn = screen.getByRole("button", { name: /add details/i });
    fireEvent.click(expandBtn);

    const titleInput = screen.getByPlaceholderText(/create a task in inbox/i);
    const dateInput = screen.getByLabelText(/plan date/i);
    const submitBtn = screen.getByRole("button", { name: /create task/i });

    fireEvent.change(titleInput, { target: { value: "Date Only Task" } });
    fireEvent.change(dateInput, { target: { value: "2026-08-20" } });
    fireEvent.click(submitBtn);

    expect(handleCreate).toHaveBeenCalledWith(
      expect.objectContaining({
        title: "Date Only Task",
        plan: { date: "2026-08-20" },
      }),
    );
  });

  it("submits Floating planned task when time and Floating type are selected", async () => {
    const handleCreate = vi.fn().mockResolvedValue(undefined);
    render(
      <CreateTaskInput
        onCreateTask={handleCreate}
        effectiveDefault="instant"
      />,
    );

    const expandBtn = screen.getByRole("button", { name: /add details/i });
    fireEvent.click(expandBtn);

    const titleInput = screen.getByPlaceholderText(/create a task in inbox/i);
    const dateInput = screen.getByLabelText(/plan date/i);

    fireEvent.change(titleInput, { target: { value: "Floating Task" } });
    fireEvent.change(dateInput, { target: { value: "2026-08-20" } });

    const timeInput = screen.getByLabelText(/plan time/i);
    fireEvent.change(timeInput, { target: { value: "14:00" } });

    const typeSelect = screen.getByLabelText(/plan type/i);
    fireEvent.change(typeSelect, { target: { value: "floating" } });

    const submitBtn = screen.getByRole("button", { name: /create task/i });
    fireEvent.click(submitBtn);

    expect(handleCreate).toHaveBeenCalledWith(
      expect.objectContaining({
        title: "Floating Task",
        plan: {
          type: "floating",
          date: "2026-08-20",
          time: "14:00",
        },
      }),
    );
  });

  it("restores in-progress draft task input from sessionStorage on mount and clears on submit", async () => {
    const handleCreate = vi.fn().mockResolvedValue(undefined);
    sessionStorage.setItem(
      "cras_draft_task_input",
      JSON.stringify({
        title: "Restored Draft Title",
        description: "Restored Draft Description",
      }),
    );

    render(<CreateTaskInput onCreateTask={handleCreate} />);

    const titleInput = screen.getByPlaceholderText(
      /create a task in inbox/i,
    ) as HTMLInputElement;
    expect(titleInput.value).toBe("Restored Draft Title");

    const descInput = screen.getByPlaceholderText(
      /add description/i,
    ) as HTMLTextAreaElement;
    expect(descInput.value).toBe("Restored Draft Description");

    const submitBtn = screen.getByRole("button", { name: /create task/i });
    fireEvent.click(submitBtn);

    expect(handleCreate).toHaveBeenCalledWith(
      "Restored Draft Title",
      "Restored Draft Description",
      undefined,
    );

    await waitFor(() => {
      expect(sessionStorage.getItem("cras_draft_task_input")).toBeNull();
    });
  });
});
