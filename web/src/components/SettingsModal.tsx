import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  X,
  Settings as SettingsIcon,
  Bell,
  CheckCircle2,
  AlertTriangle,
  XCircle,
  Clock,
  Loader2,
  Mic,
} from "lucide-react";
import {
  BEST_EFFORT_RELIABILITY_COPY,
  type InstallationStatus,
  deriveInstallationStatus,
  getLocalNotificationsEnabled,
  setLocalNotificationsEnabled,
  getBrowserPermissionState,
  getObservedTimezone,
  registerServiceWorker,
  subscribeToPush,
  syncInstallationWithServer,
  arrayBufferToBase64,
} from "../services/notificationService";
import {
  fetchOperatorSettings,
  updateOperatorTimedPlanType,
  updateOperatorMissedDelivery,
  type OperatorSettings,
} from "../services/settingsService";
import {
  fetchVoiceModelCatalog,
  updateOperatorVoiceSettings,
  type VoiceModelCatalogEntry,
} from "../services/voiceService";
import type { TimedPlanType } from "../services/temporalService";
import type { SupabaseClient } from "@supabase/supabase-js";

export interface SettingsModalProps {
  readonly isOpen: boolean;
  readonly onClose: () => void;
  readonly client: SupabaseClient;
  readonly effectiveDefaultTimedPlanType: TimedPlanType;
  readonly onTimedPlanTypeChanged?: (type: TimedPlanType) => void;
  readonly onDeleteAccount?: () => void;
}

export function SettingsModal({
  isOpen,
  onClose,
  client,
  effectiveDefaultTimedPlanType,
  onTimedPlanTypeChanged,
  onDeleteAccount,
}: SettingsModalProps): React.JSX.Element | null {
  const [operatorSettings, setOperatorSettings] =
    useState<OperatorSettings | null>(null);
  const [voiceCatalog, setVoiceCatalog] = useState<VoiceModelCatalogEntry[]>(
    [],
  );
  const [customPrompt, setCustomPrompt] = useState<string>("");
  const [localEnabled, setLocalEnabled] = useState<boolean>(
    getLocalNotificationsEnabled(),
  );
  const [permissionState, setPermissionState] = useState<
    NotificationPermission | "unsupported"
  >(getBrowserPermissionState());
  const [hasEndpoint, setHasEndpoint] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const modalRef = useRef<HTMLDivElement>(null);

  const status: InstallationStatus = deriveInstallationStatus({
    localEnabled,
    permission: permissionState,
    hasEndpoint,
  });

  const loadData = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const [settings, reg, catalog] = await Promise.all([
        fetchOperatorSettings(client).catch(() => null),
        registerServiceWorker().catch(() => null),
        fetchVoiceModelCatalog(client).catch(() => []),
      ]);

      setOperatorSettings(settings);
      setCustomPrompt(settings?.custom_extractor_prompt || "");
      setVoiceCatalog(catalog);
      setLocalEnabled(getLocalNotificationsEnabled());
      setPermissionState(getBrowserPermissionState());

      if (reg && "pushManager" in reg) {
        const sub = await reg.pushManager.getSubscription();
        setHasEndpoint(!!sub);
      } else {
        setHasEndpoint(false);
      }
    } catch (err) {
      setErrorMessage(
        err instanceof Error ? err.message : "Failed to load settings",
      );
    } finally {
      setIsLoading(false);
    }
  }, [client]);

  useEffect(() => {
    if (isOpen) {
      loadData();
    }
  }, [isOpen, loadData]);

  // Focus trap and Escape key handling
  useEffect(() => {
    if (!isOpen) return;

    const previouslyFocused = document.activeElement as HTMLElement | null;

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

    // Auto-focus first focusable element
    const timeout = setTimeout(() => {
      if (modalRef.current) {
        const firstFocusable = modalRef.current.querySelector<HTMLElement>(
          "button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex='-1'])",
        );
        firstFocusable?.focus();
      }
    }, 50);

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      clearTimeout(timeout);
      previouslyFocused?.focus();
    };
  }, [isOpen, onClose]);

  if (!isOpen) {
    return null;
  }

  const handleToggleLocalEnabled = async (newVal: boolean) => {
    setLocalEnabled(newVal);
    setLocalNotificationsEnabled(newVal);
    try {
      await syncInstallationWithServer(client, { localEnabled: newVal });
    } catch {
      // Retain local state
    }
  };

  const handleRequestPermission = async () => {
    if (typeof window === "undefined" || typeof Notification === "undefined") {
      return;
    }

    try {
      const perm = await Notification.requestPermission();
      setPermissionState(perm);

      if (perm === "granted") {
        const reg = await registerServiceWorker();
        let ep: string | null = null;
        let p256: string | null = null;
        let a: string | null = null;

        if (reg) {
          const sub = await subscribeToPush(reg);
          if (sub) {
            ep = sub.endpoint;
            p256 = arrayBufferToBase64(sub.getKey("p256dh"));
            a = arrayBufferToBase64(sub.getKey("auth"));
            setHasEndpoint(true);
          }
        }

        await syncInstallationWithServer(client, {
          permissionState: "granted",
          endpoint: ep,
          p256dh: p256,
          auth: a,
        });
      } else {
        await syncInstallationWithServer(client, {
          permissionState: perm === "denied" ? "denied" : "prompt",
        });
      }
    } catch (err) {
      setErrorMessage(
        err instanceof Error ? err.message : "Failed to request permission",
      );
    }
  };

  const handlePlanTypeChange = async (type: TimedPlanType | "inherit") => {
    setIsSaving(true);
    try {
      const explicit = type === "inherit" ? null : type;
      await updateOperatorTimedPlanType(client, explicit);
      setOperatorSettings((prev) => ({
        ...prev,
        default_timed_plan_type: explicit,
      }));
      if (explicit) {
        onTimedPlanTypeChanged?.(explicit);
      }
    } catch (err) {
      setErrorMessage(
        err instanceof Error
          ? err.message
          : "Failed to update default plan type",
      );
    } finally {
      setIsSaving(false);
    }
  };

  const handleMissedDeliveryToggle = async (enabled: boolean) => {
    setIsSaving(true);
    try {
      await updateOperatorMissedDelivery(client, enabled);
      setOperatorSettings((prev) => ({
        ...prev,
        missed_delivery_enabled: enabled,
      }));
    } catch (err) {
      setErrorMessage(
        err instanceof Error
          ? err.message
          : "Failed to update missed delivery setting",
      );
    } finally {
      setIsSaving(false);
    }
  };

  const handleSttModelChange = async (key: string | "inherit") => {
    setIsSaving(true);
    try {
      const explicit = key === "inherit" ? null : key;
      await updateOperatorVoiceSettings(client, { stt_model_key: explicit });
      setOperatorSettings((prev) => ({
        ...prev,
        stt_model_key: explicit,
      }));
    } catch (err) {
      setErrorMessage(
        err instanceof Error ? err.message : "Failed to update STT model",
      );
    } finally {
      setIsSaving(false);
    }
  };

  const handleExtractorModelChange = async (key: string | "inherit") => {
    setIsSaving(true);
    try {
      const explicit = key === "inherit" ? null : key;
      await updateOperatorVoiceSettings(client, {
        extractor_model_key: explicit,
      });
      setOperatorSettings((prev) => ({
        ...prev,
        extractor_model_key: explicit,
      }));
    } catch (err) {
      setErrorMessage(
        err instanceof Error ? err.message : "Failed to update extractor model",
      );
    } finally {
      setIsSaving(false);
    }
  };

  const handleSaveCustomPrompt = async () => {
    setIsSaving(true);
    try {
      const explicit = customPrompt.trim() || null;
      await updateOperatorVoiceSettings(client, {
        custom_extractor_prompt: explicit,
      });
      setOperatorSettings((prev) => ({
        ...prev,
        custom_extractor_prompt: explicit,
      }));
    } catch (err) {
      setErrorMessage(
        err instanceof Error ? err.message : "Failed to update custom prompt",
      );
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <div
      ref={modalRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="settings-modal-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-xs animate-in fade-in-50"
    >
      <div className="relative w-full max-w-lg rounded-xl border border-border bg-card p-6 shadow-xl space-y-5 text-card-foreground">
        {/* Header */}
        <div className="flex items-center justify-between pb-2 border-b border-border/60">
          <div className="flex items-center space-x-2.5">
            <SettingsIcon className="h-5 w-5 text-primary" />
            <h2
              id="settings-modal-title"
              className="text-base font-semibold text-foreground"
            >
              Operator & Installation Settings
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
          <div role="alert" className="rounded-md bg-destructive/10 p-3 text-xs text-destructive">
            {errorMessage}
          </div>
        )}

        {isLoading ? (
          <div className="py-12 flex flex-col items-center justify-center space-y-2 text-muted-foreground">
            <Loader2 className="h-6 w-6 animate-spin text-primary" />
            <p className="text-xs">Loading settings...</p>
          </div>
        ) : (
          <div className="space-y-4 max-h-[70vh] overflow-y-auto pr-1">
            {/* Installation Notifications Section */}
            <div className="rounded-lg border border-border/80 bg-background/50 p-4 space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-2">
                  <Bell className="h-4 w-4 text-primary" />
                  <span className="text-sm font-medium text-foreground">
                    Installation Notifications
                  </span>
                </div>

                {/* State Badge */}
                <div>
                  {status === "enabled" ? (
                    <span
                      data-testid="status-enabled"
                      className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20"
                    >
                      <CheckCircle2 className="h-3 w-3" />
                      <span>Enabled</span>
                    </span>
                  ) : status === "disabled_locally" ? (
                    <span
                      data-testid="status-disabled-locally"
                      className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-secondary text-muted-foreground border border-border"
                    >
                      <XCircle className="h-3 w-3" />
                      <span>Disabled locally</span>
                    </span>
                  ) : status === "blocked" ? (
                    <span
                      data-testid="status-blocked"
                      className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-destructive/10 text-destructive border border-destructive/20"
                    >
                      <AlertTriangle className="h-3 w-3" />
                      <span>Blocked by system permission</span>
                    </span>
                  ) : (
                    <span
                      data-testid="status-endpoint-unavailable"
                      className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-amber-500/10 text-amber-600 dark:text-amber-400 border border-amber-500/20"
                    >
                      <AlertTriangle className="h-3 w-3" />
                      <span>Endpoint unavailable</span>
                    </span>
                  )}
                </div>
              </div>

              {/* Local Control Toggle */}
              <div className="flex items-center justify-between pt-1 text-xs">
                <span className="text-muted-foreground">
                  Receive notifications on this device:
                </span>
                <label className="relative inline-flex items-center cursor-pointer">
                  <input
                    type="checkbox"
                    checked={localEnabled}
                    onChange={(e) => handleToggleLocalEnabled(e.target.checked)}
                    className="sr-only peer"
                    aria-label="Toggle notifications for this device"
                  />
                  <div className="w-9 h-5 bg-secondary peer-focus:outline-hidden rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary"></div>
                </label>
              </div>

              {/* Status Explanation / Actions */}
              {status === "blocked" && (
                <div className="rounded-md bg-destructive/10 p-2.5 text-xs text-destructive space-y-1.5">
                  <p>
                    Notifications are blocked by your browser permissions. To
                    enable alerts, allow notifications for this site in your
                    browser settings.
                  </p>
                  <button
                    type="button"
                    onClick={() => {
                      setPermissionState(getBrowserPermissionState());
                      loadData();
                    }}
                    className="text-xs font-semibold underline hover:no-underline cursor-pointer"
                  >
                    Re-check browser permission
                  </button>
                </div>
              )}

              {status === "endpoint_unavailable" && localEnabled && (
                <div className="rounded-md bg-amber-500/10 p-2.5 text-xs text-amber-700 dark:text-amber-300 space-y-1.5">
                  <p>
                    Push notification endpoint could not be registered with your
                    browser push service.
                  </p>
                  <button
                    type="button"
                    onClick={handleRequestPermission}
                    className="text-xs font-semibold underline hover:no-underline cursor-pointer"
                  >
                    Grant permission / retry push subscription
                  </button>
                </div>
              )}

              <div className="text-[11px] text-muted-foreground pt-1 flex items-center justify-between">
                <span>Installation Timezone:</span>
                <span className="font-mono text-foreground">
                  {getObservedTimezone()}
                </span>
              </div>
            </div>

            {/* Operator Shared Settings Section */}
            <div className="rounded-lg border border-border/80 bg-background/50 p-4 space-y-3">
              <span className="text-sm font-medium text-foreground">
                Operator Shared Settings
              </span>

              {/* Default Timed Plan Type */}
              <div className="space-y-1.5 text-xs">
                <label
                  htmlFor="operator-default-plan-type"
                  className="font-medium text-muted-foreground"
                >
                  Default Timed Plan Type:
                </label>
                <select
                  id="operator-default-plan-type"
                  value={operatorSettings?.default_timed_plan_type ?? "inherit"}
                  onChange={(e) =>
                    handlePlanTypeChange(
                      e.target.value as TimedPlanType | "inherit",
                    )
                  }
                  disabled={isSaving}
                  className="w-full rounded-md border border-border bg-card px-2.5 py-1.5 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring cursor-pointer"
                >
                  <option value="inherit">
                    Inherit Deployment Default ({effectiveDefaultTimedPlanType})
                  </option>
                  <option value="instant">Instant (Zoned UTC moment)</option>
                  <option value="floating">
                    Floating (Same clock face everywhere)
                  </option>
                </select>
              </div>

              {/* Missed Delivery Setting */}
              <div className="space-y-1.5 pt-2 border-t border-border/60 text-xs">
                <div className="flex items-center justify-between">
                  <div>
                    <span className="font-medium text-foreground">
                      Missed Notification Delivery
                    </span>
                    <p className="text-[11px] text-muted-foreground">
                      {operatorSettings?.missed_delivery_enabled
                        ? "Deliver missed notifications up to 1 hour after plan time"
                        : "Skip missed notifications by default"}
                    </p>
                  </div>
                  <label className="relative inline-flex items-center cursor-pointer">
                    <input
                      type="checkbox"
                      checked={
                        operatorSettings?.missed_delivery_enabled ?? false
                      }
                      onChange={(e) =>
                        handleMissedDeliveryToggle(e.target.checked)
                      }
                      disabled={isSaving}
                      className="sr-only peer"
                      aria-label="Toggle missed notification delivery"
                    />
                    <div className="w-9 h-5 bg-secondary peer-focus:outline-hidden rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary"></div>
                  </label>
                </div>
              </div>
            </div>

            {/* Voice Configuration Section */}
            <div className="rounded-lg border border-border/80 bg-background/50 p-4 space-y-3">
              <div className="flex items-center space-x-2">
                <Mic className="h-4 w-4 text-primary" />
                <span className="text-sm font-medium text-foreground">
                  Voice Configuration
                </span>
              </div>

              {/* STT Model */}
              <div className="space-y-1.5 text-xs">
                <label
                  htmlFor="voice-stt-model"
                  className="font-medium text-muted-foreground"
                >
                  Speech-to-Text Model:
                </label>
                <select
                  id="voice-stt-model"
                  value={operatorSettings?.stt_model_key ?? "inherit"}
                  onChange={(e) => handleSttModelChange(e.target.value)}
                  disabled={isSaving}
                  className="w-full rounded-md border border-border bg-card px-2.5 py-1.5 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring cursor-pointer"
                >
                  <option value="inherit">
                    Inherit Deployment Default (Voxtral Small)
                  </option>
                  {voiceCatalog
                    .filter((m) => m.type === "stt")
                    .map((m) => (
                      <option key={m.key} value={m.key}>
                        {m.name} {m.is_default ? "(Deployment default)" : ""}
                      </option>
                    ))}
                </select>
              </div>

              {/* Extractor Model */}
              <div className="space-y-1.5 text-xs">
                <label
                  htmlFor="voice-extractor-model"
                  className="font-medium text-muted-foreground"
                >
                  Extraction Model:
                </label>
                <select
                  id="voice-extractor-model"
                  value={operatorSettings?.extractor_model_key ?? "inherit"}
                  onChange={(e) => handleExtractorModelChange(e.target.value)}
                  disabled={isSaving}
                  className="w-full rounded-md border border-border bg-card px-2.5 py-1.5 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring cursor-pointer"
                >
                  <option value="inherit">
                    Inherit Deployment Default (Gemma 4 26B-A4B-it)
                  </option>
                  {voiceCatalog
                    .filter((m) => m.type === "extractor")
                    .map((m) => (
                      <option key={m.key} value={m.key}>
                        {m.name} {m.is_default ? "(Deployment default)" : ""}
                      </option>
                    ))}
                </select>
              </div>

              {/* Custom Extractor Prompt */}
              <div className="space-y-1.5 pt-1 text-xs">
                <div className="flex items-center justify-between">
                  <label
                    htmlFor="voice-custom-prompt"
                    className="font-medium text-muted-foreground"
                  >
                    Custom Extractor Prompt (Optional):
                  </label>
                  {customPrompt !==
                    (operatorSettings?.custom_extractor_prompt || "") && (
                    <button
                      type="button"
                      onClick={handleSaveCustomPrompt}
                      disabled={isSaving}
                      className="text-[11px] font-semibold text-primary underline hover:no-underline cursor-pointer"
                    >
                      Save Prompt
                    </button>
                  )}
                </div>
                <textarea
                  id="voice-custom-prompt"
                  value={customPrompt}
                  onChange={(e) => setCustomPrompt(e.target.value)}
                  onBlur={handleSaveCustomPrompt}
                  placeholder="Inherit Deployment default prompt..."
                  rows={2}
                  disabled={isSaving}
                  className="w-full rounded-md border border-border bg-card px-2.5 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-hidden focus:ring-1 focus:ring-ring resize-none font-mono"
                />
              </div>
            </div>

            {/* Account Danger Zone */}
            {onDeleteAccount && (
              <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4 space-y-2.5">
                <div className="flex items-center space-x-2">
                  <AlertTriangle className="h-4 w-4 text-destructive" />
                  <span className="text-sm font-medium text-foreground">
                    Danger Zone
                  </span>
                </div>
                <p className="text-[11px] text-muted-foreground leading-relaxed">
                  Deleting your account revokes access immediately and erases
                  all data after a seven-day Recovery window.
                </p>
                <button
                  type="button"
                  onClick={onDeleteAccount}
                  aria-label="Delete account"
                  className="px-3 py-1.5 rounded-md bg-destructive text-destructive-foreground text-xs font-semibold hover:opacity-90 transition-opacity cursor-pointer"
                >
                  Delete account...
                </button>
              </div>
            )}

            {/* Best Effort Reliability Copy */}
            <div className="rounded-lg bg-secondary/50 p-3 border border-border/60 flex items-start space-x-2.5">
              <Clock className="h-4 w-4 text-muted-foreground shrink-0 mt-0.5" />
              <p className="text-[11px] text-muted-foreground italic leading-normal">
                {BEST_EFFORT_RELIABILITY_COPY}
              </p>
            </div>
          </div>
        )}

        <div className="flex items-center justify-end pt-2 border-t border-border/60">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-1.5 rounded-md bg-primary text-primary-foreground text-xs font-medium hover:opacity-90 transition-opacity cursor-pointer"
          >
            Done
          </button>
        </div>
      </div>
    </div>
  );
}
