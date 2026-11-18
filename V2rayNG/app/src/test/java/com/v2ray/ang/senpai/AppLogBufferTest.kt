package com.v2ray.ang.senpai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogBufferTest {

    @Test
    fun redact_stripsAllSecretsAndProxyLinksPreservingDiagnostics() {
        val sample = "Auth failed for NP-B4LZ-8F6F-KAK7 token=nps_abcXYZ123456 " +
            "Authorization: Bearer super_secret_tok " +
            "vless://550e8400-e29b-41d4-a716-446655440000@104.16.1.2:443?sni=x.com#srv " +
            "uuid 550e8400-e29b-41d4-a716-446655440000 on edge 104.16.1.2 (142 ms)"

        val redacted = AppLogBuffer.redact(sample)

        assertFalse(redacted.contains("NP-B4LZ-8F6F-KAK7"))
        assertFalse(redacted.contains("nps_abcXYZ123456"))
        assertFalse(redacted.contains("super_secret_tok"))
        assertFalse(redacted.contains("550e8400-e29b-41d4-a716-446655440000"))
        assertTrue(redacted.contains("[LICENSE_REDACTED]"))
        assertTrue(redacted.contains("[TOKEN_REDACTED]"))
        assertTrue(redacted.contains("vless://[CONFIG_REDACTED]"))
        assertTrue(redacted.contains("104.16.1.2"))
        assertTrue(redacted.contains("142 ms"))
    }

    @Test
    fun recordAndExport_alwaysStoreRedactedEntries() {
        AppLogBuffer.clear()
        AppLogBuffer.info("License", "Redeeming NP-ABCD-1234-EFGH via https://x.test/by-sub/secret99")
        val exported = AppLogBuffer.formatExportText(AppLogBuffer.snapshot())
        assertFalse(exported.contains("NP-ABCD-1234-EFGH"))
        assertFalse(exported.contains("secret99"))
        assertTrue(exported.contains("[LICENSE_REDACTED]"))
        assertEquals(1, AppLogBuffer.snapshot().size)
        AppLogBuffer.clear()
    }
}
