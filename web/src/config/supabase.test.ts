import { describe, it, expect } from "vitest";
import { getPublicSupabaseConfig, createSupabaseClient } from "./supabase";

describe("Supabase Public Configuration & Client Seam", () => {
  it("extracts public Supabase URL and publishable key from VITE_ variables", () => {
    const config = getPublicSupabaseConfig({
      VITE_SUPABASE_URL: "https://example-project.supabase.co",
      VITE_SUPABASE_ANON_KEY: "sb_publishable_test_key_123",
    });

    expect(config.url).toBe("https://example-project.supabase.co");
    expect(config.publishableKey).toBe("sb_publishable_test_key_123");
    // Ensure no service_role key or extra credentials exist
    expect(Object.keys(config)).toEqual(["url", "publishableKey"]);
  });

  it("supports SUPABASE_ prefixed keys as fallback", () => {
    const config = getPublicSupabaseConfig({
      SUPABASE_URL: "https://legacy-project.supabase.co",
      SUPABASE_ANON_KEY: "sb_legacy_key_123",
    });

    expect(config.url).toBe("https://legacy-project.supabase.co");
    expect(config.publishableKey).toBe("sb_legacy_key_123");
  });

  it("falls back to default local Supabase credentials when env is empty", () => {
    const config = getPublicSupabaseConfig({});
    expect(config.url).toBe("http://127.0.0.1:54321");
    expect(config.publishableKey).toBeTruthy();
  });

  it("creates a Supabase client with public config and browser session persistence", () => {
    const client = createSupabaseClient({
      url: "https://example-project.supabase.co",
      publishableKey: "sb_publishable_test_key_123",
    });

    expect(client).toBeDefined();
    expect(client.auth).toBeDefined();
  });
});
