import { createClient } from "@supabase/supabase-js";

export interface LeasedJob {
  job_id: string;
  lease_token: string;
  task_id: string;
  task_title: string;
  task_version: number;
  task_completed_at: string | null;
  operator_id: string;
  occurrence_key: string;
  interpreted_due_at: string;
  missed_delivery_enabled: boolean;
  platform: "web" | "android";
  endpoint: string | null;
  p256dh: string | null;
  auth: string | null;
  is_active: boolean;
  local_enabled: boolean;
  permission_state: string;
}

export interface PushPayload {
  taskId: string;
  occurrenceKey: string;
  title: string;
}

export async function processNotificationJob(
  supabase: any,
  job: LeasedJob,
  fetchFn: typeof fetch = fetch,
): Promise<{ jobId: string; result: string; statusCode?: number }> {
  const now = Date.now();
  const dueTime = new Date(job.interpreted_due_at).getTime();
  const graceWindowMs = job.missed_delivery_enabled ? 3600000 : 120000;
  const deadline = dueTime + graceWindowMs;

  // 1. Re-validate job preconditions
  if (job.task_completed_at !== null) {
    await supabase.rpc("record_notification_result", {
      p_job_id: job.job_id,
      p_lease_token: job.lease_token,
      p_result: "cancelled",
    });
    return { jobId: job.job_id, result: "cancelled" };
  }

  if (now > deadline) {
    await supabase.rpc("record_notification_result", {
      p_job_id: job.job_id,
      p_lease_token: job.lease_token,
      p_result: "expired",
    });
    return { jobId: job.job_id, result: "expired" };
  }

  if (
    !job.is_active ||
    !job.local_enabled ||
    job.permission_state !== "granted" ||
    !job.endpoint
  ) {
    await supabase.rpc("record_notification_result", {
      p_job_id: job.job_id,
      p_lease_token: job.lease_token,
      p_result: "cancelled",
    });
    return { jobId: job.job_id, result: "cancelled" };
  }

  // 2. Prepare payload - strictly task title and routing identifiers
  const payload: PushPayload = {
    taskId: job.task_id,
    occurrenceKey: job.occurrence_key,
    title: job.task_title,
  };

  const ttlSeconds = job.missed_delivery_enabled
    ? Math.max(0, Math.floor((dueTime + 3600000 - now) / 1000))
    : 0;

  try {
    const response = await fetchFn(job.endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        TTL: String(ttlSeconds),
        Urgency: "high",
        Topic: job.occurrence_key.replace(/[^a-zA-Z0-9-_]/g, "_").slice(0, 32),
      },
      body: JSON.stringify(payload),
    });

    if (response.status === 200 || response.status === 201 || response.status === 202) {
      await supabase.rpc("record_notification_result", {
        p_job_id: job.job_id,
        p_lease_token: job.lease_token,
        p_result: "delivered",
        p_status_code: response.status,
      });
      return { jobId: job.job_id, result: "delivered", statusCode: response.status };
    } else if (response.status === 404 || response.status === 410) {
      // Permanent failure / unsubscribed
      await supabase.rpc("record_notification_result", {
        p_job_id: job.job_id,
        p_lease_token: job.lease_token,
        p_result: "permanent_failure",
        p_status_code: response.status,
      });
      return { jobId: job.job_id, result: "permanent_failure", statusCode: response.status };
    } else {
      // Transient failure
      await supabase.rpc("record_notification_result", {
        p_job_id: job.job_id,
        p_lease_token: job.lease_token,
        p_result: "transient_failure",
        p_status_code: response.status,
      });
      return { jobId: job.job_id, result: "transient_failure", statusCode: response.status };
    }
  } catch (err) {
    await supabase.rpc("record_notification_result", {
      p_job_id: job.job_id,
      p_lease_token: job.lease_token,
      p_result: "transient_failure",
    });
    return { jobId: job.job_id, result: "transient_failure" };
  }
}

declare const Deno: any;

if (typeof Deno !== "undefined" && typeof Deno.serve === "function") {
  Deno.serve(async (req: Request) => {
    if (req.method === "OPTIONS") {
      return new Response("ok", {
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Headers":
            "authorization, x-client-info, apikey, content-type",
        },
      });
    }

    const authHeader = req.headers.get("Authorization");
    const cronSecret =
      Deno.env.get("CRON_SECRET") || Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");

    if (!authHeader || !authHeader.includes(cronSecret || "")) {
      return new Response(JSON.stringify({ error: "Unauthorized" }), {
        status: 401,
        headers: { "Content-Type": "application/json" },
      });
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const supabase = createClient(supabaseUrl, supabaseServiceKey);

    const { data: leasedJobs, error } = await supabase.rpc(
      "lease_due_notification_jobs",
      {
        batch_size: 50,
        lease_seconds: 60,
      },
    );

    if (error) {
      return new Response(JSON.stringify({ error: error.message }), {
        status: 500,
        headers: { "Content-Type": "application/json" },
      });
    }

    const results = await Promise.all(
      ((leasedJobs as LeasedJob[]) || []).map((job) =>
        processNotificationJob(supabase, job),
      ),
    );

    return new Response(JSON.stringify({ processed: results.length, results }), {
      headers: { "Content-Type": "application/json" },
    });
  });
}

