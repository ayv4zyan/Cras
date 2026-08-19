import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { LabelManagerModal } from "./LabelManagerModal";
import type { Label } from "../contracts/task";

describe("LabelManagerModal Component", () => {
  const sampleLabels: Label[] = [
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

  it("renders list of existing labels with their names and colors", () => {
    render(
      <LabelManagerModal
        isOpen={true}
        labels={sampleLabels}
        onClose={vi.fn()}
        onCreateLabel={vi.fn()}
        onUpdateLabel={vi.fn()}
        onDeleteLabel={vi.fn()}
      />,
    );

    expect(screen.getByText("Manage Labels")).toBeInTheDocument();
    expect(screen.getByText("Urgent")).toBeInTheDocument();
    expect(screen.getByText("Work")).toBeInTheDocument();
  });

  it("creates a new label with name and color", async () => {
    const onCreateLabel = vi.fn().mockResolvedValue(undefined);

    render(
      <LabelManagerModal
        isOpen={true}
        labels={sampleLabels}
        onClose={vi.fn()}
        onCreateLabel={onCreateLabel}
        onUpdateLabel={vi.fn()}
        onDeleteLabel={vi.fn()}
      />,
    );

    const nameInput = screen.getByPlaceholderText(/new label name/i);
    const createBtn = screen.getByRole("button", { name: /add label/i });

    fireEvent.change(nameInput, { target: { value: "Personal" } });
    fireEvent.click(createBtn);

    expect(onCreateLabel).toHaveBeenCalledWith({
      name: "Personal",
      color: expect.any(String),
    });
  });

  it("displays error when attempting to create a label with duplicate name", async () => {
    const onCreateLabel = vi
      .fn()
      .mockImplementation(async ({ name }: { name: string }) => {
        if (name.toLowerCase() === "urgent") {
          throw new Error("A label with this name already exists");
        }
      });

    render(
      <LabelManagerModal
        isOpen={true}
        labels={sampleLabels}
        onClose={vi.fn()}
        onCreateLabel={onCreateLabel}
        onUpdateLabel={vi.fn()}
        onDeleteLabel={vi.fn()}
      />,
    );

    const nameInput = screen.getByPlaceholderText(/new label name/i);
    const createBtn = screen.getByRole("button", { name: /add label/i });

    fireEvent.change(nameInput, { target: { value: "Urgent" } });
    fireEvent.click(createBtn);

    expect(
      await screen.findByText(/a label with this name already exists/i),
    ).toBeInTheDocument();
  });

  it("renames and recolors a label", async () => {
    const onUpdateLabel = vi.fn().mockResolvedValue(undefined);

    render(
      <LabelManagerModal
        isOpen={true}
        labels={sampleLabels}
        onClose={vi.fn()}
        onCreateLabel={vi.fn()}
        onUpdateLabel={onUpdateLabel}
        onDeleteLabel={vi.fn()}
      />,
    );

    const editBtn = screen.getByRole("button", {
      name: /edit label urgent/i,
    });
    fireEvent.click(editBtn);

    const editInput = screen.getByDisplayValue("Urgent");
    fireEvent.change(editInput, { target: { value: "Critical" } });

    const saveBtn = screen.getByRole("button", { name: /save label/i });
    fireEvent.click(saveBtn);

    expect(onUpdateLabel).toHaveBeenCalledWith({
      id: "22222222-2222-2222-2222-222222222222",
      name: "Critical",
      color: expect.any(String),
    });
  });

  it("deletes a label when delete button is clicked", async () => {
    const onDeleteLabel = vi.fn().mockResolvedValue(undefined);

    render(
      <LabelManagerModal
        isOpen={true}
        labels={sampleLabels}
        onClose={vi.fn()}
        onCreateLabel={vi.fn()}
        onUpdateLabel={vi.fn()}
        onDeleteLabel={onDeleteLabel}
      />,
    );

    const deleteBtn = screen.getByRole("button", {
      name: /delete label urgent/i,
    });
    fireEvent.click(deleteBtn);

    expect(onDeleteLabel).toHaveBeenCalledWith(
      "22222222-2222-2222-2222-222222222222",
    );
  });
});
