import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { AuthenticatedApp } from "../App";
import { BEST_EFFORT_RELIABILITY_COPY } from "../services/notificationService";
import type {
  SupabaseClient,
  User,
  RealtimeChannel,
} from "@supabase/supabase-js";
import type { Task } from "../contracts/task";

describe("Web Push Notifications Journey & Lifecycle", () => {
  let mockClient: SupabaseClient;
  let mockRpc: ReturnType<typeof vi.fn>;
  let mockFrom: ReturnType<typeof vi.fn>;
  let mockUpsertSettings: ReturnType<typeof vi.fn>;
  let mockChannel: RealtimeChannel;
  const mockUser: User = {
    id: "550e8400-e29b-41d4-a716-446655440099",
    email: "operator@example.com",
    app_metadata: {},
    user_metadata: {},
    aud: "authenticated",
    created_at: new Date().toISOString(),
  };

  const initialTasks: Task[] = [
    {
      id: "550e8400-e29b-41d4-a716-446655440001",
      title: "Untimed Task in Inbox",
      description: null,
      priority: 4,
      plan: null,
      labels: [],
      parentId: null,
      completedAt: null,
      createdAt: "2026-08-20T10:00:00Z",
      updatedAt: "2026-08-20T10:00:00Z",
      version: 1,
    },
  ];

  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();

    mockUpsertSettings = vi.fn().mockResolvedValue({ error: null });

    mockRpc = vi.fn().mockImplementation((name, args) => {
      if (name === "create_task") {
        return Promise.resolve({
          data: {
            id: args.id || "550e8400-e29b-41d4-a716-446655440002",
            title: args.title,
            description: args.description || null,
            priority: args.priority || 4,
            plan: args.plan || null,
            labels: args.labels || [],
            parentId: args.parent_id || null,
            completedAt: null,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            version: 1,
          },
          error: null,
        });
      }
      if (name === "update_task") {
        return Promise.resolve({
          data: {
            id: args.id,
            title: args.title || "Updated Title",
            description: args.description || null,
            priority: args.priority || 4,
            plan: args.plan || null,
            labels: args.labels || [],
            parentId: args.parent_id || null,
            completedAt: null,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            version: (args.expected_version || 1) + 1,
          },
          error: null,
        });
      }
      if (name === "register_or_update_installation") {
        return Promise.resolve({
          data: { id: args.p_id || args.id, is_active: true },
          error: null,
        });
      }
      if (name === "deactivate_installation") {
        return Promise.resolve({ data: true, error: null });
      }
      return Promise.resolve({ data: null, error: null });
    });

    mockFrom = vi.fn().mockImplementation((table) => {
      if (table === "tasks") {
        return {
          select: vi
            .fn()
            .mockResolvedValue({ data: initialTasks, error: null }),
        };
      }
      if (table === "labels") {
        return {
          select: vi.fn().mockReturnValue({
            order: vi.fn().mockResolvedValue({ data: [], error: null }),
          }),
        };
      }
      if (table === "settings") {
        return {
          select: vi.fn().mockReturnValue({
            maybeSingle: vi.fn().mockResolvedValue({
              data: {
                operator_id: mockUser.id,
                default_timed_plan_type: "instant",
                missed_delivery_enabled: false,
              },
              error: null,
            }),
          }),
          upsert: mockUpsertSettings,
        };
      }
      if (table === "deployment_config") {
        return {
          select: vi.fn().mockReturnValue({
            maybeSingle: vi.fn().mockResolvedValue({
              data: {
                id: 1,
                default_timed_plan_type: "instant",
                voice_enabled: true,
              },
              error: null,
            }),
          }),
        };
      }
      if (table === "comments") {
        return {
          select: vi.fn().mockReturnValue({
            eq: vi.fn().mockResolvedValue({ data: [], error: null }),
          }),
        };
      }
      return {
        select: vi.fn().mockResolvedValue({ data: [], error: null }),
      };
    });

    mockChannel = {
      on: vi.fn().mockReturnThis(),
      subscribe: vi.fn().mockImplementation((cb) => {
        if (cb) cb("SUBSCRIBED");
        return mockChannel;
      }),
      unsubscribe: vi.fn(),
    } as unknown as RealtimeChannel;

    mockClient = {
      from: mockFrom,
      rpc: mockRpc,
      schema: vi.fn().mockReturnValue({
        from: mockFrom,
        rpc: mockRpc,
      }),
      channel: vi.fn().mockReturnValue(mockChannel),
      removeChannel: vi.fn(),
    } as unknown as SupabaseClient;
  });

  it("saving an untimed task does NOT trigger the in-context permission modal", async () => {
    render(
      <AuthenticatedApp
        client={mockClient}
        user={mockUser}
        onSignOut={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText("Untimed Task in Inbox")).toBeInTheDocument();
    });

    const createInput = screen.getByTestId("create-task-input");
    fireEvent.change(createInput, { target: { value: "New Untimed Task" } });
    fireEvent.keyDown(createInput, { key: "Enter", code: "Enter" });

    await waitFor(() => {
      expect(screen.getByText("New Untimed Task")).toBeInTheDocument();
    });

    // Permission modal should NOT be shown
    expect(
      screen.queryByRole("heading", { name: /timed task notifications/i }),
    ).not.toBeInTheDocument();
  });

  it("saving the first timed task explains Notifications and requests permission in context", async () => {
    render(
      <AuthenticatedApp
        client={mockClient}
        user={mockUser}
        onSignOut={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText("Untimed Task in Inbox")).toBeInTheDocument();
    });

    // Expand details to add date and time
    const detailsBtn = screen.getByRole("button", { name: /add details/i });
    fireEvent.click(detailsBtn);

    const createInput = screen.getByTestId("create-task-input");
    fireEvent.change(createInput, { target: { value: "Doctor Appointment" } });

    // Set date directly or click quick today
    const dateInput = screen.getByLabelText(/plan date/i);
    fireEvent.change(dateInput, { target: { value: "2026-08-21" } });

    // Set time
    const timeInput = screen.getByLabelText(/plan time/i);
    fireEvent.change(timeInput, { target: { value: "14:30" } });

    // Submit task
    const submitBtn = screen.getByRole("button", { name: /^create task$/i });
    fireEvent.click(submitBtn);

    // In-context permission explanation dialog appears!
    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /timed task notifications/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByText(BEST_EFFORT_RELIABILITY_COPY),
      ).toBeInTheDocument();
    });
  });

  it("Settings modal allows viewing installation status, toggling notifications, and updating missed delivery", async () => {
    render(
      <AuthenticatedApp
        client={mockClient}
        user={mockUser}
        onSignOut={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByLabelText("Settings")).toBeInTheDocument();
    });

    // Open Settings Modal
    const settingsBtn = screen.getByLabelText("Settings");
    fireEvent.click(settingsBtn);

    await waitFor(() => {
      expect(
        screen.getByRole("heading", {
          name: /operator & installation settings/i,
        }),
      ).toBeInTheDocument();
      expect(
        screen.getByText(BEST_EFFORT_RELIABILITY_COPY),
      ).toBeInTheDocument();
      expect(
        screen.getByText(/installation notifications/i),
      ).toBeInTheDocument();
    });

    // Toggle local notifications
    const toggleLocal = screen.getByLabelText(
      /toggle notifications for this device/i,
    );
    fireEvent.click(toggleLocal);
    await waitFor(() => {
      expect(mockRpc).toHaveBeenCalledWith(
        "register_or_update_installation",
        expect.objectContaining({
          p_local_enabled: false,
        }),
      );
    });

    // Toggle missed delivery setting
    const toggleMissed = screen.getByLabelText(
      /toggle missed notification delivery/i,
    );
    fireEvent.click(toggleMissed);
    await waitFor(() => {
      expect(mockUpsertSettings).toHaveBeenCalledWith({
        missed_delivery_enabled: true,
      });
    });

    // Close Settings Modal
    const doneBtn = screen.getByRole("button", { name: /done/i });
    fireEvent.click(doneBtn);

    await waitFor(() => {
      expect(
        screen.queryByRole("heading", {
          name: /operator & installation settings/i,
        }),
      ).not.toBeInTheDocument();
    });
  });

  it("Sign-out deactivates the installation before completing sign out", async () => {
    const callOrder: string[] = [];
    mockRpc.mockImplementation((name) => {
      if (name === "deactivate_installation") {
        callOrder.push("deactivate");
        return Promise.resolve({ data: true, error: null });
      }
      return Promise.resolve({ data: null, error: null });
    });

    const onSignOut = vi.fn().mockImplementation(() => {
      callOrder.push("signOut");
      return Promise.resolve();
    });

    render(
      <AuthenticatedApp
        client={mockClient}
        user={mockUser}
        onSignOut={onSignOut}
      />,
    );

    await waitFor(() => {
      expect(screen.getByLabelText("Sign out")).toBeInTheDocument();
    });

    const signOutBtn = screen.getByLabelText("Sign out");
    fireEvent.click(signOutBtn);

    await waitFor(() => {
      expect(mockRpc).toHaveBeenCalledWith(
        "deactivate_installation",
        expect.objectContaining({
          p_id: expect.any(String),
        }),
      );
      expect(onSignOut).toHaveBeenCalled();
      expect(callOrder).toEqual(["deactivate", "signOut"]);
    });
  });

  it("Sign-out proceeds with onSignOut even if deactivation rejects", async () => {
    mockRpc.mockImplementation((name) => {
      if (name === "deactivate_installation") {
        return Promise.resolve({
          data: null,
          error: { message: "Network offline", code: "PGRST000" },
        });
      }
      return Promise.resolve({ data: null, error: null });
    });

    const onSignOut = vi.fn().mockResolvedValue(undefined);

    render(
      <AuthenticatedApp
        client={mockClient}
        user={mockUser}
        onSignOut={onSignOut}
      />,
    );

    await waitFor(() => {
      expect(screen.getByLabelText("Sign out")).toBeInTheDocument();
    });

    const signOutBtn = screen.getByLabelText("Sign out");
    fireEvent.click(signOutBtn);

    await waitFor(() => {
      expect(onSignOut).toHaveBeenCalled();
    });
  });
});
