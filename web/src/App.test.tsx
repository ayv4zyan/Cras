import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor, act } from "@testing-library/react";
import App, { CrasApp } from "./App";
import { AuthProvider } from "./contexts/AuthContext";
import type { SupabaseClient, Session, User } from "@supabase/supabase-js";

describe("Web Client Seam - App Surface", () => {
  it("renders Continue with Google when unauthenticated", async () => {
    render(<App />);

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { level: 1, name: /cras/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByText(/personal task management for independent operators/i),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /continue with google/i }),
      ).toBeInTheDocument();
    });
  });

  it("renders deliberate empty Cras surface with domain navigation and inbox state when authenticated", async () => {
    const mockUser: User = {
      id: "operator-1-uuid",
      email: "operator@example.com",
    } as User;

    const mockClient = {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: {
            session: { user: mockUser, access_token: "token-1" } as Session,
          },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockReturnValue({
          data: { subscription: { unsubscribe: vi.fn() } },
        }),
        signInWithOAuth: vi.fn(),
        signOut: vi.fn(),
      },
      schema: vi.fn().mockReturnValue({
        from: vi.fn().mockReturnValue({
          select: vi.fn().mockResolvedValue({ data: [], error: null }),
        }),
      }),
    } as unknown as SupabaseClient;

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    await waitFor(() => {
      // Brand and Operator context
      expect(
        screen.getByRole("heading", { level: 1, name: /cras/i }),
      ).toBeInTheDocument();
      expect(screen.getByText(/operator task space/i)).toBeInTheDocument();
      expect(screen.getByText("operator@example.com")).toBeInTheDocument();

      // Standard domain views
      expect(
        screen.getByRole("button", { name: /inbox/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /today/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /upcoming/i }),
      ).toBeInTheDocument();

      // Deliberate empty state in Inbox view
      expect(
        screen.getByRole("heading", { level: 2, name: /inbox/i }),
      ).toBeInTheDocument();
      expect(screen.getByText(/no tasks in inbox/i)).toBeInTheDocument();
      expect(screen.getByText(/your task space is clear/i)).toBeInTheDocument();
    });
  });

  it("navigates across Today, Upcoming, and Completed views displaying matching headings and empty states", async () => {
    const mockUser: User = {
      id: "operator-1-uuid",
      email: "operator@example.com",
    } as User;

    const mockClient = {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: {
            session: { user: mockUser, access_token: "token-1" } as Session,
          },
          error: null,
        }),
        onAuthStateChange: vi.fn().mockReturnValue({
          data: { subscription: { unsubscribe: vi.fn() } },
        }),
        signInWithOAuth: vi.fn(),
        signOut: vi.fn(),
      },
      schema: vi.fn().mockReturnValue({
        from: vi.fn().mockReturnValue({
          select: vi.fn().mockResolvedValue({ data: [], error: null }),
        }),
      }),
    } as unknown as SupabaseClient;

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    // 1. Initial Inbox view
    await waitFor(() => {
      expect(
        screen.getByRole("heading", { level: 2, name: /inbox/i }),
      ).toBeInTheDocument();
    });

    // 2. Select Today view
    const todayNavBtn = screen.getByRole("button", { name: /^today/i });
    act(() => {
      todayNavBtn.click();
    });
    await waitFor(() => {
      expect(
        screen.getByRole("heading", { level: 2, name: /^today/i }),
      ).toBeInTheDocument();
      expect(screen.getByText(/no tasks for today/i)).toBeInTheDocument();
    });

    // 3. Select Upcoming view
    const upcomingNavBtn = screen.getByRole("button", { name: /^upcoming/i });
    act(() => {
      upcomingNavBtn.click();
    });
    await waitFor(() => {
      expect(
        screen.getByRole("heading", { level: 2, name: /^upcoming/i }),
      ).toBeInTheDocument();
      expect(screen.getByText(/no upcoming tasks/i)).toBeInTheDocument();
    });

    // 4. Select Completed view
    const completedNavBtn = screen.getByRole("button", { name: /^completed/i });
    act(() => {
      completedNavBtn.click();
    });
    await waitFor(() => {
      expect(
        screen.getByRole("heading", { level: 2, name: /^completed/i }),
      ).toBeInTheDocument();
      expect(screen.getByText(/no completed tasks yet/i)).toBeInTheDocument();
    });
  });

  it("resets task state and selection on account switch and sign out", async () => {
    let authCallback:
      ((event: string, session: Session | null) => void) | null = null;
    let currentSession: Session | null = null;

    const userA: User = {
      id: "operator-a-uuid",
      email: "alice@example.com",
    } as User;
    const sessionA = { user: userA, access_token: "token-a" } as Session;

    const userB: User = {
      id: "operator-b-uuid",
      email: "bob@example.com",
    } as User;
    const sessionB = { user: userB, access_token: "token-b" } as Session;

    const labelAId = "550e8400-e29b-41d4-a716-446655440011";
    const labelBId = "550e8400-e29b-41d4-a716-446655440012";
    const taskAId = "550e8400-e29b-41d4-a716-446655440001";
    const taskBId = "550e8400-e29b-41d4-a716-446655440002";
    const commentAId = "550e8400-e29b-41d4-a716-446655440021";
    const commentBId = "550e8400-e29b-41d4-a716-446655440022";

    const mockTasksA = [
      {
        id: taskAId,
        title: "Alice's Secret Task",
        description: "Alice notes",
        priority: 4,
        plan: null,
        labels: [labelAId],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-19T00:00:00Z",
        updatedAt: "2026-08-19T00:00:00Z",
        version: 1,
      },
    ];

    const mockLabelsA = [
      {
        id: labelAId,
        name: "Alice Label",
        color: "#ff0000",
        created_at: "2026-08-19T00:00:00Z",
        updated_at: "2026-08-19T00:00:00Z",
      },
    ];

    const mockCommentsA = [
      {
        id: commentAId,
        taskId: taskAId,
        content: "Alice Comment Note",
        createdAt: "2026-08-19T00:00:00Z",
      },
    ];

    const mockTasksB = [
      {
        id: taskBId,
        title: "Bob's Public Task",
        description: "Bob notes",
        priority: 2,
        plan: null,
        labels: [labelBId],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-19T00:00:00Z",
        updatedAt: "2026-08-19T00:00:00Z",
        version: 1,
      },
    ];

    const mockLabelsB = [
      {
        id: labelBId,
        name: "Bob Label",
        color: "#00ff00",
        created_at: "2026-08-19T00:00:00Z",
        updated_at: "2026-08-19T00:00:00Z",
      },
    ];

    const mockCommentsB = [
      {
        id: commentBId,
        taskId: taskBId,
        content: "Bob Comment Note",
        createdAt: "2026-08-19T00:00:00Z",
      },
    ];

    currentSession = sessionA;

    const mockClient = {
      auth: {
        getSession: vi.fn().mockImplementation(() =>
          Promise.resolve({
            data: { session: currentSession },
            error: null,
          }),
        ),
        onAuthStateChange: vi.fn().mockImplementation((cb) => {
          authCallback = cb;
          return { data: { subscription: { unsubscribe: vi.fn() } } };
        }),
        signInWithOAuth: vi.fn(),
        signOut: vi.fn(),
      },
      schema: vi.fn().mockReturnValue({
        from: vi.fn().mockImplementation((table: string) => {
          if (table === "tasks") {
            const isUserB = currentSession?.user?.id === userB.id;
            return {
              select: vi.fn().mockResolvedValue({
                data: isUserB ? mockTasksB : mockTasksA,
                error: null,
              }),
            };
          }
          if (table === "comments") {
            return {
              select: vi.fn().mockReturnValue({
                order: vi.fn().mockImplementation(() => ({
                  eq: vi
                    .fn()
                    .mockImplementation((_col: string, taskId: string) => {
                      if (taskId === mockTasksA[0].id) {
                        return Promise.resolve({
                          data: mockCommentsA,
                          error: null,
                        });
                      }
                      if (taskId === mockTasksB[0].id) {
                        return Promise.resolve({
                          data: mockCommentsB,
                          error: null,
                        });
                      }
                      return Promise.resolve({ data: [], error: null });
                    }),
                })),
              }),
            };
          }
          return {
            select: vi.fn().mockResolvedValue({ data: [], error: null }),
          };
        }),
      }),
      from: vi.fn().mockImplementation((table: string) => {
        if (table === "labels") {
          const isUserB = currentSession?.user?.id === userB.id;
          return {
            select: vi.fn().mockReturnValue({
              order: vi.fn().mockResolvedValue({
                data: isUserB ? mockLabelsB : mockLabelsA,
                error: null,
              }),
            }),
          };
        }
        if (table === "comments") {
          return {
            select: vi.fn().mockReturnValue({
              order: vi.fn().mockReturnValue({
                eq: vi.fn().mockResolvedValue({ data: [], error: null }),
              }),
              eq: vi.fn().mockResolvedValue({ data: [], error: null }),
            }),
          };
        }
        return {
          select: vi.fn().mockReturnValue({
            eq: vi.fn().mockReturnValue({
              maybeSingle: vi
                .fn()
                .mockResolvedValue({ data: null, error: null }),
            }),
            maybeSingle: vi.fn().mockResolvedValue({ data: null, error: null }),
          }),
        };
      }),
    } as unknown as SupabaseClient;

    render(
      <AuthProvider client={mockClient}>
        <CrasApp client={mockClient} />
      </AuthProvider>,
    );

    // 1. Account A data renders
    await waitFor(() => {
      expect(screen.getByText("alice@example.com")).toBeInTheDocument();
      expect(screen.getByText("Alice's Secret Task")).toBeInTheDocument();
      expect(screen.getAllByText("Alice Label").length).toBeGreaterThan(0);
    });

    // Open detail modal for Task A to view Alice's comments
    act(() => {
      screen.getByText("Alice's Secret Task").click();
    });

    await waitFor(() => {
      expect(screen.getByText("Alice Comment Note")).toBeInTheDocument();
    });

    // 2. Sign out via auth callback
    act(() => {
      currentSession = null;
      authCallback?.("SIGNED_OUT", null);
    });

    await waitFor(() => {
      expect(screen.queryByText("alice@example.com")).not.toBeInTheDocument();
      expect(screen.queryByText("Alice's Secret Task")).not.toBeInTheDocument();
      expect(screen.queryAllByText("Alice Label")).toHaveLength(0);
      expect(screen.queryByText("Alice Comment Note")).not.toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /continue with google/i }),
      ).toBeInTheDocument();
    });

    // 3. Sign in Account B via auth callback
    act(() => {
      currentSession = sessionB;
      authCallback?.("SIGNED_IN", sessionB);
    });

    // 4. Assert Account A data does not render, and Account B data renders properly
    await waitFor(() => {
      expect(screen.getByText("bob@example.com")).toBeInTheDocument();
      expect(screen.getByText("Bob's Public Task")).toBeInTheDocument();
      expect(screen.getAllByText("Bob Label").length).toBeGreaterThan(0);
      expect(screen.queryByText("alice@example.com")).not.toBeInTheDocument();
      expect(screen.queryByText("Alice's Secret Task")).not.toBeInTheDocument();
      expect(screen.queryAllByText("Alice Label")).toHaveLength(0);
      expect(screen.queryByText("Alice Comment Note")).not.toBeInTheDocument();
    });

    // Open detail modal for Task B to view Bob's comments
    act(() => {
      screen.getByText("Bob's Public Task").click();
    });

    await waitFor(() => {
      expect(screen.getByText("Bob Comment Note")).toBeInTheDocument();
      expect(screen.queryByText("Alice Comment Note")).not.toBeInTheDocument();
    });
  });
});
