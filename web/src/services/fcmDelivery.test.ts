import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  processNotificationJob,
  buildFcmMessage,
  classifyFcmSendFailure,
  type LeasedJob,
  type FcmOptions,
} from "./notificationWorker";
import type { SupabaseClient } from "@supabase/supabase-js";

describe("FCM delivery for Android installations", () => {
  let mockSupabase: SupabaseClient;
  let mockRpc: ReturnType<typeof vi.fn>;

  const fcmOptions: Required<FcmOptions> = {
    projectId: "cras-test-project",
    clientEmail: "cras-sender@cras-test-project.iam.gserviceaccount.com",
    privateKey: "",
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    mockRpc = vi.fn().mockResolvedValue({ error: null });
    mockSupabase = {
      rpc: mockRpc,
      schema: vi.fn().mockReturnValue({
        rpc: mockRpc,
      }),
    } as unknown as SupabaseClient;

    const kp = await crypto.subtle.generateKey(
      {
        name: "RSASSA-PKCS1-v1_5",
        modulusLength: 2048,
        publicExponent: new Uint8Array([1, 0, 1]),
        hash: "SHA-256",
      },
      true,
      ["sign", "verify"],
    );
    const pkcs8 = await crypto.subtle.exportKey("pkcs8", kp.privateKey);
    const der = btoa(String.fromCharCode(...new Uint8Array(pkcs8)));
    const pem = `-----BEGIN PRIVATE KEY-----\n${der}\n-----END PRIVATE KEY-----\n`;
    fcmOptions.privateKey = pem;
  });

  // Android installation rows carry the FCM registration token as endpoint and
  // never hold web push encryption keys.
  const androidJob: LeasedJob = {
    job_id: "job-android-1",
    lease_token: "lease-token-android",
    task_id: "task-456",
    task_title: "Review quarterly roadmap",
    task_version: 2,
    task_completed_at: null,
    operator_id: "op-789",
    occurrence_key: "instant:2026-08-21T18:00:00Z",
    interpreted_due_at: new Date(Date.now() + 10000).toISOString(),
    missed_delivery_enabled: false,
    platform: "android",
    endpoint: "fDp5BeOvT9G-registration-token-opaque-value",
    p256dh: null,
    auth: null,
    is_active: true,
    local_enabled: true,
    permission_state: "granted",
  };

  function fcmFetchMock(response: { status: number; body?: unknown }) {
    return vi.fn().mockImplementation(async (url: string) => {
      if (url === "https://oauth2.googleapis.com/token") {
        return {
          status: 200,
          ok: true,
          text: async () =>
            JSON.stringify({
              access_token: "ya29.mock-token",
              expires_in: 3600,
            }),
          json: async () => ({
            access_token: "ya29.mock-token",
            expires_in: 3600,
          }),
        };
      }
      return {
        status: response.status,
        ok: response.status >= 200 && response.status < 300,
        text: async () =>
          response.body === undefined ? "" : JSON.stringify(response.body),
        json: async () => response.body ?? {},
      };
    });
  }

  describe("buildFcmMessage", () => {
    it("contains only the Task title plus opaque routing data", () => {
      const message = buildFcmMessage(androidJob, 0);
      expect(message.message.notification).toEqual({
        title: "Review quarterly roadmap",
      });
      expect(message.message.data).toEqual({
        taskId: "task-456",
        occurrenceKey: "instant:2026-08-21T18:00:00Z",
      });
      expect(JSON.stringify(message)).not.toContain("roadmap team notes");
    });

    it("uses the stable occurrence identity as the Android notification tag", () => {
      const message = buildFcmMessage(androidJob, 0);
      expect(message.message.android.tag).toBe("instant:2026-08-21T18:00:00Z");
      expect(message.message.android.priority).toBe("HIGH");
    });

    it("uses zero retention when missed delivery is disabled", () => {
      const message = buildFcmMessage(androidJob, 0);
      expect(message.message.android.ttl).toBe("0s");
    });

    it("bounds retention by the one hour missed-delivery deadline", () => {
      const dueIn30Min = new Date(Date.now() - 1800000).toISOString();
      const job = {
        ...androidJob,
        interpreted_due_at: dueIn30Min,
        missed_delivery_enabled: true,
      };
      const message = buildFcmMessage(job, 3600);
      expect(message.message.android.ttl).toBe("3600s");
    });
  });

  describe("classifyFcmSendFailure", () => {
    it("treats unregistered tokens and sender mismatch as permanent rejection", () => {
      expect(classifyFcmSendFailure(404, "UNREGISTERED")).toBe(
        "permanent_failure",
      );
      expect(classifyFcmSendFailure(410, "UNREGISTERED")).toBe(
        "permanent_failure",
      );
      expect(classifyFcmSendFailure(403, "SENDER_ID_MISMATCH")).toBe(
        "permanent_failure",
      );
    });

    it("treats invalid requests as non-retryable without disabling the endpoint", () => {
      expect(classifyFcmSendFailure(400, "INVALID_ARGUMENT")).toBe("cancelled");
    });

    it("treats quota, availability, and unknown server errors as transient", () => {
      expect(classifyFcmSendFailure(429, "QUOTA_EXCEEDED")).toBe(
        "transient_failure",
      );
      expect(classifyFcmSendFailure(500, "INTERNAL")).toBe("transient_failure");
      expect(classifyFcmSendFailure(503, "UNAVAILABLE")).toBe(
        "transient_failure",
      );
      expect(classifyFcmSendFailure(500)).toBe("transient_failure");
    });
  });

  describe("processNotificationJob dispatch", () => {
    it("delivers an FCM notification message without web push keys or HTTPS endpoints", async () => {
      const mockFetch = fcmFetchMock({ status: 200 });

      const result = await processNotificationJob(
        mockSupabase,
        androidJob,
        mockFetch as unknown as typeof fetch,
        { fcm: fcmOptions },
      );

      expect(result.result).toBe("delivered");
      expect(mockFetch).toHaveBeenCalledTimes(2);

      const [sendUrl, sendInit] = mockFetch.mock.calls[1];
      expect(sendUrl).toBe(
        "https://fcm.googleapis.com/v1/projects/cras-test-project/messages:send",
      );
      expect(sendInit.method).toBe("POST");
      expect(sendInit.headers.Authorization).toMatch(/^Bearer .+$/);

      const body = JSON.parse(sendInit.body);
      expect(body.message.notification.title).toBe("Review quarterly roadmap");
      expect(body.message.data.taskId).toBe("task-456");
      expect(body.message.data.occurrenceKey).toBe(
        "instant:2026-08-21T18:00:00Z",
      );
      expect(body.message.android.tag).toBe("instant:2026-08-21T18:00:00Z");

      expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
        p_job_id: "job-android-1",
        p_lease_token: "lease-token-android",
        p_result: "delivered",
        p_status_code: 200,
      });
    });

    it("exchanges a service account JWT for an OAuth token and reuses it across jobs", async () => {
      const uniqueOptions = {
        ...fcmOptions,
        clientEmail: `cache-test-${Date.now()}@cras-test-project.iam.gserviceaccount.com`,
      };
      const mockFetch = vi.fn().mockImplementation(async (url: string) => {
        if (url === "https://oauth2.googleapis.com/token") {
          return {
            status: 200,
            ok: true,
            text: async () =>
              JSON.stringify({
                access_token: "ya29.test-token",
                expires_in: 3600,
              }),
            json: async () => ({
              access_token: "ya29.test-token",
              expires_in: 3600,
            }),
          };
        }
        return { status: 200, ok: true, text: async () => "" };
      });

      await processNotificationJob(
        mockSupabase,
        androidJob,
        mockFetch as unknown as typeof fetch,
        { fcm: uniqueOptions },
      );
      await processNotificationJob(
        mockSupabase,
        { ...androidJob, job_id: "job-android-2", lease_token: "lease-2" },
        mockFetch as unknown as typeof fetch,
        { fcm: uniqueOptions },
      );

      const tokenCalls = mockFetch.mock.calls.filter(
        ([url]) => url === "https://oauth2.googleapis.com/token",
      );
      expect(tokenCalls.length).toBe(1);

      const [, tokenInit] = tokenCalls[0];
      expect(tokenInit.method).toBe("POST");
      expect(tokenInit.body).toContain(
        "grant_type=" +
          encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer"),
      );
      expect(tokenInit.body).toContain("assertion=eyJ");

      const sendCalls = mockFetch.mock.calls.filter(([url]) =>
        String(url).endsWith("/messages:send"),
      );
      expect(sendCalls.length).toBe(2);
      expect(sendCalls[0][1].headers.Authorization).toBe(
        "Bearer ya29.test-token",
      );
      expect(sendCalls[1][1].headers.Authorization).toBe(
        "Bearer ya29.test-token",
      );
    });

    it("records provider acceptance as delivered even though display is not proven", async () => {
      const mockFetch = fcmFetchMock({ status: 200 });

      const result = await processNotificationJob(
        mockSupabase,
        androidJob,
        mockFetch as unknown as typeof fetch,
        { fcm: fcmOptions },
      );

      expect(result.result).toBe("delivered");
      expect(result.statusCode).toBe(200);
      expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
        p_job_id: "job-android-1",
        p_lease_token: "lease-token-android",
        p_result: "delivered",
        p_status_code: 200,
      });
    });

    it("permanently rejects unregistered tokens so the endpoint is disabled and pending jobs cancelled", async () => {
      const mockFetch = fcmFetchMock({
        status: 404,
        body: { error: { code: 404, status: "UNREGISTERED" } },
      });

      const result = await processNotificationJob(
        mockSupabase,
        androidJob,
        mockFetch as unknown as typeof fetch,
        { fcm: fcmOptions },
      );

      expect(result.result).toBe("permanent_failure");
      expect(result.statusCode).toBe(404);
      expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
        p_job_id: "job-android-1",
        p_lease_token: "lease-token-android",
        p_result: "permanent_failure",
        p_status_code: 404,
      });
    });

    it("treats quota and availability errors as transient failures", async () => {
      for (const [status, body] of [
        [429, { error: { code: 429, status: "QUOTA_EXCEEDED" } }],
        [503, { error: { code: 503, status: "UNAVAILABLE" } }],
      ] as const) {
        mockRpc.mockClear();
        const mockFetch = fcmFetchMock({ status, body });

        const result = await processNotificationJob(
          mockSupabase,
          androidJob,
          mockFetch as unknown as typeof fetch,
          { fcm: fcmOptions },
        );

        expect(result.result).toBe("transient_failure");
        expect(result.statusCode).toBe(status);
        expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
          p_job_id: "job-android-1",
          p_lease_token: "lease-token-android",
          p_result: "transient_failure",
          p_status_code: status,
        });
      }
    });

    it("fails closed with cancellation when FCM credentials are incomplete", async () => {
      const mockFetch = vi.fn();

      const result = await processNotificationJob(
        mockSupabase,
        androidJob,
        mockFetch as unknown as typeof fetch,
        {
          fcm: {
            projectId: "cras-test-project",
            clientEmail: "",
            privateKey: "",
          },
        },
      );

      expect(result.result).toBe("cancelled");
      expect(result.error).toBe("Incomplete FCM configuration");
      expect(mockFetch).not.toHaveBeenCalled();
      expect(mockRpc).toHaveBeenCalledWith("record_notification_result", {
        p_job_id: "job-android-1",
        p_lease_token: "lease-token-android",
        p_result: "cancelled",
        p_status_code: null,
      });
    });

    it("still enforces task completion, grace window, and eligibility preconditions for Android jobs", async () => {
      const completedJob: LeasedJob = {
        ...androidJob,
        task_completed_at: new Date().toISOString(),
      };
      const mockFetch = vi.fn();
      const result = await processNotificationJob(
        mockSupabase,
        completedJob,
        mockFetch as unknown as typeof fetch,
        { fcm: fcmOptions },
      );
      expect(result.result).toBe("cancelled");
      expect(mockFetch).not.toHaveBeenCalled();

      const ineligibleJob: LeasedJob = {
        ...androidJob,
        local_enabled: false,
      };
      const result2 = await processNotificationJob(
        mockSupabase,
        ineligibleJob,
        mockFetch as unknown as typeof fetch,
        { fcm: fcmOptions },
      );
      expect(result2.result).toBe("cancelled");
      expect(mockFetch).not.toHaveBeenCalled();

      const expiredJob: LeasedJob = {
        ...androidJob,
        interpreted_due_at: new Date(Date.now() - 300000).toISOString(),
        missed_delivery_enabled: false,
      };
      const result3 = await processNotificationJob(
        mockSupabase,
        expiredJob,
        mockFetch as unknown as typeof fetch,
        { fcm: fcmOptions },
      );
      expect(result3.result).toBe("expired");
      expect(mockFetch).not.toHaveBeenCalled();
    });

    it("keeps the web push path unchanged when an FCM configuration is present", async () => {
      const webJob: LeasedJob = {
        job_id: "job-web-1",
        lease_token: "lease-web",
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
        p256dh:
          "BKi_qdsfjYSlus6qWa3GWDdPB_cnzDCYo1FPz5EB6oXC6lTe-nQAVDOBlf2q_QZyV75z0ppcUy_We7cQtnggPLY",
        auth: "KioqKioqKioqKioqKioqKg",
        is_active: true,
        local_enabled: true,
        permission_state: "granted",
      };
      const mockFetch = vi.fn().mockResolvedValue({ status: 201 });

      const result = await processNotificationJob(
        mockSupabase,
        webJob,
        mockFetch as unknown as typeof fetch,
        { fcm: fcmOptions },
      );

      expect(result.result).toBe("delivered");
      const [calledUrl] = mockFetch.mock.calls[0];
      expect(calledUrl).toBe("https://push.example.com/sub/123");
    });
  });
});
