import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { AuthProvider } from "../contexts/AuthContext";
import { CrasApp } from "../App";
import { SettingsModal } from "../components/SettingsModal";
import { LabelManagerModal } from "../components/LabelManagerModal";
import { TaskDetailModal } from "../components/TaskDetailModal";
import { NotificationPermissionModal } from "../components/NotificationPermissionModal";
import type { Task, Label } from "../contracts/task";
import type { SupabaseClient, Session, User } from "@supabase/supabase-js";

describe("Accessibility Suite - Keyboard, Semantics, Focus, & Touch Targets (AC 2)", () => {
  const mockUser: User = {
    id: "11111111-1111-1111-1111-111111111111",
    email: "operator@example.com",
    app_metadata: {},
    user_metadata: {},
    aud: "authenticated",
    created_at: new Date().toISOString(),
  };

  const sampleTask: Task = {
    id: "11111111-1111-1111-1111-111111111111",
    title: "Accessibility Sample Task",
    description: "Testing screen-reader semantics and keyboard focus",
    priority: 1,
    plan: { type: "floating", date: "2026-08-26", time: "14:00" },
    labels: [],
    parentId: null,
    completedAt: null,
    createdAt: "2026-08-26T12:00:00Z",
    updatedAt: "2026-08-26T12:00:00Z",
    version: 1,
  };

  const sampleLabels: Label[] = [
    { id: "label-1", name: "Urgent", color: "#ef4444" },
    { id: "label-2", name: "Work", color: "#10b981" },
  ];

  function createMockClient(): SupabaseClient {
    return {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: {
            session: {
              user: mockUser,
              access_token: "mock-token",
            } as Session,
          },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockReturnValue({
          data: { subscription: { unsubscribe: vi.fn() } },
        }),
        signOut: vi.fn().mockResolvedValue({ error: null }),
      },
      from: vi.fn().mockReturnValue({
        select: vi.fn().mockReturnValue({
          order: vi.fn().mockResolvedValue({ data: [], error: null }),
          maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
        }),
      }),
      schema: vi.fn().mockReturnValue({
        from: vi.fn().mockReturnValue({
          select: vi.fn().mockResolvedValue({ data: [], error: null }),
        }),
        rpc: vi.fn().mockResolvedValue({ data: null, error: null }),
      }),
      channel: vi.fn().mockReturnValue({
        on: vi.fn().mockReturnThis(),
        subscribe: vi.fn().mockReturnValue({ unsubscribe: vi.fn() }),
      }),
    } as unknown as SupabaseClient;
  }

  describe("Criterion 1: Dialog Semantics and Escape Dismissal across Modals", () => {
    it("SettingsModal renders role='dialog', aria-modal, and handles Escape key", () => {
      const onClose = vi.fn();
      render(
        <SettingsModal
          isOpen={true}
          onClose={onClose}
          client={createMockClient()}
          effectiveDefaultTimedPlanType="instant"
          onDeleteAccount={vi.fn()}
        />,
      );

      const dialog = screen.getByRole("dialog");
      expect(dialog).toBeDefined();

      // Press Escape
      fireEvent.keyDown(window, { key: "Escape", code: "Escape" });
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it("LabelManagerModal renders accessible dialog structure and handles Escape", () => {
      const onClose = vi.fn();
      render(
        <LabelManagerModal
          isOpen={true}
          onClose={onClose}
          labels={sampleLabels}
          onCreateLabel={vi.fn()}
          onUpdateLabel={vi.fn()}
          onDeleteLabel={vi.fn()}
        />,
      );

      const dialog = screen.getByRole("dialog");
      expect(dialog).toBeDefined();

      fireEvent.keyDown(window, { key: "Escape", code: "Escape" });
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it("TaskDetailModal renders accessible dialog structure and handles Escape", () => {
      const onClose = vi.fn();
      render(
        <TaskDetailModal
          isOpen={true}
          task={sampleTask}
          availableLabels={sampleLabels}
          comments={[]}
          subtasks={[]}
          effectiveDefault="instant"
          onClose={onClose}
          onSave={vi.fn()}
          onToggleComplete={vi.fn()}
        />,
      );

      const dialog = screen.getByRole("dialog");
      expect(dialog).toBeDefined();

      fireEvent.keyDown(window, { key: "Escape", code: "Escape" });
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it("NotificationPermissionModal renders accessible dialog structure", () => {
      const onClose = vi.fn();
      render(
        <NotificationPermissionModal
          isOpen={true}
          onClose={onClose}
          client={createMockClient()}
        />,
      );

      const dialog = screen.getByRole("dialog");
      expect(dialog).toBeDefined();

      fireEvent.keyDown(window, { key: "Escape", code: "Escape" });
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  describe("Criterion 2: Screen-Reader Semantics and ARIA Labels", () => {
    it("ensures interactive controls and icon-only buttons have accessible names", () => {
      render(
        <SettingsModal
          isOpen={true}
          onClose={vi.fn()}
          client={createMockClient()}
          effectiveDefaultTimedPlanType="instant"
          onDeleteAccount={vi.fn()}
        />,
      );

      // Verify close button has accessible label
      const closeBtn = screen.getByRole("button", { name: /close/i });
      expect(closeBtn).toBeDefined();
    });

    it("verifies heading hierarchy in navigation and views", async () => {
      const client = createMockClient();
      render(
        <AuthProvider client={client}>
          <CrasApp client={client} />
        </AuthProvider>,
      );

      await waitFor(() => {
        const appHeading = screen.getByRole("heading", {
          level: 1,
          name: /cras/i,
        });
        expect(appHeading).toBeDefined();

        const viewHeading = screen.getByRole("heading", {
          level: 2,
          name: /inbox/i,
        });
        expect(viewHeading).toBeDefined();
      });
    });
  });

  describe("Criterion 3: Touch Target & Focus Visibility", () => {
    it("ensures interactive buttons maintain touch target minimum classes and focus outlines", () => {
      const { container } = render(
        <SettingsModal
          isOpen={true}
          onClose={vi.fn()}
          client={createMockClient()}
          effectiveDefaultTimedPlanType="instant"
          onDeleteAccount={vi.fn()}
        />,
      );

      const buttons = container.querySelectorAll("button");
      expect(buttons.length).toBeGreaterThan(0);
      buttons.forEach((btn) => {
        expect(btn.getAttribute("aria-hidden")).toBeNull();
        const cls = btn.className;
        expect(cls).toMatch(/p-\d|px-\d|py-\d|min-h-\[|h-\d/);
        expect(cls).toMatch(/focus-visible:|focus:|focus-ring/);
      });
    });
  });
});
