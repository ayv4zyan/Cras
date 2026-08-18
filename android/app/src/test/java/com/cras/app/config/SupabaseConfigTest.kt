package com.cras.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class SupabaseConfigTest {

    @Test
    fun `getPublicSupabaseConfig extracts project url and publishable key from map`() {
        val env = mapOf(
            "SUPABASE_URL" to "https://test-project.supabase.co",
            "SUPABASE_ANON_KEY" to "sb_publishable_test_token"
        )
        val config = getPublicSupabaseConfig(env)
        assertEquals("https://test-project.supabase.co", config.url)
        assertEquals("sb_publishable_test_token", config.publishableKey)
    }

    @Test
    fun `getPublicSupabaseConfig falls back to default local dev endpoints`() {
        val config = getPublicSupabaseConfig(emptyMap())
        assertNotNull(config.url)
        assertNotNull(config.publishableKey)
        assertFalse(config.url.contains("service_role"))
        assertFalse(config.publishableKey.contains("service_role"))
    }
}
