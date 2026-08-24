import { describe, it, expect, vi, beforeEach } from "vitest";
import type { SupabaseClient } from "@supabase/supabase-js";
import {
  handleAccountLifecycleRequest,
  isOperatorPendingDeletion,
} from "./accountLifecycleWorker";
import { processVoiceCapture } from "./voiceWorker";

const OP_ID = "11111111-1111-1111-1111-111111111111";

function encodeJwtSegment(payload: Record<string, unknown>): string {
  const json = JSON.stringify(payload);
  const b64 = Buffer.from(json, "utf8").toString("base64");
  return b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function tokenWithSession(sessionId: string): string {
  return [
    encodeJwtSegment({ alg: "HS256", typ: "JWT" }),
    encodeJwtSegment({ sub: OP_ID, session_id: sessionId }),
    "signature",
  ].join(".");
}

interface RpcRegistry {
  [rpcName: string]: ReturnType<typeof vi.fn>;
}

function createAnonClient(options?: { userId?: string | null }) {
  const getUser = vi.fn(async (token: string) => {
    const userId = options?.userId === undefined ? OP_ID : options.userId;
    if (!token || token === "invalid") {
      return { data: { user: null }, error: { message: "bad token" } };
    }
    if (!userId) {
      return { data: { user: null }, error: { message: "unknown user" } };
    }
    return { data: { user: { id: userId } }, error: null };
  });
  return {
    auth: { getUser },
    supabaseUrl: "https://test.supabase.co",
  } as unknown as SupabaseClient;
}

function createAdminClient(registry: RpcRegistry, callLog: string[]) {
  const schema = vi.fn((schemaName: string) => {
    if (schemaName === "api") {
      return {
        rpc: vi.fn(async (name: string, args: Record<string, unknown>) => {
          callLog.push(name);
          const impl = registry[name];
          if (!impl) {
            throw new Error(`Unexpected api rpc: ${name}`);
          }
          return impl(args);
        }),
      };
    }
    if (schemaName === "storage") {
      return {
        from: vi.fn(() => ({
          select: vi.fn(() => ({
            or: vi.fn(() => {
              callLog.push("__storageObjects");
              return registry.__storageObjects({});
            }),
          })),
        })),
      };
    }
    throw new Error(`Unexpected schema: ${schemaName}`);
  });
  return { schema } as unknown as SupabaseClient;
}

function createStorageApi(
  removeCalls: Array<{ bucket: string; names: string[] }>,
) {
  return {
    from: vi.fn((bucket: string) => ({
      remove: vi.fn(async (names: string[]) => {
        removeCalls.push({ bucket, names });
        return names.map((name) => ({ name, error: null }));
      }),
    })),
  };
}

describe("account lifecycle edge worker", () => {
  let callLog: string[];
  let registry: RpcRegistry;
  let removeCalls: Array<{ bucket: string; names: string[] }>;
  let storageApi: ReturnType<typeof createStorageApi>;

  beforeEach(() => {
    callLog = [];
    removeCalls = [];
    registry = {};
    storageApi = createStorageApi(removeCalls);
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  function buildDeps(userToken?: string) {
    return {
      anonClient: createAnonClient(),
      adminClient: createAdminClient(registry, callLog),
      storageApi,
      lifecycleSecret: "sweep-secret",
      bearerToken: userToken,
    };
  }

  function post(body: unknown, authorization?: string): Request {
    return new Request("http://localhost/functions/v1/account-lifecycle", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(authorization ? { Authorization: authorization } : {}),
      },
      body: JSON.stringify(body),
    });
  }

  describe("status surface", () => {
    it("requires a bearer token", async () => {
      const res = await handleAccountLifecycleRequest(
        post({ action: "status" }),
        buildDeps(),
      );
      expect(res.status).toBe(401);
    });

    it("refuses tokens that fail verification", async () => {
      const res = await handleAccountLifecycleRequest(
        post({ action: "status" }, "Bearer invalid"),
        buildDeps(),
      );
      expect(res.status).toBe(401);
    });

    it("reports the current database state without requiring an active session", async () => {
      registry.get_lifecycle_status = vi.fn(() =>
        Promise.resolve({
          data: {
            deletion_state: "pending_deletion",
            deletion_deadline: "2026-08-31T00:00:00+00:00",
            recovery_available: true,
          },
          error: null,
        }),
      );

      const res = await handleAccountLifecycleRequest(
        post({ action: "status" }, "Bearer op-token"),
        buildDeps(),
      );

      expect(res.status).toBe(200);
      const body = (await res.json()) as Record<string, unknown>;
      expect(body.deletionState).toBe("pending_deletion");
      expect(body.deletionDeadline).toBe("2026-08-31T00:00:00+00:00");
      expect(body.recoveryAvailable).toBe(true);
    });

    it("treats a missing state row as an active Operator", async () => {
      registry.get_lifecycle_status = vi.fn(() =>
        Promise.resolve({
          data: {
            deletion_state: "active",
            deletion_deadline: null,
            recovery_available: false,
          },
          error: null,
        }),
      );

      const res = await handleAccountLifecycleRequest(
        post({ action: "status" }, "Bearer op-token"),
        buildDeps(),
      );

      const body = (await res.json()) as Record<string, unknown>;
      expect(body.deletionState).toBe("active");
    });

    it("rejects unknown actions", async () => {
      const res = await handleAccountLifecycleRequest(
        post({ action: "something-else" }, "Bearer op-token"),
        buildDeps(),
      );
      expect(res.status).toBe(400);
    });
  });

  describe("request-deletion", () => {
    beforeEach(() => {
      registry.assert_active_session = vi.fn(() =>
        Promise.resolve({ data: true, error: null }),
      );
      registry.enter_pending_deletion = vi.fn(() =>
        Promise.resolve({
          data: {
            operator_id: OP_ID,
            deletion_deadline: "2026-08-31T12:00:00+00:00",
            already_pending: false,
          },
          error: null,
        }),
      );
      registry.revoke_operator_sessions = vi.fn(() =>
        Promise.resolve({ data: 3, error: null }),
      );
    });

    it("records the deadline and disables notifications before revoking sessions", async () => {
      const res = await handleAccountLifecycleRequest(
        post(
          { action: "request-deletion" },
          `Bearer ${tokenWithSession("sess-1")}`,
        ),
        buildDeps(),
      );

      expect(res.status).toBe(200);
      const body = (await res.json()) as Record<string, unknown>;
      expect(body.confirmed).toBe(true);
      expect(body.deletionDeadline).toBe("2026-08-31T12:00:00+00:00");
      expect(body.sessionsRevoked).toBe(true);

      expect(callLog).toEqual([
        "assert_active_session",
        "enter_pending_deletion",
        "revoke_operator_sessions",
      ]);
      expect(registry.enter_pending_deletion).toHaveBeenCalledWith({
        p_operator: OP_ID,
      });
      expect(registry.revoke_operator_sessions).toHaveBeenCalledWith({
        p_operator: OP_ID,
      });
    });

    it("never extends the deadline when deletion is requested again", async () => {
      registry.enter_pending_deletion = vi.fn(() =>
        Promise.resolve({
          data: {
            operator_id: OP_ID,
            deletion_deadline: "2026-08-31T12:00:00+00:00",
            already_pending: true,
          },
          error: null,
        }),
      );

      const res = await handleAccountLifecycleRequest(
        post(
          { action: "request-deletion" },
          `Bearer ${tokenWithSession("sess-1")}`,
        ),
        buildDeps(),
      );

      expect(res.status).toBe(200);
      const body = (await res.json()) as Record<string, unknown>;
      expect(body.confirmed).toBe(true);
      expect(body.deletionDeadline).toBe("2026-08-31T12:00:00+00:00");
      expect(body.alreadyPending).toBe(true);
    });

    it("stays confirmed when global session revocation fails", async () => {
      registry.revoke_operator_sessions = vi.fn(() =>
        Promise.resolve({
          data: null,
          error: { message: "revocation unavailable" },
        }),
      );

      const res = await handleAccountLifecycleRequest(
        post(
          { action: "request-deletion" },
          `Bearer ${tokenWithSession("sess-1")}`,
        ),
        buildDeps(),
      );

      expect(res.status).toBe(200);
      const body = (await res.json()) as Record<string, unknown>;
      expect(body.confirmed).toBe(true);
      expect(body.sessionsRevoked).toBe(false);
    });

    it("refuses when the presented JWT has no surviving server session", async () => {
      registry.assert_active_session = vi.fn(() =>
        Promise.resolve({ data: false, error: null }),
      );

      const res = await handleAccountLifecycleRequest(
        post(
          { action: "request-deletion" },
          `Bearer ${tokenWithSession("sess-gone")}`,
        ),
        buildDeps(),
      );

      expect(res.status).toBe(401);
      const body = (await res.json()) as Record<string, unknown>;
      expect(body.code).toBe("session_inactive");
      expect(callLog).not.toContain("enter_pending_deletion");
    });
  });

  describe("recover-account", () => {
    beforeEach(() => {
      registry.assert_active_session = vi.fn(() =>
        Promise.resolve({ data: true, error: null }),
      );
      registry.recover_account = vi.fn(() =>
        Promise.resolve({ data: { recovered: true }, error: null }),
      );
    });

    it("restores the account inside the Recovery window", async () => {
      const res = await handleAccountLifecycleRequest(
        post(
          { action: "recover-account" },
          `Bearer ${tokenWithSession("sess-new")}`,
        ),
        buildDeps(),
      );

      expect(res.status).toBe(200);
      const body = (await res.json()) as Record<string, unknown>;
      expect(body.recovered).toBe(true);
      expect(registry.recover_account).toHaveBeenCalledWith({
        p_operator: OP_ID,
      });
    });

    it("relays the server-side refusal once the window closes", async () => {
      registry.recover_account = vi.fn(() =>
        Promise.resolve({
          data: { recovered: false, error: "recovery_window_closed" },
          error: null,
        }),
      );

      const res = await handleAccountLifecycleRequest(
        post(
          { action: "recover-account" },
          `Bearer ${tokenWithSession("sess-new")}`,
        ),
        buildDeps(),
      );

      expect(res.status).toBe(403);
      const body = (await res.json()) as Record<string, unknown>;
      expect(body.code).toBe("recovery_window_closed");
    });

    it("requires a current server session created by the recovery sign-in", async () => {
      registry.assert_active_session = vi.fn(() =>
        Promise.resolve({ data: false, error: null }),
      );

      const res = await handleAccountLifecycleRequest(
        post(
          { action: "recover-account" },
          `Bearer ${tokenWithSession("sess-revoked")}`,
        ),
        buildDeps(),
      );

      expect(res.status).toBe(401);
      expect(callLog).not.toContain("recover_account");
    });
  });

  describe("purge-sweep", () => {
    beforeEach(() => {
      registry.claim_due_purge_batch = vi.fn(() =>
        Promise.resolve({
          data: [
            {
              operator_id: OP_ID,
              deletion_deadline: "2026-08-20T00:00:00+00:00",
            },
          ],
          error: null,
        }),
      );
      registry.finalize_operator_purge = vi.fn(() =>
        Promise.resolve({ data: true, error: null }),
      );
      registry.__storageObjects = vi.fn(() =>
        Promise.resolve({
          data: [
            { bucket_id: "exports", name: `${OP_ID}/a.json` },
            { bucket_id: "uploads", name: `${OP_ID}/b.bin` },
          ],
          error: null,
        }),
      );
    });

    function sweep(auth?: string) {
      return handleAccountLifecycleRequest(
        post({ action: "purge-sweep" }, auth),
        buildDeps(),
      );
    }

    it("rejects callers without the trusted sweep secret", async () => {
      const res = await sweep("Bearer wrong-secret");
      expect(res.status).toBe(401);
      expect(callLog).not.toContain("claim_due_purge_batch");
    });

    it("removes owned Storage objects before deleting the Auth identity", async () => {
      const res = await sweep("Bearer sweep-secret");

      expect(res.status).toBe(200);
      expect(removeCalls).toEqual([
        { bucket: "exports", names: [`${OP_ID}/a.json`] },
        { bucket: "uploads", names: [`${OP_ID}/b.bin`] },
      ]);
      expect(callLog).toEqual([
        "claim_due_purge_batch",
        "__storageObjects",
        "finalize_operator_purge",
      ]);
      const body = (await res.json()) as {
        claimed: number;
        results: Array<{ operatorId: string; purged: boolean }>;
      };
      expect(body.claimed).toBe(1);
      expect(body.results[0].purged).toBe(true);
    });

    it("treats already-absent Storage objects as removed", async () => {
      removeCalls.length = 0;
      storageApi = createStorageApi(removeCalls);
      Object.assign(storageApi.from("exports"), {});
      const failingRemove = vi
        .fn()
        .mockResolvedValueOnce([
          {
            name: `${OP_ID}/gone.json`,
            error: { message: "The resource was not found" },
          },
        ])
        .mockResolvedValue([]);
      storageApi = {
        from: vi.fn(() => ({ remove: failingRemove })),
      } as unknown as ReturnType<typeof createStorageApi>;

      registry.finalize_operator_purge = vi.fn(() =>
        Promise.resolve({ data: false, error: null }),
      );

      const res = await handleAccountLifecycleRequest(
        post({ action: "purge-sweep" }, "Bearer sweep-secret"),
        {
          anonClient: createAnonClient(),
          adminClient: createAdminClient(registry, callLog),
          storageApi,
          lifecycleSecret: "sweep-secret",
        },
      );

      expect(res.status).toBe(200);
      expect(callLog).toContain("finalize_operator_purge");
    });

    it("skips identity deletion when Storage cleanup fails so the sweep retries later", async () => {
      const failingStorage = {
        from: vi.fn(() => ({
          remove: vi.fn(async () => {
            throw new Error("storage unavailable");
          }),
        })),
      };

      const res = await handleAccountLifecycleRequest(
        post({ action: "purge-sweep" }, "Bearer sweep-secret"),
        {
          anonClient: createAnonClient(),
          adminClient: createAdminClient(registry, callLog),
          storageApi: failingStorage as unknown as ReturnType<
            typeof createStorageApi
          >,
          lifecycleSecret: "sweep-secret",
        },
      );

      expect(res.status).toBe(200);
      const body = (await res.json()) as {
        results: Array<{ operatorId: string; purged: boolean; error?: string }>;
      };
      expect(body.results[0].purged).toBe(false);
      expect(body.results[0].error).toContain("storage unavailable");
      expect(callLog).not.toContain("finalize_operator_purge");
    });
  });

  describe("voice gating for Pending deletion", () => {
    it("detects a frozen Operator through current database state", async () => {
      const admin = createAdminClient(
        {
          operator_is_pending_deletion: vi.fn(() =>
            Promise.resolve({ data: true, error: null }),
          ),
        },
        callLog,
      );

      await expect(isOperatorPendingDeletion(admin, OP_ID)).resolves.toBe(true);
    });

    it("fails closed when the database state cannot be read", async () => {
      const admin = createAdminClient(
        {
          operator_is_pending_deletion: vi.fn(() =>
            Promise.resolve({
              data: null,
              error: { message: "database unavailable" },
            }),
          ),
        },
        callLog,
      );

      await expect(isOperatorPendingDeletion(admin, OP_ID)).resolves.toBe(true);
    });

    it("blocks Voice capture for a frozen account before any provider work", async () => {
      const admin = createAdminClient(
        {
          operator_is_pending_deletion: vi.fn(() =>
            Promise.resolve({ data: true, error: null }),
          ),
        },
        callLog,
      );
      const fetchFn = vi.fn();

      const result = await processVoiceCapture(
        admin as unknown as SupabaseClient,
        OP_ID,
        new Uint8Array([0, 1, 2, 3]),
        {
          recordingStartTime: "2026-08-24T00:00:00Z",
          timezone: "UTC",
          deepInfraApiKey: "key",
        },
        fetchFn as unknown as typeof fetch,
      );

      expect(result.success).toBe(false);
      expect(result.error?.code).toBe("account_frozen");
      expect(result.error?.status).toBe(403);
      expect(fetchFn).not.toHaveBeenCalled();
      expect(callLog).toEqual(["operator_is_pending_deletion"]);
    });
  });
});
