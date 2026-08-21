import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { NotificationPermissionModal } from "./NotificationPermissionModal";
import { BEST_EFFORT_RELIABILITY_COPY } from "../services/notificationService";
import type { SupabaseClient } from "@supabase/supabase-js";

describe("NotificationPermissionModal Component", () => {
  let mockClient: SupabaseClient;
  let mockRpc: ReturnType<typeof vi.fn>;
  let originalNotification: typeof window.Notification;

  beforeEach(() => {
    localStorage.clear();
    originalNotification = window.Notification;
    mockRpc = vi.fn().mockResolvedValue({ data: {}, error: null });
    mockClient = {
      rpc: mockRpc,
      schema: vi.fn().mockReturnValue({ rpc: mockRpc }),
    } as unknown as SupabaseClient;
  });

  afterEach(() => {
    Object.defineProperty(window, "Notification", {
      writable: true,
      value: originalNotification,
    });
  });

  it("does not render when isOpen is false", () => {
    render(
      <NotificationPermissionModal
        isOpen={false}
        onClose={vi.fn()}
        client={mockClient}
      />,
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("renders explanation and exact best-effort reliability copy when open", () => {
    render(
      <NotificationPermissionModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
      />,
    );

    expect(
      screen.getByRole("heading", { name: /timed task notifications/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(BEST_EFFORT_RELIABILITY_COPY)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /enable notifications/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /maybe later/i }),
    ).toBeInTheDocument();
  });

  it("handles Maybe Later by setting explained and closing modal", () => {
    const onClose = vi.fn();
    render(
      <NotificationPermissionModal
        isOpen={true}
        onClose={onClose}
        client={mockClient}
      />,
    );

    const skipBtn = screen.getByRole("button", { name: /maybe later/i });
    fireEvent.click(skipBtn);

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(
      localStorage.getItem("cras_notifications_permission_explained"),
    ).toBe("true");
  });

  it("handles Escape key by setting explained and closing modal", () => {
    const onClose = vi.fn();
    render(
      <NotificationPermissionModal
        isOpen={true}
        onClose={onClose}
        client={mockClient}
      />,
    );

    fireEvent.keyDown(window, { key: "Escape" });

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(
      localStorage.getItem("cras_notifications_permission_explained"),
    ).toBe("true");
  });

  it("handles Enable Notifications by requesting browser permission", async () => {
    const onClose = vi.fn();
    const onPermissionResolved = vi.fn();

    // Mock Notification API
    const mockRequestPermission = vi.fn().mockResolvedValue("granted");
    Object.defineProperty(window, "Notification", {
      writable: true,
      value: {
        permission: "default",
        requestPermission: mockRequestPermission,
      },
    });

    render(
      <NotificationPermissionModal
        isOpen={true}
        onClose={onClose}
        client={mockClient}
        onPermissionResolved={onPermissionResolved}
      />,
    );

    const enableBtn = screen.getByRole("button", {
      name: /enable notifications/i,
    });
    fireEvent.click(enableBtn);

    await waitFor(() => {
      expect(mockRequestPermission).toHaveBeenCalled();
      expect(onPermissionResolved).toHaveBeenCalledWith("granted");
      expect(mockRpc).toHaveBeenCalledWith(
        "register_or_update_installation",
        expect.objectContaining({
          p_permission_state: "granted",
        }),
      );
      expect(onClose).toHaveBeenCalled();
    });
  });
});
