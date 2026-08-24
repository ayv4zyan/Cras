import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { SupabaseClient } from "@supabase/supabase-js";
import { FunctionsFetchError, FunctionsHttpError } from "@supabase/supabase-js";
import {
  fetchAccountStatus,
  requestAccountDeletion,
  recoverAccount,
  generateAccountExport,
  downloadAccountExport,
  clearLocalOperatorData,
  stageReauthIntent,
  consumeReauthIntent,
  REAUTH_INTENT_STORAGE_KEY,
  AccountLifecycleError,
} from "./accountService";
import { getStorageKey } from "./outboxService";

function createMockClient(overrides?: {
  session?: { access_token: string } | null;
}) {
  const rpcMock = vi.fn();
  const invokeMock = vi.fn();
  const mockClient = {
    auth: {
      getSession: vi.fn().mockResolvedValue({
        data: {
          session:
            overrides?.session === undefined
              ? { access_token: "session-token" }
              : overrides.session,
        },
        error: null,
      }),
    },
    schema: vi.fn().mockReturnValue({ rpc: rpcMock }),
    functions: { invoke: invokeMock },
  };
  return {
    mockClient: mockClient as unknown as SupabaseClient,
    rpcMock,
    invokeMock,
  };
}

describe("account service seam", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  describe("fetchAccountStatus", () => {
    it("posts a status action to the lifecycle endpoint with the bearer token", async () => {
      const { mockClient, invokeMock } = createMockClient();
      invokeMock.mockResolvedValue({
        data: {
          deletionState: "active",
          deletionDeadline: null,
          recoveryAvailable: false,
        },
        error: null,
      });

      const status = await fetchAccountStatus(mockClient);

      expect(invokeMock).toHaveBeenCalledWith("account-lifecycle", {
        headers: { Authorization: "Bearer session-token" },
        body: { action: "status" },
      });
      expect(status).toEqual({
        deletionState: "active",
        deletionDeadline: null,
        recoveryAvailable: false,
      });
    });

    it("maps a frozen account with its deadline and recovery availability", async () => {
      const { mockClient, invokeMock } = createMockClient();
      invokeMock.mockResolvedValue({
        data: {
          deletionState: "pending_deletion",
          deletionDeadline: "2026-08-31T00:00:00Z",
          recoveryAvailable: true,
        },
        error: null,
      });

      const status = await fetchAccountStatus(mockClient);

      expect(status.deletionState).toBe("pending_deletion");
      expect(status.deletionDeadline).toBe("2026-08-31T00:00:00Z");
      expect(status.recoveryAvailable).toBe(true);
    });

    it("throws an AccountLifecycleError carrying the server message on refusal", async () => {
      const { mockClient, invokeMock } = createMockClient();
      const response = new Response(JSON.stringify({ error: "Unauthorized" }), {
        status: 401,
      });
      invokeMock.mockResolvedValue({
        data: null,
        error: new FunctionsHttpError(response),
        response,
      });

      await expect(fetchAccountStatus(mockClient)).rejects.toMatchObject({
        status: 401,
        message: "Unauthorized",
      });
    });

    it("maps transport failures to a retryable network error", async () => {
      const { mockClient, invokeMock } = createMockClient();
      invokeMock.mockResolvedValue({
        data: null,
        error: new FunctionsFetchError(new Error("offline")),
      });

      await expect(fetchAccountStatus(mockClient)).rejects.toMatchObject({
        status: 0,
        code: "network_error",
        isNetworkError: true,
      });
    });
  });

  describe("requestAccountDeletion", () => {
    it("posts a request-deletion action and returns the confirmation", async () => {
      const { mockClient, invokeMock } = createMockClient();
      invokeMock.mockResolvedValue({
        data: {
          confirmed: true,
          deletionState: "pending_deletion",
          deletionDeadline: "2026-08-31T12:00:00Z",
          sessionsRevoked: true,
        },
        error: null,
      });

      const result = await requestAccountDeletion(mockClient);

      expect(invokeMock).toHaveBeenCalledWith(
        "account-lifecycle",
        expect.objectContaining({ body: { action: "request-deletion" } }),
      );
      expect(result.confirmed).toBe(true);
      expect(result.deletionDeadline).toBe("2026-08-31T12:00:00Z");
      expect(result.sessionsRevoked).toBe(true);
    });

    it("still reports a confirmed deletion when global revocation failed", async () => {
      const { mockClient, invokeMock } = createMockClient();
      invokeMock.mockResolvedValue({
        data: {
          confirmed: true,
          deletionState: "pending_deletion",
          deletionDeadline: "2026-08-31T12:00:00Z",
          sessionsRevoked: false,
        },
        error: null,
      });

      const result = await requestAccountDeletion(mockClient);

      expect(result.confirmed).toBe(true);
      expect(result.sessionsRevoked).toBe(false);
    });
  });

  describe("recoverAccount", () => {
    it("posts a recover-account action and resolves on success", async () => {
      const { mockClient, invokeMock } = createMockClient();
      invokeMock.mockResolvedValue({
        data: { recovered: true },
        error: null,
      });

      await expect(recoverAccount(mockClient)).resolves.toBeUndefined();

      expect(invokeMock).toHaveBeenCalledWith(
        "account-lifecycle",
        expect.objectContaining({ body: { action: "recover-account" } }),
      );
    });

    it("surfaces the refusal reason when the Recovery window has closed", async () => {
      const { mockClient, invokeMock } = createMockClient();
      const response = new Response(
        JSON.stringify({
          error: "Recovery window has closed.",
          code: "recovery_window_closed",
        }),
        { status: 403 },
      );
      invokeMock.mockResolvedValue({
        data: null,
        error: new FunctionsHttpError(response),
        response,
      });

      let caught: unknown = null;
      try {
        await recoverAccount(mockClient);
      } catch (err) {
        caught = err;
      }

      expect(caught).toBeInstanceOf(AccountLifecycleError);
      expect((caught as AccountLifecycleError).code).toBe(
        "recovery_window_closed",
      );
      expect((caught as AccountLifecycleError).status).toBe(403);
    });
  });

  describe("generateAccountExport", () => {
    it("calls the export RPC through the api schema and parses the snapshot", async () => {
      const { mockClient, rpcMock } = createMockClient();
      const snapshot = {
        exportedAt: "2026-08-24T10:00:00Z",
        tasks: [{ id: "t-1", title: "Write tests" }],
        labels: [],
        taskLabels: [],
        comments: [],
        settings: { missedDeliveryEnabled: false },
      };
      rpcMock.mockResolvedValue({
        data: JSON.stringify(snapshot),
        error: null,
      });

      const result = await generateAccountExport(mockClient);

      expect(rpcMock).toHaveBeenCalledWith("export_operator_data");
      expect(result.tasks).toEqual([{ id: "t-1", title: "Write tests" }]);
      expect(result.exportedAt).toBe("2026-08-24T10:00:00Z");
    });

    it("throws when the export RPC fails", async () => {
      const { mockClient, rpcMock } = createMockClient();
      rpcMock.mockResolvedValue({
        data: null,
        error: { message: "permission denied" },
      });

      await expect(generateAccountExport(mockClient)).rejects.toThrow(
        /export/i,
      );
    });
  });

  describe("downloadAccountExport", () => {
    let createObjectUrlMock: ReturnType<typeof vi.fn>;
    let revokeObjectUrlMock: ReturnType<typeof vi.fn>;
    let clickSpy: ReturnType<typeof vi.fn>;
    const originalCreateObjectUrl = URL.createObjectURL;
    const originalRevokeObjectUrl = URL.revokeObjectURL;

    beforeEach(() => {
      createObjectUrlMock = vi.fn(() => "blob:mock-url");
      revokeObjectUrlMock = vi.fn();
      Object.defineProperty(URL, "createObjectURL", {
        value: createObjectUrlMock,
        configurable: true,
        writable: true,
      });
      Object.defineProperty(URL, "revokeObjectURL", {
        value: revokeObjectUrlMock,
        configurable: true,
        writable: true,
      });
      clickSpy = vi.fn();
      vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(
        clickSpy,
      );
    });

    afterEach(() => {
      Object.defineProperty(URL, "createObjectURL", {
        value: originalCreateObjectUrl,
        configurable: true,
        writable: true,
      });
      Object.defineProperty(URL, "revokeObjectURL", {
        value: originalRevokeObjectUrl,
        configurable: true,
        writable: true,
      });
      vi.restoreAllMocks();
    });

    it("downloads the canonical JSON snapshot before any confirmation", async () => {
      const { mockClient, rpcMock } = createMockClient();
      const snapshot = {
        exportedAt: "2026-08-24T10:00:00Z",
        tasks: [],
        labels: [],
        taskLabels: [],
        comments: [],
        settings: null,
      };
      rpcMock.mockResolvedValue({
        data: JSON.stringify(snapshot),
        error: null,
      });

      await downloadAccountExport(mockClient);

      expect(createObjectUrlMock).toHaveBeenCalledTimes(1);
      const blob = createObjectUrlMock.mock.calls[0][0] as Blob;
      expect(blob.type).toBe("application/json");
      expect(await blob.text()).toBe(JSON.stringify(snapshot));
      expect(clickSpy).toHaveBeenCalledTimes(1);

      const anchor = clickSpy.mock.instances[0] as HTMLAnchorElement;
      expect(anchor.download).toMatch(/^cras-export-\d{4}-\d{2}-\d{2}\.json$/);
      expect(revokeObjectUrlMock).toHaveBeenCalledWith("blob:mock-url");
    });
  });

  describe("clearLocalOperatorData", () => {
    it("clears the Outbox, cached plan default, and unsubmitted draft without touching unrelated keys", () => {
      const operatorId = "operator-1";
      localStorage.setItem(getStorageKey(operatorId), "[]");
      localStorage.setItem(
        "cras_effective_default_timed_plan_type",
        "floating",
      );
      localStorage.setItem("unrelated", "keep-me");
      sessionStorage.setItem("cras_unsubmitted_task_input", "half typed");

      clearLocalOperatorData(operatorId);

      expect(localStorage.getItem(getStorageKey(operatorId))).toBeNull();
      expect(
        localStorage.getItem("cras_effective_default_timed_plan_type"),
      ).toBeNull();
      expect(sessionStorage.getItem("cras_unsubmitted_task_input")).toBeNull();
      expect(localStorage.getItem("unrelated")).toBe("keep-me");
    });

    it("also drops the staged reauthentication intent for a clean sign-out", () => {
      stageReauthIntent("operator-1");

      clearLocalOperatorData("operator-1");

      expect(sessionStorage.getItem(REAUTH_INTENT_STORAGE_KEY)).toBeNull();
    });
  });

  describe("reauthentication intent", () => {
    it("stages and consumes the intent for the same identity exactly once", () => {
      stageReauthIntent("op-1");

      expect(consumeReauthIntent("op-1")).toBe("op-1");
      expect(consumeReauthIntent("op-1")).toBeNull();
    });

    it("is discarded when the returning identity differs", () => {
      stageReauthIntent("op-1");

      expect(consumeReauthIntent("someone-else")).toBeNull();
      expect(consumeReauthIntent("op-1")).toBeNull();
    });

    it("returns null when nothing was staged", () => {
      expect(consumeReauthIntent("op-1")).toBeNull();
    });
  });
});
