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
    let authCallback: ((event: string, session: Session | null) => void) | null = null;
    const userA: User = {
      id: "operator-a-uuid",
      email: "alice@example.com",
    } as User;
    const sessionA = { user: userA, access_token: "token-a" } as Session;

    const mockTasksA = [
      {
        id: "550e8400-e29b-41d4-a716-446655440001",
        title: "Alice's Secret Task",
        description: "Alice notes",
        priority: 4,
        plan: null,
        labels: [],
        parentId: null,
        completedAt: null,
        createdAt: "2026-08-19T00:00:00Z",
        updatedAt: "2026-08-19T00:00:00Z",
        version: 1,
      },
    ];

    const mockClient = {
      auth: {
        getSession: vi.fn().mockResolvedValue({
          data: { session: sessionA },
          error: null,
        }),
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
            return {
              select: vi.fn().mockResolvedValue({ data: mockTasksA, error: null }),
            };
          }
          return {
            select: vi.fn().mockResolvedValue({ data: [], error: null }),
          };
        }),
      }),
      from: vi.fn().mockImplementation((table: string) => {
        if (table === "comments") {
          return {
            select: vi.fn().mockReturnValue({
              eq: vi.fn().mockResolvedValue({ data: [], error: null }),
            }),
          };
        }
        return {
          select: vi.fn().mockReturnValue({
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

    await waitFor(() => {
      expect(screen.getByText("Alice's Secret Task")).toBeInTheDocument();
    });

    // Sign out via auth callback
    act(() => {
      authCallback?.("SIGNED_OUT", null);
    });

    await waitFor(() => {
      expect(screen.queryByText("Alice's Secret Task")).not.toBeInTheDocument();
    });
  });
});
