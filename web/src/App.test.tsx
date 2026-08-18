import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
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
});
