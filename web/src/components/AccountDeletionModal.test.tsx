import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { AccountDeletionModal } from "./AccountDeletionModal";

describe("AccountDeletionModal", () => {
  let onReauthenticate: ReturnType<typeof vi.fn>;
  let onDownloadExport: ReturnType<typeof vi.fn>;
  let onConfirmDeletion: ReturnType<typeof vi.fn>;
  let onClose: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    onReauthenticate = vi.fn();
    onDownloadExport = vi.fn().mockResolvedValue(undefined);
    onConfirmDeletion = vi.fn().mockResolvedValue(undefined);
    onClose = vi.fn();
  });

  function renderModal(overrides?: {
    initialStep?: "overview" | "reauthenticate" | "confirm";
  }) {
    return render(
      <AccountDeletionModal
        isOpen={true}
        onClose={onClose}
        userEmail="operator@example.com"
        initialStep={overrides?.initialStep ?? "overview"}
        onReauthenticate={onReauthenticate}
        onDownloadExport={onDownloadExport}
        onConfirmDeletion={onConfirmDeletion}
      />,
    );
  }

  it("introduces the deletion decision and the seven-day Recovery window", () => {
    renderModal();

    expect(
      screen.getByRole("heading", { name: /delete your cras account/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(/seven days/i)).toBeInTheDocument();
  });

  it("offers the data export before any confirmation is possible", async () => {
    renderModal();

    const downloadButton = screen.getByRole("button", {
      name: /download data export/i,
    });
    expect(downloadButton).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /delete account/i }),
    ).not.toBeInTheDocument();

    fireEvent.click(downloadButton);
    await waitFor(() => expect(onDownloadExport).toHaveBeenCalledTimes(1));
  });

  it("requires fresh Google reauthentication before showing the destructive confirmation", () => {
    renderModal();

    fireEvent.click(
      screen.getByRole("button", { name: /continue to verification/i }),
    );

    expect(screen.getByText(/sign in with google again/i)).toBeInTheDocument();

    fireEvent.click(
      screen.getByRole("button", { name: /continue with google/i }),
    );
    expect(onReauthenticate).toHaveBeenCalledTimes(1);
    expect(onConfirmDeletion).not.toHaveBeenCalled();
  });

  it("recovers when starting reauthentication rejects instead of stranding the busy state", async () => {
    onReauthenticate = vi.fn().mockRejectedValue(new Error("Popup blocked"));

    renderModal();
    fireEvent.click(
      screen.getByRole("button", { name: /continue to verification/i }),
    );
    fireEvent.click(
      screen.getByRole("button", { name: /continue with google/i }),
    );

    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: /continue with google/i }),
      ).toBeEnabled(),
    );
    expect(screen.getByText(/popup blocked/i)).toBeInTheDocument();
  });

  it("jumps straight to confirmation when reauthentication already succeeded", () => {
    renderModal({ initialStep: "confirm" });

    expect(
      screen.getByRole("button", { name: /delete account/i }),
    ).toBeDisabled();
  });

  it("enables the destructive confirmation only after explicit acknowledgement", () => {
    renderModal({ initialStep: "confirm" });

    const deleteButton = screen.getByRole("button", {
      name: /delete account/i,
    });
    expect(deleteButton).toBeDisabled();

    fireEvent.click(
      screen.getByLabelText(
        /i understand my account will be permanently deleted/i,
      ),
    );

    expect(deleteButton).toBeEnabled();
  });

  it("confirms the deletion exactly once and surfaces a busy state", async () => {
    let resolveConfirmation: () => void = () => {};
    onConfirmDeletion.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          resolveConfirmation = resolve;
        }),
    );
    renderModal({ initialStep: "confirm" });

    fireEvent.click(
      screen.getByLabelText(
        /i understand my account will be permanently deleted/i,
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: /delete account/i }));

    expect(onConfirmDeletion).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: /deleting/i })).toBeDisabled();

    resolveConfirmation();
    await waitFor(() =>
      expect(
        screen.queryByRole("button", { name: /deleting/i }),
      ).not.toBeInTheDocument(),
    );
  });

  it("shows the failure message when the server refuses the confirmation", async () => {
    onConfirmDeletion.mockRejectedValue(new Error("Session expired"));

    renderModal({ initialStep: "confirm" });
    fireEvent.click(
      screen.getByLabelText(
        /i understand my account will be permanently deleted/i,
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: /delete account/i }));

    await waitFor(() =>
      expect(screen.getByText(/session expired/i)).toBeInTheDocument(),
    );
  });

  it("keeps sign-out out of the deletion flow", () => {
    renderModal();

    expect(
      screen.queryByRole("button", { name: /sign out/i }),
    ).not.toBeInTheDocument();
  });
});
