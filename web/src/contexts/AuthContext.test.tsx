import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  render,
  screen,
  waitFor,
  act,
  fireEvent,
} from "@testing-library/react";
import { AuthProvider } from "./AuthContext";
import { useAuth } from "./useAuth";
import type { SupabaseClient, Session, User } from "@supabase/supabase-js";

function TestConsumer() {
  const { session, user, isLoading, signInWithGoogle, signOut } = useAuth();
  if (isLoading) return <div>Loading auth...</div>;
  if (!session) {
    return (
      <div>
        <span>Unauthenticated</span>
        <button onClick={() => signInWithGoogle()}>Sign In with Google</button>
      </div>
    );
  }
  return (
    <div>
      <span>Operator: {user?.email}</span>
      <button onClick={() => signOut()}>Sign Out</button>
    </div>
  );
}

describe("Auth Context Seam", () => {
  let mockGetSession: ReturnType<typeof vi.fn>;
  let mockOnAuthStateChange: ReturnType<typeof vi.fn>;
  let mockSignInWithOAuth: ReturnType<typeof vi.fn>;
  let mockSignOut: ReturnType<typeof vi.fn>;
  let mockClient: SupabaseClient;
  let authStateCallback:
    ((event: string, session: Session | null) => void) | null = null;

  beforeEach(() => {
    authStateCallback = null;
    mockGetSession = vi
      .fn()
      .mockResolvedValue({ data: { session: null }, error: null });
    mockOnAuthStateChange = vi.fn().mockImplementation((cb) => {
      authStateCallback = cb;
      return {
        data: {
          subscription: {
            unsubscribe: vi.fn(),
          },
        },
      };
    });
    mockSignInWithOAuth = vi.fn().mockResolvedValue({
      data: { provider: "google", url: "https://auth.example.com" },
      error: null,
    });
    mockSignOut = vi.fn().mockResolvedValue({ error: null });

    mockClient = {
      auth: {
        getSession: mockGetSession,
        onAuthStateChange: mockOnAuthStateChange,
        signInWithOAuth: mockSignInWithOAuth,
        signOut: mockSignOut,
      },
    } as unknown as SupabaseClient;
  });

  it("renders unauthenticated state when no session exists", async () => {
    render(
      <AuthProvider client={mockClient}>
        <TestConsumer />
      </AuthProvider>,
    );

    expect(screen.getByText("Loading auth...")).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText("Unauthenticated")).toBeInTheDocument();
    });
  });

  it("automatically restores returning browser session on mount", async () => {
    const existingSession = {
      user: { id: "op-1", email: "operator@example.com" } as User,
      access_token: "token-1",
    } as Session;

    mockGetSession.mockResolvedValueOnce({
      data: { session: existingSession },
      error: null,
    });

    render(
      <AuthProvider client={mockClient}>
        <TestConsumer />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(
        screen.getByText("Operator: operator@example.com"),
      ).toBeInTheDocument();
    });
  });

  it("triggers Google OAuth on signInWithGoogle", async () => {
    render(
      <AuthProvider client={mockClient}>
        <TestConsumer />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Unauthenticated")).toBeInTheDocument();
    });

    fireEvent.click(
      screen.getByRole("button", { name: /sign in with google/i }),
    );

    expect(mockSignInWithOAuth).toHaveBeenCalledWith(
      expect.objectContaining({
        provider: "google",
      }),
    );
  });

  it("signs out and updates state", async () => {
    const existingSession = {
      user: { id: "op-1", email: "operator@example.com" } as User,
      access_token: "token-1",
    } as Session;

    mockGetSession.mockResolvedValueOnce({
      data: { session: existingSession },
      error: null,
    });

    render(
      <AuthProvider client={mockClient}>
        <TestConsumer />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(
        screen.getByText("Operator: operator@example.com"),
      ).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole("button", { name: /sign out/i }));

    expect(mockSignOut).toHaveBeenCalled();

    // Trigger auth state change callback for SIGNED_OUT
    act(() => {
      authStateCallback?.("SIGNED_OUT", null);
    });

    await waitFor(() => {
      expect(screen.getByText("Unauthenticated")).toBeInTheDocument();
    });
  });
});
