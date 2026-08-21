import React, { useState } from "react";
import { Bell, Loader2, X } from "lucide-react";
import {
  BEST_EFFORT_RELIABILITY_COPY,
  registerServiceWorker,
  subscribeToPush,
  syncInstallationWithServer,
  setExplainedPermission,
  arrayBufferToBase64,
} from "../services/notificationService";
import type { SupabaseClient } from "@supabase/supabase-js";

export interface NotificationPermissionModalProps {
  readonly isOpen: boolean;
  readonly onClose: () => void;
  readonly client: SupabaseClient;
  readonly onPermissionResolved?: (permission: NotificationPermission) => void;
}

export function NotificationPermissionModal({
  isOpen,
  onClose,
  client,
  onPermissionResolved,
}: NotificationPermissionModalProps): React.JSX.Element | null {
  const [isRequesting, setIsRequesting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!isOpen) {
    return null;
  }

  const handleEnableNotifications = async () => {
    setIsRequesting(true);
    setError(null);
    try {
      setExplainedPermission(true);

      if (
        typeof window === "undefined" ||
        typeof Notification === "undefined"
      ) {
        onClose();
        return;
      }

      const permission = await Notification.requestPermission();
      onPermissionResolved?.(permission);

      if (permission === "granted") {
        const registration = await registerServiceWorker();
        let endpoint: string | null = null;
        let p256dh: string | null = null;
        let auth: string | null = null;

        if (registration) {
          const subscription = await subscribeToPush(registration);
          if (subscription) {
            endpoint = subscription.endpoint;
            p256dh = arrayBufferToBase64(subscription.getKey("p256dh"));
            auth = arrayBufferToBase64(subscription.getKey("auth"));
          }
        }

        await syncInstallationWithServer(client, {
          permissionState: "granted",
          endpoint,
          p256dh,
          auth,
        });
      } else {
        await syncInstallationWithServer(client, {
          permissionState: permission === "denied" ? "denied" : "prompt",
        });
      }

      onClose();
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to enable notifications",
      );
    } finally {
      setIsRequesting(false);
    }
  };

  const handleSkip = () => {
    setExplainedPermission(true);
    onClose();
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="notification-permission-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-xs animate-in fade-in-50"
    >
      <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-xl space-y-4 text-card-foreground">
        <button
          type="button"
          onClick={handleSkip}
          aria-label="Close dialog"
          className="absolute top-4 right-4 p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors cursor-pointer"
        >
          <X className="h-4 w-4" />
        </button>

        <div className="flex items-center space-x-3">
          <div className="h-10 w-10 rounded-full bg-primary/10 text-primary flex items-center justify-center shrink-0">
            <Bell className="h-5 w-5" />
          </div>
          <div>
            <h2
              id="notification-permission-title"
              className="text-base font-semibold text-foreground"
            >
              Timed Task Notifications
            </h2>
            <p className="text-xs text-muted-foreground">
              Server-authoritative alerts for your timed plans
            </p>
          </div>
        </div>

        <p className="text-xs text-foreground leading-relaxed">
          Cras automatically schedules and delivers notifications for your timed
          tasks (both Instant and Floating plans) to this browser installation.
        </p>

        <div className="rounded-lg bg-secondary/50 p-3 border border-border/60">
          <p className="text-[11px] text-muted-foreground italic leading-normal">
            {BEST_EFFORT_RELIABILITY_COPY}
          </p>
        </div>

        {error && <p className="text-xs text-destructive">{error}</p>}

        <div className="flex items-center justify-end space-x-2 pt-2 border-t border-border/60">
          <button
            type="button"
            onClick={handleSkip}
            disabled={isRequesting}
            className="px-3 py-1.5 rounded-md text-xs font-medium text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors cursor-pointer disabled:opacity-50"
          >
            Maybe later
          </button>

          <button
            type="button"
            onClick={handleEnableNotifications}
            disabled={isRequesting}
            className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-md bg-primary text-primary-foreground text-xs font-medium hover:opacity-90 transition-opacity cursor-pointer disabled:opacity-50"
          >
            {isRequesting ? (
              <>
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
                <span>Enabling...</span>
              </>
            ) : (
              <>
                <Bell className="h-3.5 w-3.5" />
                <span>Enable Notifications</span>
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
