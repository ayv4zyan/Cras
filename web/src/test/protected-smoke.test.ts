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
    const result = await runGoogleAuthSmokeTest({
      googleClientId: "test-client-id.apps.googleusercontent.com",
    });
    expect(result.status).toBe("PASSED");
    expect(result.service).toBe("Google Authentication");
  });

  it("exercises DeepInfra smoke check when API key is provided", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200 } as Response);

    const result = await runDeepInfraSmokeTest({
      deepInfraApiKey: "sk-deepinfra-test-key",
    });
    expect(result.status).toBe("PASSED");
    expect(result.service).toBe("DeepInfra AI Voice Pipeline");

    globalThis.fetch = originalFetch;
  });

  it("exercises Web Push smoke check when VAPID keys are provided", async () => {
    const result = await runWebPushSmokeTest({
      vapidPrivateKey: "mock-vapid-private-key",
    });
    expect(result.status).toBe("PASSED");
    expect(result.service).toBe("Web Push (VAPID)");
  });

  it("exercises FCM smoke check when Firebase credentials are provided", async () => {
    const result = await runFcmSmokeTest({
      firebaseServiceAccount: JSON.stringify({ project_id: "cras-app" }),
    });
    expect(result.status).toBe("PASSED");
    expect(result.service).toBe("Firebase Cloud Messaging (FCM)");
  });

  it("exercises Supabase Cron smoke check when Service Role key is provided", async () => {
    const result = await runSupabaseCronSmokeTest({
      supabaseServiceRoleKey: "mock-service-role-key",
    });
    expect(result.status).toBe("PASSED");
    expect(result.service).toBe("Supabase Cron & Scheduled Workers");
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

    globalThis.fetch = originalFetch;
  });
});
