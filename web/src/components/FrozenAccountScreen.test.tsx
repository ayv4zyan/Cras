import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { FrozenAccountScreen } from "./FrozenAccountScreen";

describe("FrozenAccountScreen", () => {
  it("explains the frozen account and shows the server deadline", () => {
    render(
      <FrozenAccountScreen
        userEmail="operator@example.com"
        deletionDeadline="2026-08-31T12:00:00Z"
        recoveryAvailable={true}
        onRecover={() => {}}
        onSignOut={() => {}}
      />,
    );

    expect(
      screen.getByRole("heading", { name: /account scheduled for deletion/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(/scheduled purge:/i)).toBeInTheDocument();
  });

  it("offers recovery while the Recovery window is open", () => {
    const onRecover = vi.fn();
    render(
      <FrozenAccountScreen
        deletionDeadline="2026-08-31T12:00:00Z"
        recoveryAvailable={true}
        onRecover={onRecover}
        onSignOut={() => {}}
      />,
    );

    fireEvent.click(
      screen.getByRole("button", { name: /recover my account/i }),
    );
    expect(onRecover).toHaveBeenCalledTimes(1);
  });

  it("disables recovery once the window has closed", () => {
    render(
      <FrozenAccountScreen
        deletionDeadline="2026-08-31T12:00:00Z"
        recoveryAvailable={false}
        onRecover={() => {}}
        onSignOut={() => {}}
      />,
    );

    expect(
      screen.getByRole("button", { name: /recover my account/i }),
    ).toBeDisabled();
    expect(screen.getByText(/recovery window has closed/i)).toBeInTheDocument();
  });

  it("still allows a plain sign-out from the frozen state", () => {
    const onSignOut = vi.fn();
    render(
      <FrozenAccountScreen
        deletionDeadline="2026-08-31T12:00:00Z"
        recoveryAvailable={true}
        onRecover={() => {}}
        onSignOut={onSignOut}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /sign out/i }));
    expect(onSignOut).toHaveBeenCalledTimes(1);
  });
});
