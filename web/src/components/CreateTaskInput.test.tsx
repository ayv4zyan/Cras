import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { CreateTaskInput } from "./CreateTaskInput";

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
});
