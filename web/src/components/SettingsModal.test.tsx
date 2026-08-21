import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { SettingsModal } from "./SettingsModal";
import { BEST_EFFORT_RELIABILITY_COPY } from "../services/notificationService";
import type { SupabaseClient } from "@supabase/supabase-js";

describe("SettingsModal Component", () => {
  let mockClient: SupabaseClient;
  let mockFrom: ReturnType<typeof vi.fn>;
  let mockRpc: ReturnType<typeof vi.fn>;
  let mockUpsert: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.clear();
    mockRpc = vi.fn().mockResolvedValue({ data: {}, error: null });
    mockUpsert = vi.fn().mockResolvedValue({ error: null });
    mockFrom = vi.fn().mockReturnValue({
      select: vi.fn().mockReturnValue({
        maybeSingle: vi.fn().mockResolvedValue({
          data: {
            operator_id: "op-1",
            default_timed_plan_type: "instant",
            missed_delivery_enabled: false,
          },
          error: null,
        }),
      }),
      upsert: mockUpsert,
    });

    mockClient = {
      from: mockFrom,
      rpc: mockRpc,
      schema: vi.fn().mockReturnValue({ rpc: mockRpc, from: mockFrom }),
    } as unknown as SupabaseClient;
  });

  it("does not render when isOpen is false", () => {
    render(
      <SettingsModal
        isOpen={false}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
      />,
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("renders exact reliability copy and sections when open", async () => {
    render(
      <SettingsModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
      />,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("heading", {
          name: /operator & installation settings/i,
        }),
      ).toBeInTheDocument();
      expect(
        screen.getByText(BEST_EFFORT_RELIABILITY_COPY),
      ).toBeInTheDocument();
    });
  });

  it("handles Escape key to close modal", async () => {
    const onClose = vi.fn();
    render(
      <SettingsModal
        isOpen={true}
        onClose={onClose}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
      />,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("heading", {
          name: /operator & installation settings/i,
        }),
      ).toBeInTheDocument();
    });

    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("displays Disabled locally when local toggle is off", async () => {
    localStorage.setItem("cras_notifications_local_enabled", "false");

    render(
      <SettingsModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId("status-disabled-locally")).toBeInTheDocument();
    });
  });

  it("displays Blocked by system permission when Notification permission is denied", async () => {
    const originalNotification = window.Notification;
    Object.defineProperty(window, "Notification", {
      writable: true,
      value: {
        permission: "denied",
        requestPermission: vi.fn(),
      },
    });

    render(
      <SettingsModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId("status-blocked")).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /re-check browser permission/i }),
      ).toBeInTheDocument();
    });

    Object.defineProperty(window, "Notification", {
      writable: true,
      value: originalNotification,
    });
  });

  it("allows toggling local notifications", async () => {
    render(
      <SettingsModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
      />,
    );

    await waitFor(() => {
      expect(
        screen.getByLabelText(/toggle notifications for this device/i),
      ).toBeInTheDocument();
    });

    const toggle = screen.getByLabelText(
      /toggle notifications for this device/i,
    );
    fireEvent.click(toggle);

    await waitFor(() => {
      expect(localStorage.getItem("cras_notifications_local_enabled")).toBe(
        "false",
      );
    });
  });

  it("allows changing operator default timed plan type", async () => {
    const onTimedPlanTypeChanged = vi.fn();
    render(
      <SettingsModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
        onTimedPlanTypeChanged={onTimedPlanTypeChanged}
      />,
    );

    await waitFor(() => {
      expect(
        screen.getByLabelText(/default timed plan type/i),
      ).toBeInTheDocument();
    });

    const select = screen.getByLabelText(/default timed plan type/i);
    fireEvent.change(select, { target: { value: "floating" } });

    await waitFor(() => {
      expect(mockFrom).toHaveBeenCalledWith("settings");
      expect(mockUpsert).toHaveBeenCalledWith({
        default_timed_plan_type: "floating",
      });
      expect(onTimedPlanTypeChanged).toHaveBeenCalledWith("floating");
    });
  });

  it("allows toggling missed delivery setting", async () => {
    render(
      <SettingsModal
        isOpen={true}
        onClose={vi.fn()}
        client={mockClient}
        effectiveDefaultTimedPlanType="instant"
      />,
    );

    await waitFor(() => {
      expect(
        screen.getByLabelText(/toggle missed notification delivery/i),
      ).toBeInTheDocument();
    });

    const toggle = screen.getByLabelText(
      /toggle missed notification delivery/i,
    );
    fireEvent.click(toggle);

    await waitFor(() => {
      expect(mockFrom).toHaveBeenCalledWith("settings");
      expect(mockUpsert).toHaveBeenCalledWith({
        missed_delivery_enabled: true,
      });
    });
  });
});
