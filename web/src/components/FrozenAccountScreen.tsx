import React from "react";
import { AlertTriangle, LogOut, Loader2 } from "lucide-react";

export interface FrozenAccountScreenProps {
  readonly userEmail?: string;
  readonly deletionDeadline: string | null;
  readonly recoveryAvailable: boolean;
  readonly isRecovering?: boolean;
  readonly errorMessage?: string | null;
  readonly onRecover: () => void;
  readonly onSignOut: () => void;
}

export function FrozenAccountScreen({
  userEmail,
  deletionDeadline,
  recoveryAvailable,
  isRecovering = false,
  errorMessage,
  onRecover,
  onSignOut,
}: FrozenAccountScreenProps): React.JSX.Element {
  const deadlineLabel = deletionDeadline
    ? new Date(deletionDeadline).toLocaleString(undefined, {
        dateStyle: "medium",
        timeStyle: "short",
      })
    : null;

  return (
    <div className="flex h-screen w-screen items-center justify-center bg-background text-foreground p-4">
      <div className="w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-xl space-y-5 text-card-foreground">
        <div className="flex items-center space-x-2.5">
          <AlertTriangle className="h-5 w-5 text-destructive" />
          <h1 className="text-base font-semibold text-foreground">
            Account scheduled for deletion
          </h1>
        </div>

        {errorMessage && (
          <div
            role="alert"
            className="rounded-md bg-destructive/10 p-3 text-xs text-destructive"
          >
            {errorMessage}
          </div>
        )}

        <p className="text-xs text-muted-foreground leading-relaxed">
          {userEmail ? (
            <>
              The account{" "}
              <span className="font-medium text-foreground">
                {userEmail}
              </span>{" "}
            </>
          ) : (
            "This account "
          )}
          was confirmed for deletion. Access, Notifications, and syncing are
          frozen. Server data is retained only until permanent purge.
        </p>

        {deadlineLabel && (
          <p className="text-xs text-muted-foreground">
            Scheduled purge:{" "}
            <span className="font-medium text-foreground">{deadlineLabel}</span>
          </p>
        )}

        {recoveryAvailable ? (
          <p className="text-xs text-muted-foreground leading-relaxed">
            You can recover this account before the deadline using the same
            Google identity. Recovery restores your data and schedules only
            future Notifications.
          </p>
        ) : (
          <p className="text-xs text-muted-foreground leading-relaxed">
            The Recovery window has closed for this account.
          </p>
        )}

        <div className="flex flex-col space-y-2 pt-1">
          <button
            type="button"
            onClick={onRecover}
            disabled={!recoveryAvailable || isRecovering}
            className="w-full flex items-center justify-center space-x-2 px-3 py-2 rounded-md bg-primary text-primary-foreground text-xs font-semibold hover:opacity-90 transition-opacity cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isRecovering && <Loader2 className="h-3 w-3 animate-spin" />}
            <span>Recover my account</span>
          </button>
          <button
            type="button"
            onClick={onSignOut}
            className="w-full flex items-center justify-center space-x-2 px-3 py-2 rounded-md border border-border text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors cursor-pointer"
          >
            <LogOut className="h-3.5 w-3.5" />
            <span>Sign out</span>
          </button>
        </div>
      </div>
    </div>
  );
}
