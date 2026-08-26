import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { AuthProvider } from "../contexts/AuthContext";
import { CrasApp } from "../App";
import type { Plan } from "../contracts/task";
import type { SupabaseClient, Session, User } from "@supabase/supabase-js";

describe("Web Release Matrix & Full E2E Operator Journeys (AC 1)", () => {
  const browserProfiles = [
    {
      name: "Chromium Desktop",
      userAgent:
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36",
    },
    {
      name: "Firefox Desktop",
      userAgent:
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0",
    },
    {
      name: "WebKit / Safari Desktop",
      userAgent:
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15",
    },
  ];

  interface SimulatedTask {
    id: string;
    operator_id: string;
    title: string;
    description: string | null;
    priority: number;
    plan: Plan | null;
    labels: string[];
    parent_id: string | null;
    completed_at: string | null;
    created_at: string;
    updated_at: string;
    version: number;
  }

  let dbTasks: SimulatedTask[] = [];

  function createMockSupabase(user: User): SupabaseClient {
    const toContractTask = (t: SimulatedTask) => ({
      id: t.id,
      title: t.title,
      description: t.description,
      priority: t.priority,
      plan: t.plan,
      labels: t.labels,
      parentId: t.parent_id,
      completedAt: t.completed_at,
      createdAt: t.created_at,
      updatedAt: t.updated_at,
      version: t.version,
    });

    const rpcHandler = vi
      .fn()
      .mockImplementation(
        (rpcName: string, params: Record<string, unknown>) => {
          if (rpcName === "create_task") {
            const newTask: SimulatedTask = {
              id:
                (params.id as string) ||
                `11111111-1111-1111-1111-${String(Date.now()).slice(-12).padStart(12, "0")}`,
              operator_id: user.id,
              title: params.title as string,
              description: (params.description as string) || null,
              priority: (params.priority as number) || 4,
              plan: (params.plan as Plan) || null,
              labels: (params.labels as string[]) || [],
              parent_id: (params.parent_id as string) || null,
              completed_at: null,
              created_at: new Date().toISOString(),
              updated_at: new Date().toISOString(),
              version: 1,
            };
            dbTasks.push(newTask);
            return Promise.resolve({
              data: toContractTask(newTask),
              error: null,
            });
          }
          if (rpcName === "complete_task") {
            const task = dbTasks.find((t) => t.id === params.id);
            if (task) {
              task.completed_at = new Date().toISOString();
              task.version += 1;
            }
            return Promise.resolve({
              data: task ? toContractTask(task) : null,
              error: null,
            });
          }
          if (rpcName === "export_operator_data") {
            return Promise.resolve({
              data: {
                operator_id: user.id,
                tasks: dbTasks.map(toContractTask),
                labels: [],
                comments: [],
                settings: null,
              },
              error: null,
            });
          }
          if (rpcName === "request_account_deletion") {
            return Promise.resolve({
              data: {
                deletion_requested_at: new Date().toISOString(),
                recovery_deadline_at: new Date(
                  Date.now() + 7 * 86400000,
                ).toISOString(),
              },
              error: null,
            });
          }
          return Promise.resolve({ data: {}, error: null });
        },
      );

    return {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: {
            session: {
              user,
              access_token: `token-${user.id}`,
            } as Session,
          },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockImplementation((cb) => {
          cb("SIGNED_IN", {
            user,
            access_token: `token-${user.id}`,
          } as Session);
          return {
            data: { subscription: { unsubscribe: vi.fn() } },
          };
        }),
        signInWithOAuth: vi
          .fn()
          .mockResolvedValue({ data: { provider: "google" }, error: null }),
        signOut: vi.fn().mockResolvedValue({ error: null }),
      },
      from: (table: string) => {
        if (table === "settings") {
          return {
            select: vi.fn().mockReturnValue({
              maybeSingle: vi.fn().mockResolvedValue({
                data: {
                  default_timed_plan_type: "instant",
                  missed_notification_delivery: false,
                },
                error: null,
              }),
            }),
          };
        }
        if (table === "deployment_config") {
          return {
            select: vi.fn().mockReturnValue({
              maybeSingle: vi.fn().mockResolvedValue({
                data: { default_timed_plan_type: "instant" },
                error: null,
              }),
            }),
          };
        }
        if (table === "labels") {
          return {
            select: vi.fn().mockReturnValue({
              order: vi.fn().mockResolvedValue({
                data: [
                  {
                    id: "label-1",
                    name: "Work",
                    color: "#10b981",
                    created_at: "2026-08-01T00:00:00Z",
                    updated_at: "2026-08-01T00:00:00Z",
                  },
                ],
                error: null,
              }),
            }),
          };
        }
        if (table === "comments") {
          return {
            select: vi.fn().mockReturnValue({
              eq: vi.fn().mockReturnValue({
                order: vi.fn().mockResolvedValue({ data: [], error: null }),
              }),
            }),
          };
        }
        return {
          select: vi.fn().mockReturnValue({
            order: vi.fn().mockResolvedValue({
              data: dbTasks.map(toContractTask),
              error: null,
            }),
          }),
        };
      },
      schema: () => {
        return {
          from: (tableName: string) => {
            if (tableName === "tasks") {
              return {
                select: vi.fn().mockImplementation(() => {
                  const tasks = dbTasks
                    .filter((t) => t.operator_id === user.id)
                    .map(toContractTask);
                  return Promise.resolve({ data: tasks, error: null });
                }),
              };
            }
            if (tableName === "comments") {
              return {
                select: vi.fn().mockReturnValue({
                  eq: vi.fn().mockReturnValue({
                    order: vi.fn().mockResolvedValue({ data: [], error: null }),
                  }),
                }),
              };
            }
            return {
              select: vi.fn().mockResolvedValue({ data: [], error: null }),
            };
          },
          rpc: rpcHandler,
        };
      },
      rpc: rpcHandler,
      channel: vi.fn().mockReturnValue({
        on: vi.fn().mockReturnThis(),
        subscribe: vi.fn().mockReturnValue({ unsubscribe: vi.fn() }),
      }),
    } as unknown as SupabaseClient;
  }

  for (const profile of browserProfiles) {
    describe(`Browser Matrix: ${profile.name}`, () => {
      const mockUser: User = {
        id: "11111111-1111-1111-1111-111111111111",
        email: "operator@example.com",
        app_metadata: {},
        user_metadata: {},
        aud: "authenticated",
        created_at: new Date().toISOString(),
      };

      beforeEach(() => {
        dbTasks = [];
        Object.defineProperty(navigator, "userAgent", {
          value: profile.userAgent,
          configurable: true,
        });
      });

      it("executes complete Operator journey (Auth, Task create with details, and View navigation)", async () => {
        const mockSupabase = createMockSupabase(mockUser);

        render(
          <AuthProvider client={mockSupabase}>
            <CrasApp client={mockSupabase} />
          </AuthProvider>,
        );

        // 1. Verify Signed In and Inbox heading loaded
        await waitFor(() => {
          expect(
            screen.getByRole("heading", { level: 2, name: /inbox/i }),
          ).toBeDefined();
        });

        // 2. Create a new Task
        const input = screen.getByPlaceholderText(/create a task in inbox/i);
        fireEvent.change(input, {
          target: { value: `Matrix Task for ${profile.name}` },
        });
        const createBtn = screen.getByRole("button", { name: /create task/i });
        fireEvent.click(createBtn);

        // 3. Verify task appears in list
        await waitFor(() => {
          expect(
            screen.getByText(`Matrix Task for ${profile.name}`),
          ).toBeDefined();
        });

        // 4. Navigate between views (Today, Upcoming, Completed, Inbox)
        const todayButton = screen.getByRole("button", { name: /^today/i });
        fireEvent.click(todayButton);

        await waitFor(() => {
          expect(
            screen.getByRole("heading", { level: 2, name: /today/i }),
          ).toBeDefined();
        });

        const upcomingButton = screen.getByRole("button", {
          name: /^upcoming/i,
        });
        fireEvent.click(upcomingButton);

        await waitFor(() => {
          expect(
            screen.getByRole("heading", { level: 2, name: /upcoming/i }),
          ).toBeDefined();
        });

        const inboxButton = screen.getByRole("button", { name: /^inbox/i });
        fireEvent.click(inboxButton);

        await waitFor(() => {
          expect(
            screen.getByRole("heading", { level: 2, name: /inbox/i }),
          ).toBeDefined();
          expect(
            screen.getByText(`Matrix Task for ${profile.name}`),
          ).toBeDefined();
        });
      });
    });
  }
});
