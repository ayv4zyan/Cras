package com.cras.app.config

data class PublicSupabaseConfig(
    val url: String,
    val publishableKey: String
)

private const val DEFAULT_LOCAL_URL = "http://10.0.2.2:54321"
private const val DEFAULT_LOCAL_ANON_KEY =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM0MTI4MDB9.CRAS_LOCAL_DEV_ANON_KEY"

fun getPublicSupabaseConfig(
    env: Map<String, String?> = emptyMap()
): PublicSupabaseConfig {
    val url = env["SUPABASE_URL"]
        ?: env["VITE_SUPABASE_URL"]
        ?: DEFAULT_LOCAL_URL
    val publishableKey = env["SUPABASE_ANON_KEY"]
        ?: env["SUPABASE_PUBLISHABLE_KEY"]
        ?: env["VITE_SUPABASE_ANON_KEY"]
        ?: DEFAULT_LOCAL_ANON_KEY

    return PublicSupabaseConfig(
        url = url,
        publishableKey = publishableKey
    )
}
