import { describe, it, expect, vi } from "vitest";
import {
  runAllSmokeTests,
  sanitizeSecret,
  runGoogleAuthSmokeTest,
  runDeepInfraSmokeTest,
  runWebPushSmokeTest,
  runFcmSmokeTest,
  runSupabaseCronSmokeTest,
  runHostedDeploymentSmokeTest,
} from "../services/smokeService";

describe("Protected Real-Service Smoke Test Suite (AC 5)", () => {
  it("sanitizes secrets preventing leakage into logs or reports", () => {
    expect(sanitizeSecret(undefined)).toBe("[REDACTED]");
    expect(sanitizeSecret("")).toBe("[REDACTED]");
    expect(sanitizeSecret("12345")).toBe("[REDACTED]");
    expect(sanitizeSecret("sk-deepinfra-secret-12345678")).toBe("sk-...678");
  });

  it("handles non-secret environments gracefully without failing or leaking secrets", async () => {
    const results = await runAllSmokeTests({});
    expect(results.length).toBe(6);
    for (const result of results) {
      expect(result.status).toBe("SKIPPED_NO_SECRET");
      expect(result.message).toBeDefined();
    }
  });

  it("exercises Google Auth smoke check when credentials are provided", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200 } as Response);

    const validResult = await runGoogleAuthSmokeTest({
      googleClientId: "test-client-id.apps.googleusercontent.com",
      googleClientSecret: "test-secret",
    });
    expect(validResult.status).toBe("PASSED");
    expect(validResult.service).toBe("Google Authentication");

    const invalidResult = await runGoogleAuthSmokeTest({
      googleClientId: "invalid-client-id",
      googleClientSecret: "some-secret",
    });
    expect(invalidResult.status).toBe("FAILED");

    globalThis.fetch = originalFetch;
  });

  it("exercises DeepInfra smoke check and handles success and failure", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200 } as Response);

    const successResult = await runDeepInfraSmokeTest({
      deepInfraApiKey: "sk-deepinfra-test-key",
    });
    expect(successResult.status).toBe("PASSED");
    expect(successResult.service).toBe("DeepInfra AI Voice Pipeline");

    globalThis.fetch = vi
      .fn()
      .mockResolvedValue({ ok: false, status: 401 } as Response);

    const failureResult = await runDeepInfraSmokeTest({
      deepInfraApiKey: "sk-deepinfra-invalid-key",
    });
    expect(failureResult.status).toBe("FAILED");
    expect(failureResult.message).toContain("401");

    globalThis.fetch = originalFetch;
  });

  it("exercises Web Push smoke check with valid and invalid VAPID keys", async () => {
    const validResult = await runWebPushSmokeTest({
      vapidPublicKey:
        "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkjTk-5xC2TePV-08",
      vapidPrivateKey: "6B29FC40-62FB-42E6-B543-A12B7C80EF21_mock_key_val",
    });
    expect(validResult.status).toBe("PASSED");
    expect(validResult.service).toBe("Web Push (VAPID)");

    const invalidResult = await runWebPushSmokeTest({
      vapidPublicKey: "too-short",
      vapidPrivateKey: "bad",
    });
    expect(invalidResult.status).toBe("FAILED");
  });

  it("exercises FCM smoke check when Firebase credentials are provided", async () => {
    const validServiceAccount = JSON.stringify({
      project_id: "cras-app-prod",
      client_email: "firebase-adminsdk@cras-app-prod.iam.gserviceaccount.com",
      private_key:
        "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBA\n-----END PRIVATE KEY-----\n",
    });

    const validResult = await runFcmSmokeTest({
      firebaseServiceAccount: validServiceAccount,
    });
    expect(validResult.status).toBe("PASSED");
    expect(validResult.service).toBe("Firebase Cloud Messaging (FCM)");

    const invalidResult = await runFcmSmokeTest({
      firebaseServiceAccount: JSON.stringify({ project_id: "cras-app" }),
    });
    expect(invalidResult.status).toBe("FAILED");
  });

  it("exercises Supabase Cron smoke check when Service Role key is provided", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200 } as Response);

    const validResult = await runSupabaseCronSmokeTest({
      supabaseUrl: "https://mock-supabase.co",
      supabaseServiceRoleKey: "mock-service-role-key",
    });
    expect(validResult.status).toBe("PASSED");
    expect(validResult.service).toBe("Supabase Cron & Scheduled Workers");

    const invalidSchemeResult = await runSupabaseCronSmokeTest({
      supabaseUrl: "ftp://mock-supabase.co",
      supabaseServiceRoleKey: "mock-service-role-key",
    });
    expect(invalidSchemeResult.status).toBe("FAILED");

    globalThis.fetch = originalFetch;
  });

  it("exercises Hosted Deployment smoke check when URL is provided", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200 } as Response);

    const result = await runHostedDeploymentSmokeTest({
      hostedAppUrl: "https://cras.app",
    });
    expect(result.status).toBe("PASSED");
    expect(result.service).toBe("Hosted Deployment Availability");

    globalThis.fetch = vi
      .fn()
      .mockResolvedValue({ ok: false, status: 502 } as Response);

    const failResult = await runHostedDeploymentSmokeTest({
      hostedAppUrl: "https://cras.app",
    });
    expect(failResult.status).toBe("FAILED");

    globalThis.fetch = originalFetch;
  });
});
