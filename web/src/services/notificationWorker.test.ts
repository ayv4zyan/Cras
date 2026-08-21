import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  processNotificationJob,
  generateVapidHeader,
  encryptWebPushPayload,
  deriveTopic,
  type LeasedJob,
} from "./notificationWorker";
import type { SupabaseClient } from "@supabase/supabase-js";

describe("processNotificationJob Worker Logic", () => {
  let mockSupabase: SupabaseClient;
  let mockRpc: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.clearAllMocks();
    mockRpc = vi.fn().mockResolvedValue({ error: null });
    mockSupabase = {
      rpc: mockRpc,
      schema: vi.fn().mockReturnValue({
        rpc: mockRpc,
      }),
    } as unknown as SupabaseClient;
  });

  // Valid 65-byte uncompressed P-256 public key (base64url) and 16-byte auth
  const validP256dh =
    "BKi_qdsfjYSlus6qWa3GWDdPB_cnzDCYo1FPz5EB6oXC6lTe-nQAVDOBlf2q_QZyV75z0ppcUy_We7cQtnggPLY";
  const validAuth = "KioqKioqKioqKioqKioqKg";

  const baseJob: LeasedJob = {
    job_id: "job-123",
    lease_token: "lease-token-abc",
    task_id: "task-456",
    task_title: "Review quarterly roadmap",
    task_version: 2,
    task_completed_at: null,
    operator_id: "op-789",
    occurrence_key: "instant:2026-08-21T18:00:00Z",
    interpreted_due_at: new Date(Date.now() + 10000).toISOString(),
    missed_delivery_enabled: false,
    platform: "web",
    endpoint: "https://push.example.com/sub/123",
    p256dh: validP256dh,
    auth: validAuth,
    is_active: true,
    local_enabled: true,
    permission_state: "granted",
  };

  it("cancels job immediately if task is completed without sending push request", async () => {
    const completedJob: LeasedJob = {
      ...baseJob,
      task_completed_at: new Date().toISOString(),
    };
    const mockFetch = vi.fn();

    const result = await processNotificationJob(
      mockSupabase,
      completedJob,
      mockFetch as unknown as typeof fetch,
    );
    expect(result.result).toBe("cancelled");
    expect(mockFetch).not.toHaveBeenCalled();
    expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
      p_job_id: "job-123",
      p_lease_token: "lease-token-abc",
      p_result: "cancelled",
      p_status_code: null,
    });
  });

  it("expires job if past operational grace deadline without sending push request", async () => {
    const expiredJob: LeasedJob = {
      ...baseJob,
      interpreted_due_at: new Date(Date.now() - 300000).toISOString(), // 5 mins ago
      missed_delivery_enabled: false, // 2 min grace
    };
    const mockFetch = vi.fn();

    const result = await processNotificationJob(
      mockSupabase,
      expiredJob,
      mockFetch as unknown as typeof fetch,
    );
    expect(result.result).toBe("expired");
    expect(mockFetch).not.toHaveBeenCalled();
    expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
      p_job_id: "job-123",
      p_lease_token: "lease-token-abc",
      p_result: "expired",
      p_status_code: null,
    });
  });

  it("cancels job if installation is no longer eligible (e.g. local_enabled = false)", async () => {
    const ineligibleJob: LeasedJob = {
      ...baseJob,
      local_enabled: false,
    };
    const mockFetch = vi.fn();

    const result = await processNotificationJob(
      mockSupabase,
      ineligibleJob,
      mockFetch as unknown as typeof fetch,
    );
    expect(result.result).toBe("cancelled");
    expect(mockFetch).not.toHaveBeenCalled();
    expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
      p_job_id: "job-123",
      p_lease_token: "lease-token-abc",
      p_result: "cancelled",
      p_status_code: null,
    });
  });

  it("records permanent failure for non-HTTPS endpoint without sending push request", async () => {
    const httpJob: LeasedJob = {
      ...baseJob,
      endpoint: "http://push.example.com/sub/123",
    };
    const mockFetch = vi.fn();

    const result = await processNotificationJob(
      mockSupabase,
      httpJob,
      mockFetch as unknown as typeof fetch,
    );
    expect(result.result).toBe("permanent_failure");
    expect(mockFetch).not.toHaveBeenCalled();
    expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
      p_job_id: "job-123",
      p_lease_token: "lease-token-abc",
      p_result: "permanent_failure",
      p_status_code: null,
    });
  });

  it("delivers Web Push using aes128gcm encryption and binary body", async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      status: 201,
    });

    const result = await processNotificationJob(
      mockSupabase,
      baseJob,
      mockFetch as unknown as typeof fetch,
    );

    expect(result.result).toBe("delivered");
    expect(mockFetch).toHaveBeenCalledTimes(1);

    const [calledUrl, calledInit] = mockFetch.mock.calls[0];
    expect(calledUrl).toBe("https://push.example.com/sub/123");
    expect(calledInit.method).toBe("POST");
    expect(calledInit.headers.TTL).toBe("0"); // missed_delivery_enabled = false -> TTL 0
    expect(calledInit.headers.Urgency).toBe("high");
    expect(calledInit.headers["Content-Type"]).toBe("application/octet-stream");
    expect(calledInit.headers["Content-Encoding"]).toBe("aes128gcm");
    expect(typeof calledInit.headers.Topic).toBe("string");

    // Body must be encrypted binary, not plaintext JSON
    expect(calledInit.body).toBeInstanceOf(Uint8Array);
    expect(calledInit.body.length).toBeGreaterThan(86);

    expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
      p_job_id: "job-123",
      p_lease_token: "lease-token-abc",
      p_result: "delivered",
      p_status_code: 201,
    });
  });

  it("fails permanently when push encryption keys are missing or invalid without sending unencrypted JSON", async () => {
    const missingKeysJob: LeasedJob = {
      ...baseJob,
      p256dh: null,
      auth: null,
    };
    const mockFetch = vi.fn();

    const result = await processNotificationJob(
      mockSupabase,
      missingKeysJob,
      mockFetch as unknown as typeof fetch,
    );

    expect(result.result).toBe("permanent_failure");
    expect(mockFetch).not.toHaveBeenCalled();
    expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
      p_job_id: "job-123",
      p_lease_token: "lease-token-abc",
      p_result: "permanent_failure",
      p_status_code: null,
    });

    const invalidKeysJob: LeasedJob = {
      ...baseJob,
      p256dh: "short-key",
      auth: "short-auth",
    };
    const result2 = await processNotificationJob(
      mockSupabase,
      invalidKeysJob,
      mockFetch as unknown as typeof fetch,
    );

    expect(result2.result).toBe("permanent_failure");
    expect(mockFetch).not.toHaveBeenCalled();
  });

  it("computes 1-hour TTL when missed delivery is enabled", async () => {
    const missedJob: LeasedJob = {
      ...baseJob,
      interpreted_due_at: new Date(Date.now() + 1000).toISOString(),
      missed_delivery_enabled: true,
    };
    const mockFetch = vi.fn().mockResolvedValue({
      status: 200,
    });

    const result = await processNotificationJob(
      mockSupabase,
      missedJob,
      mockFetch as unknown as typeof fetch,
    );

    expect(result.result).toBe("delivered");
    const [, calledInit] = mockFetch.mock.calls[0];
    const ttl = parseInt(calledInit.headers.TTL, 10);
    expect(ttl).toBeGreaterThan(3500);
    expect(ttl).toBeLessThanOrEqual(3605);
  });

  it("supports VAPID signing with raw 32-byte and PKCS#8 private keys, and fails closed if invalid", async () => {
    // Generate a valid ECDSA P-256 key pair for test
    const kp = await crypto.subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign", "verify"],
    );
    const jwk = await crypto.subtle.exportKey("jwk", kp.privateKey);
    const rawKey = jwk.d!;
    const mockFetch = vi.fn().mockResolvedValue({ status: 200 });

    const result = await processNotificationJob(
      mockSupabase,
      baseJob,
      mockFetch as unknown as typeof fetch,
      {
        vapidPrivateKey: rawKey,
        vapidPublicKey: "test-vapid-public-key",
        vapidSubject: "mailto:support@cras.app",
      },
    );

    expect(result.result).toBe("delivered");
    const [, calledInit] = mockFetch.mock.calls[0];
    expect(calledInit.headers.Authorization).toMatch(
      /^vapid t=[^,]+, k=test-vapid-public-key$/,
    );

    // Fail closed if VAPID keys are misconfigured
    const mockFetch2 = vi.fn();
    const result2 = await processNotificationJob(
      mockSupabase,
      baseJob,
      mockFetch2 as unknown as typeof fetch,
      {
        vapidPrivateKey: "bad-vapid-key",
        vapidPublicKey: "test-vapid-public-key",
      },
    );

    expect(result2.result).toBe("permanent_failure");
    expect(mockFetch2).not.toHaveBeenCalled();
    expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
      p_job_id: "job-123",
      p_lease_token: "lease-token-abc",
      p_result: "permanent_failure",
      p_status_code: null,
    });
  });

  it("handles non-retryable statuses (400, 401, 403, 404, 410, 413) as permanent failures", async () => {
    for (const status of [400, 401, 403, 404, 410, 413]) {
      mockRpc.mockClear();
      const mockFetch = vi.fn().mockResolvedValue({ status });

      const result = await processNotificationJob(
        mockSupabase,
        baseJob,
        mockFetch as unknown as typeof fetch,
      );

      expect(result.result).toBe("permanent_failure");
      expect(result.statusCode).toBe(status);
      expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
        p_job_id: "job-123",
        p_lease_token: "lease-token-abc",
        p_result: "permanent_failure",
        p_status_code: status,
      });
    }
  });

  it("handles retryable server errors (500, 502, 503, 429) as transient failures", async () => {
    for (const status of [500, 502, 503, 429]) {
      mockRpc.mockClear();
      const mockFetch = vi.fn().mockResolvedValue({ status });

      const result = await processNotificationJob(
        mockSupabase,
        baseJob,
        mockFetch as unknown as typeof fetch,
      );

      expect(result.result).toBe("transient_failure");
      expect(result.statusCode).toBe(status);
      expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
        p_job_id: "job-123",
        p_lease_token: "lease-token-abc",
        p_result: "transient_failure",
        p_status_code: status,
      });
    }
  });

  it("handles fetch network exception by recording transient_failure without status code", async () => {
    const mockFetch = vi.fn().mockRejectedValue(new Error("Network timeout"));

    const result = await processNotificationJob(
      mockSupabase,
      baseJob,
      mockFetch as unknown as typeof fetch,
    );

    expect(result.result).toBe("transient_failure");
    expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
      p_job_id: "job-123",
      p_lease_token: "lease-token-abc",
      p_result: "transient_failure",
      p_status_code: null,
    });
  });

  it("derives deterministic 32-character topic", async () => {
    const topic1 = await deriveTopic(
      "instant:2026-08-21T18:00:00Z",
      "task-123",
    );
    const topic2 = await deriveTopic(
      "instant:2026-08-21T18:00:00Z",
      "task-123",
    );
    const topic3 = await deriveTopic(
      "instant:2026-08-21T18:00:00Z",
      "task-456",
    );

    expect(topic1).toBe(topic2);
    expect(topic1.length).toBeLessThanOrEqual(32);
    expect(topic1).not.toBe(topic3);
  });

  it("encryptWebPushPayload produces RFC 8291 payload for valid keys and returns null for invalid keys", async () => {
    const payload = JSON.stringify({
      taskId: "task-1",
      occurrenceKey: "k",
      title: "Test",
    });
    const encrypted = await encryptWebPushPayload(
      payload,
      validP256dh,
      validAuth,
    );
    expect(encrypted).toBeInstanceOf(Uint8Array);
    expect(encrypted!.length).toBeGreaterThan(86);

    const invalidLength = await encryptWebPushPayload(
      payload,
      "short-key",
      "short-auth",
    );
    expect(invalidLength).toBeNull();
  });

  it("generateVapidHeader creates valid JWT token string", async () => {
    const kp = await crypto.subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign", "verify"],
    );
    const jwk = await crypto.subtle.exportKey("jwk", kp.privateKey);
    const header = await generateVapidHeader(
      "https://push.example.com/sub/123",
      jwk.d!,
      "vapid-pub-key",
    );
    expect(header).toMatch(/^vapid t=[^,]+, k=vapid-pub-key$/);
  });
});
