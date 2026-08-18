import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { SignInScreen } from "./SignInScreen";

describe("SignInScreen Component", () => {
  it("renders Cras brand and Continue with Google button", () => {
    const handleSignIn = vi.fn();
    render(<SignInScreen onSignInWithGoogle={handleSignIn} />);

    expect(
      screen.getByRole("heading", { level: 1, name: /cras/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(/personal task management/i)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /continue with google/i }),
    ).toBeInTheDocument();
  });

  it("calls onSignInWithGoogle when button is clicked", () => {
    const handleSignIn = vi.fn();
    render(<SignInScreen onSignInWithGoogle={handleSignIn} />);

    fireEvent.click(
      screen.getByRole("button", { name: /continue with google/i }),
    );
    expect(handleSignIn).toHaveBeenCalledTimes(1);
  });
});
