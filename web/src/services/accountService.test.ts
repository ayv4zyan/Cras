import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { SupabaseClient } from "@supabase/supabase-js";
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
    supabaseUrl: "https://test.supabase.co",
    schema: vi.fn().mockReturnValue({ rpc: rpcMock }),
  };
  return { mockClient: mockClient as unknown as SupabaseClient, rpcMock };
}

describe("account service seam", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe("fetchAccountStatus", () => {
    it("posts a status action to the lifecycle endpoint with the bearer token", async () => {
      const { mockClient } = createMockClient();
      fetchMock.mockResolvedValue(
        new Response(
          JSON.stringify({
            deletionState: "active",
            deletionDeadline: null,
            recoveryAvailable: false,
          }),
          { status: 200 },
        ),
      );

      const status = await fetchAccountStatus(mockClient);

      expect(fetchMock).toHaveBeenCalledWith(
        "https://test.supabase.co/functions/v1/account-lifecycle",
        expect.objectContaining({
          method: "POST",
          headers: expect.objectContaining({
            Authorization: "Bearer session-token",
          }),
        }),
      );
      const body = JSON.parse(fetchMock.mock.calls[0][1].body);
      expect(body).toEqual({ action: "status" });
      expect(status).toEqual({
        deletionState: "active",
        deletionDeadline: null,
        recoveryAvailable: false,
      });
    });

    it("maps a frozen account with its deadline and recovery availability", async () => {
      const { mockClient } = createMockClient();
      fetchMock.mockResolvedValue(
        new Response(
          JSON.stringify({
            deletionState: "pending_deletion",
            deletionDeadline: "2026-08-31T00:00:00Z",
            recoveryAvailable: true,
          }),
          { status: 200 },
        ),
      );

      const status = await fetchAccountStatus(mockClient);

      expect(status.deletionState).toBe("pending_deletion");
      expect(status.deletionDeadline).toBe("2026-08-31T00:00:00Z");
      expect(status.recoveryAvailable).toBe(true);
    });

    it("throws an AccountLifecycleError carrying the server message on refusal", async () => {
      const { mockClient } = createMockClient();
      fetchMock.mockResolvedValue(
        new Response(JSON.stringify({ error: "Unauthorized" }), {
          status: 401,
        }),
      );

      await expect(fetchAccountStatus(mockClient)).rejects.toMatchObject({
        status: 401,
        message: "Unauthorized",
      });
    });
  });

  describe("requestAccountDeletion", () => {
    it("posts a request-deletion action and returns the confirmation", async () => {
      const { mockClient } = createMockClient();
      fetchMock.mockResolvedValue(
        new Response(
          JSON.stringify({
            confirmed: true,
            deletionState: "pending_deletion",
            deletionDeadline: "2026-08-31T12:00:00Z",
            sessionsRevoked: true,
          }),
          { status: 200 },
        ),
      );

      const result = await requestAccountDeletion(mockClient);

      const body = JSON.parse(fetchMock.mock.calls[0][1].body);
      expect(body).toEqual({ action: "request-deletion" });
      expect(result.confirmed).toBe(true);
      expect(result.deletionDeadline).toBe("2026-08-31T12:00:00Z");
      expect(result.sessionsRevoked).toBe(true);
    });

    it("still reports a confirmed deletion when global revocation failed", async () => {
      const { mockClient } = createMockClient();
      fetchMock.mockResolvedValue(
        new Response(
          JSON.stringify({
            confirmed: true,
            deletionState: "pending_deletion",
            deletionDeadline: "2026-08-31T12:00:00Z",
            sessionsRevoked: false,
          }),
          { status: 200 },
        ),
      );

      const result = await requestAccountDeletion(mockClient);

      expect(result.confirmed).toBe(true);
      expect(result.sessionsRevoked).toBe(false);
    });
  });

  describe("recoverAccount", () => {
    it("posts a recover-account action and resolves on success", async () => {
      const { mockClient } = createMockClient();
      fetchMock.mockResolvedValue(
        new Response(JSON.stringify({ recovered: true }), { status: 200 }),
      );

      await expect(recoverAccount(mockClient)).resolves.toBeUndefined();

      const body = JSON.parse(fetchMock.mock.calls[0][1].body);
      expect(body).toEqual({ action: "recover-account" });
    });

    it("surfaces the refusal reason when the Recovery window has closed", async () => {
      const { mockClient } = createMockClient();
      fetchMock.mockResolvedValue(
        new Response(
          JSON.stringify({
            error: "Recovery window has closed.",
            code: "recovery_window_closed",
          }),
          { status: 403 },
        ),
      );

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
