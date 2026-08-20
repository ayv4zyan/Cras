import { createClient, SupabaseClient } from "@supabase/supabase-js";

export interface PublicSupabaseConfig {
  readonly url: string;
  readonly publishableKey: string;
}

const DEFAULT_LOCAL_URL = "http://127.0.0.1:54321";
const DEFAULT_LOCAL_ANON_KEY =
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM0MTI4MDB9.CRAS_LOCAL_DEV_ANON_KEY";

export function getPublicSupabaseConfig(
  env: Record<string, string | undefined> = import.meta.env,
): PublicSupabaseConfig {
  const url =
    env.VITE_SUPABASE_URL ||
    env.SUPABASE_URL ||
    env.NEXT_PUBLIC_SUPABASE_URL ||
    DEFAULT_LOCAL_URL;
  const publishableKey =
    env.VITE_SUPABASE_ANON_KEY ||
    env.VITE_SUPABASE_PUBLISHABLE_KEY ||
    env.SUPABASE_ANON_KEY ||
    env.SUPABASE_PUBLISHABLE_KEY ||
    DEFAULT_LOCAL_ANON_KEY;

  return {
    url,
    publishableKey,
  };
}

export function createSupabaseClient(
  config: PublicSupabaseConfig = getPublicSupabaseConfig(),
): SupabaseClient {
  return createClient(config.url, config.publishableKey, {
    auth: {
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: true,
      flowType: "pkce",
    },
  });
}

export const supabase = createSupabaseClient();
