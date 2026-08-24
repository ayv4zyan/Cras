import { createClient } from "@supabase/supabase-js";
import {
  handleAccountLifecycleRequest,
  isOperatorPendingDeletion,
  type StorageAdminApi,
} from "../_shared/accountLifecycle.ts";

export { handleAccountLifecycleRequest, isOperatorPendingDeletion };

declare const Deno: any;

if (typeof Deno !== "undefined" && typeof Deno.serve === "function") {
  Deno.serve(async (req: Request) => {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY") || serviceKey;

    const adminClient = createClient(supabaseUrl, serviceKey);
    const anonClient = createClient(supabaseUrl, anonKey);
    const storageApi: StorageAdminApi = {
      from: (bucket: string) =>
        adminClient.storage.from(bucket) as unknown as ReturnType<
          StorageAdminApi["from"]
        >,
    };

    return await handleAccountLifecycleRequest(req, {
      anonClient,
      adminClient,
      storageApi,
      lifecycleSecret:
        Deno.env.get("ACCOUNT_LIFECYCLE_SECRET") || serviceKey,
    });
  });
}
