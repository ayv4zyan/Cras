import { describe, it, expect, vi, beforeEach } from "vitest";
import { processNotificationJob, type LeasedJob } from "./notificationWorker";
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
    p256dh: "p256dh-key",
    auth: "auth-key",
    is_active: true,
    local_enabled: true,
    permission_state: "granted",
  };

  it("cancels job immediately if task is completed", async () => {
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

  it("expires job if past operational grace deadline", async () => {
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

  it("delivers Web Push with only task title and routing identifiers in payload", async () => {
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
    expect(typeof calledInit.headers.Topic).toBe("string");

    const parsedBody = JSON.parse(calledInit.body);
    expect(parsedBody).toEqual({
      taskId: "task-456",
      occurrenceKey: "instant:2026-08-21T18:00:00Z",
      title: "Review quarterly roadmap",
    });
    // Ensure no sensitive fields or other task fields leak
    expect(parsedBody.description).toBeUndefined();
    expect(parsedBody.comments).toBeUndefined();
    expect(parsedBody.labels).toBeUndefined();

    expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
      p_job_id: "job-123",
      p_lease_token: "lease-token-abc",
      p_result: "delivered",
      p_status_code: 201,
    });
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

  it("handles permanent 410 Gone failure by recording permanent_failure", async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      status: 410,
    });

    const result = await processNotificationJob(
      mockSupabase,
      baseJob,
      mockFetch as unknown as typeof fetch,
    );

    expect(result.result).toBe("permanent_failure");
    expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
      p_job_id: "job-123",
      p_lease_token: "lease-token-abc",
      p_result: "permanent_failure",
      p_status_code: 410,
    });
  });

  it("handles transient 503 Service Unavailable by recording transient_failure", async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      status: 503,
    });

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
      p_status_code: 503,
    });
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
});
