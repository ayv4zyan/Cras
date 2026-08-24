import type { SupabaseClient } from "@supabase/supabase-js";

export interface LifecycleStorageObjectRow {
  readonly bucket_id?: string | null;
  readonly name?: string | null;
}

export interface StorageBucketAdmin {
  remove(names: readonly string[]): Promise<unknown>;
}

export interface StorageAdminApi {
  from(bucket: string): StorageBucketAdmin;
}

export interface AccountLifecycleRequestDeps {
  readonly anonClient: SupabaseClient;
  readonly adminClient: SupabaseClient;
  readonly storageApi: StorageAdminApi;
  readonly lifecycleSecret?: string;
}

export interface LifecycleStatusPayload {
  deletion_state?: string;
  deletion_deadline?: string | null;
  recovery_available?: boolean;
}

interface RpcResult<T> {
  data: T | null;
  error: { message: string; code?: string } | null;
}

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
};

function jsonResponse(
  status: number,
  payload: Record<string, unknown>,
): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
  });
}

function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const segment = token.split(".")[1];
    if (!segment) return null;
    const base64 = segment.replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const json = atob(padded);
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

async function rpc(
  client: SupabaseClient,
  name: string,
  args: Record<string, unknown> = {},
): Promise<RpcResult<unknown>> {
  const { data, error } = await client.schema("api").rpc(name, args as never);
  if (error) {
    return { data: null, error: { message: error.message, code: undefined } };
  }
  return { data: data as unknown, error: null };
}

export async function isOperatorPendingDeletion(
  adminClient: SupabaseClient,
  operatorId: string,
): Promise<boolean> {
  try {
    const { data, error } = await adminClient
      .schema("api")
      .rpc("operator_is_pending_deletion", {
        p_operator: operatorId,
      } as never);
    if (error) {
      return true;
    }
    return data === true;
  } catch {
    return true;
  }
}

function normalizeStatus(payload: LifecycleStatusPayload) {
  return {
    deletionState:
      payload.deletion_state === "pending_deletion"
        ? "pending_deletion"
        : "active",
    deletionDeadline: payload.deletion_deadline ?? null,
    recoveryAvailable: payload.recovery_available === true,
  };
}

type PurgeOutcome = {
  operatorId: string;
  purged: boolean;
  error?: string;
};

async function purgeOperator(
  operatorId: string,
  adminClient: SupabaseClient,
  storageApi: StorageAdminApi,
): Promise<PurgeOutcome> {
  const objectsQuery = adminClient
    .schema("storage")
    .from("objects")
    .select("bucket_id,name");
  const { data: listed, error: listError } = await (
    objectsQuery as unknown as {
      or: (filter: string) => Promise<RpcResult<LifecycleStorageObjectRow[]>>;
    }
  ).or(`owner.eq.${operatorId},owner_id.eq.${operatorId}`);

  if (listError || !listed) {
    return {
      operatorId,
      purged: false,
      error: listError?.message ?? "storage listing failed",
    };
  }
  const objectRows: LifecycleStorageObjectRow[] = listed;

  const byBucket = new Map<string, string[]>();
  for (const row of objectRows) {
    if (!row.bucket_id || !row.name) continue;
    const names = byBucket.get(row.bucket_id) ?? [];
    names.push(row.name);
    byBucket.set(row.bucket_id, names);
  }

  for (const [bucket, names] of byBucket) {
    try {
      const results = await storageApi.from(bucket).remove(names);
      if (Array.isArray(results)) {
        for (const entry of results as Array<{
          name?: string;
          error?: unknown;
        }>) {
          const message =
            entry &&
            typeof entry === "object" &&
            entry.error &&
            typeof entry.error === "object" &&
            "message" in entry.error
              ? String((entry.error as { message?: unknown }).message)
              : "";
          if (message && !message.toLowerCase().includes("not found")) {
            return { operatorId, purged: false, error: message };
          }
        }
      }
    } catch (err) {
      return {
        operatorId,
        purged: false,
        error: err instanceof Error ? err.message : String(err),
      };
    }
  }

  const finalization = await rpc(adminClient, "finalize_operator_purge", {
    p_operator: operatorId,
  });
  if (finalization.error) {
    return {
      operatorId,
      purged: false,
      error: finalization.error.message,
    };
  }

  return { operatorId, purged: true };
}

export async function handleAccountLifecycleRequest(
  req: Request,
  deps: AccountLifecycleRequestDeps,
): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }

  if (req.method !== "POST") {
    return jsonResponse(405, { error: "Method not allowed. Use POST." });
  }

  let body: Record<string, unknown> = {};
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return jsonResponse(400, { error: "Invalid JSON body." });
  }

  const action = body.action;
  const authHeader = req.headers.get("Authorization") ?? "";

  if (action === "purge-sweep") {
    const secret = deps.lifecycleSecret ?? "";
    if (!secret || authHeader !== `Bearer ${secret}`) {
      return jsonResponse(401, { error: "Unauthorized" });
    }
    const claim = await rpc(deps.adminClient, "claim_due_purge_batch", {
      p_batch: 50,
    });
    if (claim.error || !Array.isArray(claim.data)) {
      return jsonResponse(500, {
        error: claim.error?.message ?? "purge claim failed",
      });
    }
    const results: PurgeOutcome[] = [];
    for (const row of claim.data as Array<{
      operator_id?: string;
    }>) {
      const operatorId = row.operator_id;
      if (!operatorId) continue;
      results.push(
        await purgeOperator(operatorId, deps.adminClient, deps.storageApi),
      );
    }
    return jsonResponse(200, { claimed: results.length, results });
  }

  if (
    action !== "status" &&
    action !== "request-deletion" &&
    action !== "recover-account"
  ) {
    return jsonResponse(400, { error: "Unknown action." });
  }

  if (!authHeader.startsWith("Bearer ")) {
    return jsonResponse(401, {
      error: "Unauthorized: Missing or invalid Authorization header.",
    });
  }
  const token = authHeader.replace("Bearer ", "").trim();

  const { data: userData, error: userError } =
    await deps.anonClient.auth.getUser(token);
  if (userError || !userData.user) {
    return jsonResponse(401, {
      error: "Unauthorized: Invalid or expired session token.",
    });
  }
  const operatorId = userData.user.id;

  if (action === "status") {
    const status = await rpc(deps.adminClient, "get_lifecycle_status", {
      p_operator: operatorId,
    });
    if (status.error) {
      return jsonResponse(500, { error: status.error.message });
    }
    const payload = normalizeStatus(
      (status.data ?? {}) as LifecycleStatusPayload,
    );
    return jsonResponse(200, payload as Record<string, unknown>);
  }

  const claims = decodeJwtPayload(token);
  const sessionId = claims?.session_id;
  if (typeof sessionId !== "string" || sessionId.length === 0) {
    return jsonResponse(401, {
      error: "Session could not be verified.",
      code: "session_inactive",
    });
  }

  const sessionCheck = await rpc(deps.adminClient, "assert_active_session", {
    p_session_id: sessionId,
    p_operator: operatorId,
  });
  if (sessionCheck.error) {
    return jsonResponse(500, { error: sessionCheck.error.message });
  }
  if (sessionCheck.data !== true) {
    return jsonResponse(401, {
      error: "This session is no longer active. Please sign in again.",
      code: "session_inactive",
    });
  }

  if (action === "request-deletion") {
    const entered = await rpc(deps.adminClient, "enter_pending_deletion", {
      p_operator: operatorId,
    });
    if (entered.error || !entered.data) {
      return jsonResponse(500, {
        error: entered.error?.message ?? "Failed to enter Pending deletion.",
      });
    }
    const state = entered.data as {
      deletion_deadline?: string;
      already_pending?: boolean;
    };

    let sessionsRevoked = false;
    try {
      const revoked = await rpc(deps.adminClient, "revoke_operator_sessions", {
        p_operator: operatorId,
      });
      sessionsRevoked = !revoked.error;
    } catch {
      sessionsRevoked = false;
    }

    return jsonResponse(200, {
      confirmed: true,
      deletionState: "pending_deletion",
      deletionDeadline: state.deletion_deadline ?? null,
      alreadyPending: state.already_pending === true,
      sessionsRevoked,
    });
  }

  const recovered = await rpc(deps.adminClient, "recover_account", {
    p_operator: operatorId,
  });
  if (recovered.error) {
    return jsonResponse(500, { error: recovered.error.message });
  }
  const outcome = (recovered.data ?? {}) as {
    recovered?: boolean;
    error?: string;
  };
  if (outcome.recovered !== true) {
    return jsonResponse(403, {
      error: "Recovery is not available for this account.",
      code: outcome.error ?? "recovery_unavailable",
    });
  }
  return jsonResponse(200, { recovered: true });
}
