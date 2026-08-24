import { describe, it, expect, beforeEach, vi, afterEach } from "vitest";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
} from "@testing-library/react";
import { AuthenticatedApp } from "../App";
import type {
  SupabaseClient,
  User,
  RealtimeChannel,
} from "@supabase/supabase-js";
import type { Task } from "../contracts/task";
import { getStorageKey } from "../services/outboxService";

const OPERATOR_ID = "550e8400-e29b-41d4-a716-446655440099";
const DEADLINE = "2026-08-31T12:00:00Z";

describe("Account deletion & recovery journey", () => {
  let mockClient: SupabaseClient;
  let mockRpc: ReturnType<typeof vi.fn>;
  let mockFrom: ReturnType<typeof vi.fn>;
  let mockChannel: RealtimeChannel;
  let fetchMock: ReturnType<typeof vi.fn>;
  let lifecycleResponse: (action: string) => {
    status: number;
    body: unknown;
  };
  const mockUser: User = {
    id: OPERATOR_ID,
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

  function seedLocalData() {
    localStorage.setItem(
      getStorageKey(OPERATOR_ID),
      JSON.stringify([
        {
          id: "pending-item",
          type: "create",
          task: initialTasks[0],
          params: { title: "Offline create" },
          createdAt: "2026-08-24T00:00:00Z",
        },
      ]),
    );
    localStorage.setItem("cras_effective_default_timed_plan_type", "floating");
    sessionStorage.setItem("cras_unsubmitted_task_input", "half typed draft");
  }

  function expectLocalDataCleared() {
    expect(localStorage.getItem(getStorageKey(OPERATOR_ID))).toBeNull();
    expect(
      localStorage.getItem("cras_effective_default_timed_plan_type"),
    ).toBeNull();
    expect(sessionStorage.getItem("cras_unsubmitted_task_input")).toBeNull();
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    vi.stubGlobal("fetch", (fetchMock = vi.fn()));

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
      if (name === "register_or_update_installation") {
        return Promise.resolve({ data: { id: "inst-1" }, error: null });
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
                operator_id: OPERATOR_ID,
                default_timed_plan_type: "instant",
                missed_delivery_enabled: false,
              },
              error: null,
            }),
          }),
          upsert: vi.fn().mockResolvedValue({ error: null }),
        };
      }
      if (table === "deployment_config") {
        return {
          select: vi.fn().mockReturnValue({
            maybeSingle: vi.fn().mockResolvedValue({
              data: { id: 1, default_timed_plan_type: "instant" },
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
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: { session: { access_token: "session-token" } },
          error: null,
        }),
      },
      supabaseUrl: "https://test.supabase.co",
      from: mockFrom,
      rpc: mockRpc,
      schema: vi.fn().mockReturnValue({ from: mockFrom, rpc: mockRpc }),
      channel: vi.fn().mockReturnValue(mockChannel),
      removeChannel: vi.fn(),
      functions: {
        invoke: async (
          name: string,
          options?: { headers?: Record<string, string>; body?: unknown },
        ) => {
          const response = await fetch(
            `https://test.supabase.co/functions/v1/${name}`,
            {
              method: "POST",
              headers: {
                "Content-Type": "application/json",
                ...(options?.headers ?? {}),
              },
              body: JSON.stringify(options?.body ?? {}),
            },
          );
          const data = await response.json().catch(() => null);
          return { data, error: null };
        },
      },
    } as unknown as SupabaseClient;

    lifecycleResponse = () => ({ status: 200, body: {} });
    fetchMock.mockImplementation(async (_url: string, init?: RequestInit) => {
      const action = JSON.parse(String(init?.body ?? "{}")).action as string;
      const response = lifecycleResponse(action);
      return new Response(JSON.stringify(response.body), {
        status: response.status,
      });
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("keeps working normally while the account is active", async () => {
    lifecycleResponse = (action) =>
      action === "status"
        ? {
            status: 200,
            body: {
              deletionState: "active",
              deletionDeadline: null,
              recoveryAvailable: false,
            },
          }
        : { status: 400, body: { error: `unmocked action: ${action}` } };

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

    const lifecycleCalls = fetchMock.mock.calls.filter(([url]) =>
      String(url).includes("account-lifecycle"),
    );
    expect(lifecycleCalls.length).toBeGreaterThan(0);
  });

  it("wipes cache, Outbox, drafts, and recordings when it observes the frozen account", async () => {
    seedLocalData();
    // Keep the Outbox flush from completing so it cannot race the wipe.
    mockRpc.mockImplementation(() => new Promise(() => {}));
    lifecycleResponse = (action) =>
      action === "status"
        ? {
            status: 200,
            body: {
              deletionState: "pending_deletion",
              deletionDeadline: DEADLINE,
              recoveryAvailable: true,
            },
          }
        : { status: 400, body: { error: `unmocked action: ${action}` } };

    render(
      <AuthenticatedApp
        client={mockClient}
        user={mockUser}
        onSignOut={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("heading", {
          name: /account scheduled for deletion/i,
        }),
      ).toBeInTheDocument();
    });

    expect(screen.getByText(/scheduled purge:/i)).toBeInTheDocument();
    expectLocalDataCleared();
    expect(screen.queryByText("Untimed Task in Inbox")).not.toBeInTheDocument();
  });

  it("recovers inside the window and hands control back to the app", async () => {
    lifecycleResponse = (action) =>
      action === "status"
        ? {
            status: 200,
            body: {
              deletionState: "pending_deletion",
              deletionDeadline: DEADLINE,
              recoveryAvailable: true,
            },
          }
        : action === "recover-account"
          ? { status: 200, body: { recovered: true } }
          : { status: 400, body: { error: `unmocked action: ${action}` } };
    const onRecovered = vi.fn();

    render(
      <AuthenticatedApp
        client={mockClient}
        user={mockUser}
        onSignOut={vi.fn()}
        onRecovered={onRecovered}
      />,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /recover my account/i }),
      ).toBeEnabled();
    });

    fireEvent.click(
      screen.getByRole("button", { name: /recover my account/i }),
    );

    await waitFor(() => expect(onRecovered).toHaveBeenCalledTimes(1));

    const recoverCall = fetchMock.mock.calls.find(([, init]) =>
      String(init?.body).includes("recover-account"),
    );
    expect(recoverCall).toBeDefined();
  });

  it("refuses recovery once the Recovery window has closed", async () => {
    lifecycleResponse = (action) =>
      action === "status"
        ? {
            status: 200,
            body: {
              deletionState: "pending_deletion",
              deletionDeadline: DEADLINE,
              recoveryAvailable: false,
            },
          }
        : { status: 400, body: { error: `unmocked action: ${action}` } };

    render(
      <AuthenticatedApp
        client={mockClient}
        user={mockUser}
        onSignOut={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /recover my account/i }),
      ).toBeDisabled();
    });
    expect(screen.getByText(/recovery window has closed/i)).toBeInTheDocument();
  });

  describe("deliberate deletion flow", () => {
    function renderWithHandlers() {
      const onSignOut = vi.fn().mockResolvedValue(undefined);
      const onSignInWithGoogle = vi.fn().mockResolvedValue(undefined);
      render(
        <AuthenticatedApp
          client={mockClient}
          user={mockUser}
          onSignOut={onSignOut}
          onSignInWithGoogle={onSignInWithGoogle}
        />,
      );
      return { onSignOut, onSignInWithGoogle };
    }

    async function openDeletionFlow() {
      await waitFor(() => {
        expect(screen.getByText("Untimed Task in Inbox")).toBeInTheDocument();
      });
      fireEvent.click(screen.getByLabelText("Settings"));
      fireEvent.click(await screen.findByLabelText("Delete account"));
      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /continue to verification/i }),
        ).toBeEnabled();
      });
    }

    it("stays distinct from sign-out and demands fresh Google reauthentication", async () => {
      lifecycleResponse = (action) =>
        action === "status"
          ? {
              status: 200,
              body: {
                deletionState: "active",
                deletionDeadline: null,
                recoveryAvailable: false,
              },
            }
          : action === "request-deletion"
            ? {
                status: 200,
                body: {
                  confirmed: true,
                  deletionState: "pending_deletion",
                  deletionDeadline: DEADLINE,
                  sessionsRevoked: true,
                },
              }
            : { status: 400, body: { error: `unmocked action: ${action}` } };
      const { onSignInWithGoogle } = renderWithHandlers();

      await openDeletionFlow();

      expect(screen.getByText(/seven days/i)).toBeInTheDocument();

      fireEvent.click(
        screen.getByRole("button", { name: /continue to verification/i }),
      );
      fireEvent.click(
        screen.getByRole("button", { name: /continue with google/i }),
      );

      expect(onSignInWithGoogle).toHaveBeenCalledTimes(1);
      expect(sessionStorage.getItem("cras_reauth_intent")).toBe(OPERATOR_ID);

      const lifecycleCall = fetchMock.mock.calls.find(([url]) =>
        String(url).includes("account-lifecycle"),
      );
      expect(lifecycleCall).toBeDefined();
      const headers = (lifecycleCall?.[1] as RequestInit).headers as Record<
        string,
        string
      >;
      expect(headers.Authorization).toBe("Bearer session-token");
    });

    it("downloads the export before confirmation is reachable", async () => {
      lifecycleResponse = (action) =>
        action === "status"
          ? {
              status: 200,
              body: {
                deletionState: "active",
                deletionDeadline: null,
                recoveryAvailable: false,
              },
            }
          : action === "request-deletion"
            ? {
                status: 200,
                body: {
                  confirmed: true,
                  deletionState: "pending_deletion",
                  deletionDeadline: DEADLINE,
                  sessionsRevoked: true,
                },
              }
            : { status: 400, body: { error: `unmocked action: ${action}` } };
      const exportSnapshot = JSON.stringify({
        exportedAt: "2026-08-24T10:00:00Z",
        tasks: [],
        labels: [],
        taskLabels: [],
        comments: [],
        settings: null,
      });
      mockRpc.mockImplementation((name) => {
        if (name === "export_operator_data") {
          return Promise.resolve({ data: exportSnapshot, error: null });
        }
        return Promise.resolve({ data: null, error: null });
      });
      const createObjectUrl = vi.fn((blob: Blob) => {
        void blob;
        return "blob:mock";
      });
      Object.defineProperty(URL, "createObjectURL", {
        value: createObjectUrl,
        configurable: true,
        writable: true,
      });
      const anchorClick = vi
        .spyOn(HTMLAnchorElement.prototype, "click")
        .mockImplementation(() => {});

      try {
        renderWithHandlers();
        await openDeletionFlow();

        fireEvent.click(
          screen.getByRole("button", { name: /download data export/i }),
        );

        await waitFor(() => expect(anchorClick).toHaveBeenCalled());
        expect(createObjectUrl).toHaveBeenCalledTimes(1);
        const blob = createObjectUrl.mock.calls[0][0] as Blob;
        expect(await blob.text()).toBe(exportSnapshot);
      } finally {
        Object.defineProperty(URL, "createObjectURL", {
          value: undefined,
          configurable: true,
          writable: true,
        });
        anchorClick.mockRestore();
      }
    });

    it("clears local data and signs out after explicit destructive confirmation", async () => {
      seedLocalData();
      lifecycleResponse = (action) =>
        action === "status"
          ? {
              status: 200,
              body: {
                deletionState: "active",
                deletionDeadline: null,
                recoveryAvailable: false,
              },
            }
          : action === "request-deletion"
            ? {
                status: 200,
                body: {
                  confirmed: true,
                  deletionState: "pending_deletion",
                  deletionDeadline: DEADLINE,
                  sessionsRevoked: true,
                },
              }
            : { status: 400, body: { error: `unmocked action: ${action}` } };
      const { onSignInWithGoogle } = renderWithHandlers();
      await openDeletionFlow();

      fireEvent.click(
        screen.getByRole("button", { name: /continue to verification/i }),
      );
      fireEvent.click(
        screen.getByRole("button", { name: /continue with google/i }),
      );

      expect(onSignInWithGoogle).toHaveBeenCalledTimes(1);
      expect(sessionStorage.getItem("cras_reauth_intent")).toBe(OPERATOR_ID);

      // Simulate the browser returning from Google: the app boots again and
      // finds the staged intent for the same identity.
      cleanup();
      const resumed = renderWithHandlers();
      await waitFor(() => {
        expect(
          screen.getByRole("dialog", { name: /delete your cras account/i }),
        ).toBeInTheDocument();
      });
      expect(screen.getByLabelText(/permanently deleted/i)).toBeInTheDocument();

      fireEvent.click(screen.getByRole("button", { name: /delete account/i }));
      expect(
        screen.getByRole("button", { name: /delete account/i }),
      ).toBeDisabled();

      fireEvent.click(screen.getByLabelText(/permanently deleted/i));
      fireEvent.click(screen.getByRole("button", { name: /delete account/i }));

      await waitFor(() => expect(resumed.onSignOut).toHaveBeenCalledTimes(1));

      const deletionCall = fetchMock.mock.calls.find(([, init]) =>
        String(init?.body).includes("request-deletion"),
      );
      expect(deletionCall).toBeDefined();
      expectLocalDataCleared();
    });

    it("discards the staged identity when the returning identity differs", async () => {
      lifecycleResponse = (action) =>
        action === "status"
          ? {
              status: 200,
              body: {
                deletionState: "active",
                deletionDeadline: null,
                recoveryAvailable: false,
              },
            }
          : action === "request-deletion"
            ? {
                status: 200,
                body: {
                  confirmed: true,
                  deletionState: "pending_deletion",
                  deletionDeadline: DEADLINE,
                  sessionsRevoked: true,
                },
              }
            : { status: 400, body: { error: `unmocked action: ${action}` } };
      sessionStorage.setItem("cras_reauth_intent", "someone-else");

      renderWithHandlers();

      await waitFor(() => {
        expect(screen.getByText("Untimed Task in Inbox")).toBeInTheDocument();
      });
      expect(
        screen.queryByRole("dialog", { name: /delete your cras account/i }),
      ).not.toBeInTheDocument();
    });
  });
});
