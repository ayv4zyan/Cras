export {
  type SmokeTestConfig,
  type SmokeTestResult,
  sanitizeSecret,
  runGoogleAuthSmokeTest,
  runDeepInfraSmokeTest,
  runWebPushSmokeTest,
  runFcmSmokeTest,
  runSupabaseCronSmokeTest,
  runHostedDeploymentSmokeTest,
  runAllSmokeTests,
} from "../../web/src/services/smokeService";

import { runAllSmokeTests } from "../../web/src/services/smokeService";

if (import.meta.main || process.argv[1]?.endsWith("protected-smoke-tests.ts")) {
  const config = {
    googleClientId: process.env.GOOGLE_CLIENT_ID,
    googleClientSecret: process.env.GOOGLE_CLIENT_SECRET,
    deepInfraApiKey: process.env.DEEPINFRA_API_KEY,
    vapidPublicKey: process.env.VAPID_PUBLIC_KEY,
    vapidPrivateKey: process.env.VAPID_PRIVATE_KEY,
    firebaseServiceAccount: process.env.FIREBASE_SERVICE_ACCOUNT,
    supabaseUrl: process.env.SUPABASE_URL,
    supabaseServiceRoleKey: process.env.SUPABASE_SERVICE_ROLE_KEY,
    hostedAppUrl: process.env.HOSTED_APP_URL,
  };

  runAllSmokeTests(config).then((results) => {
    console.log("Smoke Test Results:", JSON.stringify(results, null, 2));
    const failed = results.filter((r) => r.status === "FAILED");
    if (failed.length > 0) {
      console.error(`❌ ${failed.length} smoke tests failed.`);
      process.exit(1);
    } else {
      console.log("✅ All smoke checks passed or safely skipped (no secrets in untrusted context).");
    }
  });
}
