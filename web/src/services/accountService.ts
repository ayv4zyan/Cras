import type { SupabaseClient } from "@supabase/supabase-js";
import { clearOutbox } from "./outboxService";

export type AccountDeletionState = "active" | "pending_deletion";

export interface AccountStatus {
  readonly deletionState: AccountDeletionState;
  readonly deletionDeadline: string | null;
  readonly recoveryAvailable: boolean;
}

export interface DeletionConfirmation {
  readonly confirmed: boolean;
  readonly deletionState: AccountDeletionState;
  readonly deletionDeadline: string | null;
  readonly sessionsRevoked: boolean;
}

export interface OperatorExportSnapshot {
  readonly exportedAt: string;
  readonly tasks: readonly unknown[];
  readonly labels: readonly unknown[];
  readonly taskLabels: readonly unknown[];
  readonly comments: readonly unknown[];
  readonly settings: unknown;
}

export class AccountLifecycleError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(
    message: string,
    status: number,
    code?: string,
    isNetworkError?: boolean,
  ) {
    super(message);
    this.name = "AccountLifecycleError";
    this.status = status;
    this.code = code;
    if (isNetworkError) {
      (this as { isNetworkError?: boolean }).isNetworkError = true;
    }
  }
}

type LifecycleAction = "status" | "request-deletion" | "recover-account";

async function callLifecycleEndpoint(
  client: SupabaseClient,
  action: LifecycleAction,
): Promise<Record<string, unknown>> {
  const { data, error: sessionError } = await client.auth.getSession();
  const session = sessionError ? null : data.session;
  if (!session) {
    throw new AccountLifecycleError(
      "Please sign in to manage your account.",
      401,
      "unauthorized",
    );
  }

  const {
    data: payload,
    error,
    response,
  } = await client.functions.invoke<Record<string, unknown>>(
    "account-lifecycle",
    {
      headers: { Authorization: `Bearer ${session.access_token}` },
      body: { action },
    },
  );

  if (error) {
    if (response instanceof Response) {
      let failureBody: Record<string, unknown> = {};
      try {
        failureBody = (await response.json()) as Record<string, unknown>;
      } catch {
        // Non-JSON error body falls through to a generic message below
      }
      throw new AccountLifecycleError(
        typeof failureBody.error === "string" && failureBody.error
          ? failureBody.error
          : "Account service request failed.",
        response.status,
        typeof failureBody.code === "string" ? failureBody.code : undefined,
      );
    }
    throw new AccountLifecycleError(
      "Network error: unable to reach Cras account services.",
      0,
      "network_error",
      true,
    );
  }

  return payload ?? {};
}

function readString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

export async function fetchAccountStatus(
  client: SupabaseClient,
): Promise<AccountStatus> {
  const payload = await callLifecycleEndpoint(client, "status");
  const deletionState =
    payload.deletionState === "pending_deletion"
      ? "pending_deletion"
      : "active";
  return {
    deletionState,
    deletionDeadline: readString(payload.deletionDeadline),
    recoveryAvailable: payload.recoveryAvailable === true,
  };
}

export async function requestAccountDeletion(
  client: SupabaseClient,
): Promise<DeletionConfirmation> {
  const payload = await callLifecycleEndpoint(client, "request-deletion");
  return {
    confirmed: payload.confirmed === true,
    deletionState:
      payload.deletionState === "pending_deletion"
        ? "pending_deletion"
        : "active",
    deletionDeadline: readString(payload.deletionDeadline),
    sessionsRevoked: payload.sessionsRevoked === true,
  };
}

export async function recoverAccount(client: SupabaseClient): Promise<void> {
  await callLifecycleEndpoint(client, "recover-account");
}

export async function generateAccountExport(
  client: SupabaseClient,
): Promise<OperatorExportSnapshot> {
  const { data, error } = await client
    .schema("api")
    .rpc("export_operator_data");

  if (error || typeof data !== "string") {
    throw new Error(
      `Failed to generate account export: ${error?.message ?? "empty response"}`,
    );
  }

  return JSON.parse(data) as OperatorExportSnapshot;
}

export function downloadJsonFile(fileName: string, contents: string): void {
  const blob = new Blob([contents], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

export async function downloadAccountExport(
  client: SupabaseClient,
): Promise<void> {
  const snapshot = await generateAccountExport(client);
  const day = snapshot.exportedAt.slice(0, 10) || new Date().toISOString();
  downloadJsonFile(`cras-export-${day}.json`, JSON.stringify(snapshot));
}

const REAUTH_INTENT_KEY = "cras_reauth_intent";

export const REAUTH_INTENT_STORAGE_KEY = REAUTH_INTENT_KEY;

export function stageReauthIntent(userId: string): void {
  try {
    sessionStorage.setItem(REAUTH_INTENT_KEY, userId);
  } catch {
    // Storage unavailable; the flow continues without resume-on-return
  }
}

export function consumeReauthIntent(expectedUserId: string): string | null {
  try {
    const staged = sessionStorage.getItem(REAUTH_INTENT_KEY);
    sessionStorage.removeItem(REAUTH_INTENT_KEY);
    if (staged && staged === expectedUserId) {
      return staged;
    }
  } catch {
    // Storage unavailable
  }
  return null;
}

const CACHED_TIMED_PLAN_TYPE_KEY = "cras_effective_default_timed_plan_type";
const UNSUBMITTED_TASK_INPUT_KEY = "cras_unsubmitted_task_input";

export function clearLocalOperatorData(operatorId: string): void {
  clearOutbox(operatorId);
  try {
    localStorage.removeItem(CACHED_TIMED_PLAN_TYPE_KEY);
    sessionStorage.removeItem(UNSUBMITTED_TASK_INPUT_KEY);
    sessionStorage.removeItem(REAUTH_INTENT_KEY);
  } catch {
    // Ignore storage access errors during wipe
  }
}
