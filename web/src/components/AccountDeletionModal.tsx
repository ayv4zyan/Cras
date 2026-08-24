import React, { useState, useEffect, useRef } from "react";
import { X, AlertTriangle, Download, Loader2, ShieldCheck } from "lucide-react";

export type DeletionFlowStep = "overview" | "reauthenticate" | "confirm";

export interface AccountDeletionModalProps {
  readonly isOpen: boolean;
  readonly onClose: () => void;
  readonly userEmail?: string;
  readonly initialStep?: DeletionFlowStep;
  readonly onReauthenticate: () => void | Promise<void>;
  readonly onDownloadExport: () => Promise<void>;
  readonly onConfirmDeletion: () => Promise<void>;
}

export function AccountDeletionModal({
  isOpen,
  onClose,
  userEmail,
  initialStep = "overview",
  onReauthenticate,
  onDownloadExport,
  onConfirmDeletion,
}: AccountDeletionModalProps): React.JSX.Element | null {
  const [step, setStep] = useState<DeletionFlowStep>(initialStep);
  const [acknowledged, setAcknowledged] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const [isReauthing, setIsReauthing] = useState(false);
  const [isConfirming, setIsConfirming] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const modalRef = useRef<HTMLDivElement>(null);
  const previouslyFocusedRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    previouslyFocusedRef.current = document.activeElement as HTMLElement | null;
    setStep(initialStep);
    setAcknowledged(false);
    setIsExporting(false);
    setIsReauthing(false);
    setIsConfirming(false);
    setErrorMessage(null);
    return () => {
      previouslyFocusedRef.current?.focus();
      previouslyFocusedRef.current = null;
    };
  }, [isOpen, initialStep]);

  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
        return;
      }
      if (event.key === "Tab" && modalRef.current) {
        const focusable = modalRef.current.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        );
        if (focusable.length === 0) return;
        const firstElement = focusable[0];
        const lastElement = focusable[focusable.length - 1];
        if (event.shiftKey) {
          if (document.activeElement === firstElement) {
            lastElement.focus();
            event.preventDefault();
          }
        } else {
          if (document.activeElement === lastElement) {
            firstElement.focus();
            event.preventDefault();
          }
        }
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [isOpen, onClose]);

  useEffect(() => {
    if (!isOpen) return;
    const timeout = setTimeout(() => {
      if (modalRef.current) {
        const firstFocusable = modalRef.current.querySelector<HTMLElement>(
          "button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex='-1'])",
        );
        firstFocusable?.focus();
      }
    }, 50);
    return () => clearTimeout(timeout);
  }, [isOpen, step]);

  if (!isOpen) {
    return null;
  }

  const handleDownloadExport = async () => {
    setIsExporting(true);
    setErrorMessage(null);
    try {
      await onDownloadExport();
    } catch (err) {
      setErrorMessage(
        err instanceof Error ? err.message : "Failed to download export",
      );
    } finally {
      setIsExporting(false);
    }
  };

  const handleConfirmDeletion = async () => {
    setIsConfirming(true);
    setErrorMessage(null);
    try {
      await onConfirmDeletion();
    } catch (err) {
      setErrorMessage(
        err instanceof Error ? err.message : "Failed to confirm deletion",
      );
    } finally {
      setIsConfirming(false);
    }
  };

  return (
    <div
      ref={modalRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="account-deletion-modal-title"
      aria-describedby={errorMessage ? "account-deletion-error" : undefined}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-xs animate-in fade-in-50"
    >
      <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-xl space-y-5 text-card-foreground">
        <div className="flex items-center justify-between pb-2 border-b border-border/60">
          <div className="flex items-center space-x-2.5">
            <AlertTriangle className="h-5 w-5 text-destructive" />
            <h2
              id="account-deletion-modal-title"
              className="text-base font-semibold text-foreground"
            >
              Delete your Cras account
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close dialog"
            className="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors cursor-pointer"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {errorMessage && (
          <div
            id="account-deletion-error"
            role="alert"
            className="rounded-md bg-destructive/10 p-3 text-xs text-destructive"
          >
            {errorMessage}
          </div>
        )}

        {step === "overview" && (
          <div className="space-y-4">
            {userEmail && (
              <p className="text-xs text-muted-foreground">
                Signed in as{" "}
                <span className="font-medium text-foreground">{userEmail}</span>
              </p>
            )}
            <p className="text-xs text-muted-foreground leading-relaxed">
              Deleting removes your access immediately and schedules permanent
              erasure of your Tasks, Labels, Comments, Settings, and every
              connected installation. Your data is retained only for seven days
              in the Recovery window; after that it is purged permanently and
              cannot be restored.
            </p>
            <p className="text-xs text-muted-foreground leading-relaxed">
              Sign-out is different: it only leaves this device without erasing
              anything.
            </p>

            <div className="rounded-lg border border-border/80 bg-background/50 p-3 space-y-3">
              <div>
                <button
                  type="button"
                  onClick={handleDownloadExport}
                  disabled={isExporting}
                  className="w-full flex items-center justify-center space-x-2 px-3 py-2 rounded-md border border-primary/30 text-primary text-xs font-medium hover:bg-primary/10 transition-colors cursor-pointer disabled:opacity-60"
                >
                  {isExporting ? (
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  ) : (
                    <Download className="h-3.5 w-3.5" />
                  )}
                  <span>Download data export (JSON)</span>
                </button>
                <p className="text-[11px] text-muted-foreground pt-1.5">
                  Optional snapshot of Tasks, Labels, Comments, and Settings.
                </p>
              </div>
            </div>

            <button
              type="button"
              onClick={() => setStep("reauthenticate")}
              className="w-full flex items-center justify-center px-3 py-2 rounded-md bg-destructive text-destructive-foreground text-xs font-semibold hover:opacity-90 transition-opacity cursor-pointer"
            >
              Continue to verification
            </button>
          </div>
        )}

        {step === "reauthenticate" && (
          <div className="space-y-4">
            <p className="text-xs text-muted-foreground leading-relaxed">
              To continue you must sign in with Google again in a fresh flow for
              the same identity
              {userEmail ? (
                <>
                  {" "}
                  (
                  <span className="font-medium text-foreground">
                    {userEmail}
                  </span>
                  )
                </>
              ) : null}
              . This proves the deletion is deliberate.
            </p>
            <div className="flex flex-col space-y-2">
              <button
                type="button"
                onClick={async () => {
                  setIsReauthing(true);
                  setErrorMessage(null);
                  try {
                    await onReauthenticate();
                  } catch (err) {
                    setIsReauthing(false);
                    setErrorMessage(
                      err instanceof Error
                        ? err.message
                        : "Failed to start Google verification",
                    );
                  }
                }}
                disabled={isReauthing}
                className="w-full flex items-center justify-center space-x-2 px-3 py-2 rounded-md border border-border text-xs font-medium hover:bg-secondary transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
              >
                {isReauthing ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <ShieldCheck className="h-3.5 w-3.5" />
                )}
                <span>
                  {isReauthing ? "Redirecting…" : "Continue with Google"}
                </span>
              </button>
              <button
                type="button"
                onClick={() => setStep("overview")}
                disabled={isReauthing}
                className="text-xs text-muted-foreground underline hover:no-underline cursor-pointer self-center pt-1 disabled:opacity-50 disabled:pointer-events-none"
              >
                Back
              </button>
            </div>
          </div>
        )}

        {step === "confirm" && (
          <div className="space-y-4">
            <div className="rounded-md bg-destructive/10 p-3 text-xs text-destructive leading-relaxed">
              Your account will enter Pending deletion now. Access stops
              immediately, Notifications stop, and all data is erased
              permanently after seven days unless you recover within the window.
            </div>
            <label className="flex items-start space-x-2 text-xs text-muted-foreground cursor-pointer">
              <input
                type="checkbox"
                checked={acknowledged}
                onChange={(e) => setAcknowledged(e.target.checked)}
                className="mt-0.5 h-3.5 w-3.5 cursor-pointer accent-destructive"
              />
              <span>
                I understand my account will be permanently deleted after seven
                days unless I recover, and this cannot be undone by Cras.
              </span>
            </label>
            <div className="flex items-center justify-end space-x-2 pt-1">
              <button
                type="button"
                onClick={onClose}
                className="px-3 py-1.5 rounded-md border border-border text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleConfirmDeletion}
                disabled={!acknowledged || isConfirming}
                className="px-3 py-1.5 rounded-md bg-destructive text-destructive-foreground text-xs font-semibold hover:opacity-90 transition-opacity cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {isConfirming ? (
                  <span className="flex items-center space-x-1.5">
                    <Loader2 className="h-3 w-3 animate-spin" />
                    <span>Deleting...</span>
                  </span>
                ) : (
                  "Delete account forever"
                )}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
