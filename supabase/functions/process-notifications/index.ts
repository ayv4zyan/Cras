import { createClient } from "@supabase/supabase-js";
import {
  processNotificationJob,
  type LeasedJob,
  type WebPushOptions,
} from "../_shared/webPush.ts";

export {
  base64UrlToUint8Array,
  uint8ArrayToBase64Url,
  rawP256PrivateKeyToPkcs8,
  deriveTopic,
  encryptWebPushPayload,
  generateVapidHeader,
  isPermanentFailureStatus,
  recordResult,
  processNotificationJob,
  type LeasedJob,
  type PushPayload,
  type WebPushOptions,
} from "../_shared/webPush.ts";

declare const Deno: any;

if (typeof Deno !== "undefined" && typeof Deno.serve === "function") {
  Deno.serve(async (req: Request) => {
    const authHeader = req.headers.get("Authorization");
    const cronSecret =
      Deno.env.get("CRON_SECRET") || Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");

    if (!cronSecret || authHeader !== `Bearer ${cronSecret}`) {
      return new Response(JSON.stringify({ error: "Unauthorized" }), {
        status: 401,
        headers: { "Content-Type": "application/json" },
      });
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const supabase = createClient(supabaseUrl, supabaseServiceKey);

    const { data: leasedJobs, error } = await supabase
      .schema("api")
      .rpc("lease_due_notification_jobs", {
        batch_size: 50,
        lease_seconds: 60,
      });

    if (error) {
      return new Response(JSON.stringify({ error: error.message }), {
        status: 500,
        headers: { "Content-Type": "application/json" },
      });
    }

    const vapidOptions: WebPushOptions = {
      vapidPublicKey: Deno.env.get("VAPID_PUBLIC_KEY") || undefined,
      vapidPrivateKey: Deno.env.get("VAPID_PRIVATE_KEY") || undefined,
      vapidSubject: Deno.env.get("VAPID_SUBJECT") || undefined,
    };

    const results = await Promise.all(
      ((leasedJobs as LeasedJob[]) || []).map(async (job) => {
        try {
          return await processNotificationJob(
            supabase,
            job,
            fetch,
            vapidOptions,
          );
        } catch (err) {
          console.error(
            `Failed processing notification job ${job.job_id}:`,
            err,
          );
          return {
            jobId: job.job_id,
            result: "error",
            error: err instanceof Error ? err.message : String(err),
          };
        }
      }),
    );

    return new Response(
      JSON.stringify({ processed: results.length, results }),
      {
        headers: { "Content-Type": "application/json" },
      },
    );
  });
}
