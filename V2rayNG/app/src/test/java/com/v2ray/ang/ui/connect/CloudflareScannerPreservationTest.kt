package com.v2ray.ang.ui.connect

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for TLS/transport preservation when applying a best IP.
 *
 * [CloudflareScanner.applyBestIp] must only modify:
 *  - [ProfileItem.server] (the IP being applied)
 *  - [ProfileItem.fingerPrint] (only if blank)
 *  - [ProfileItem.finalMask] (only if blank)
 *  - [ProfileItem.cipherSuites] (only if blank)
 *
 * All other fields — SNI, Host, Path, network, security, auth params,
 * xhttpMode, xhttpExtra — must be preserved.
 *
 * These tests simulate the applyBestIp mutation logic without needing
 * MmkvManager (which requires Android/Robolectric). They verify the
 * *contract*: which fields may change and which must not.
 */
class CloudflareScannerPreservationTest {

    /**
     * Simulates the mutation that applyBestIp performs on a profile.
     * This mirrors the exact logic in CloudflareScanner.applyBestIp:
     *   profile.server = bestIp
     *   if (fingerPrint.isBlank()) fingerPrint = "unsafe"
     *   if (finalMask.isBlank()) finalMask = PRODUCTION_FINALMASK
     *   if (cipherSuites.isBlank()) cipherSuites = PRODUCTION_CIPHERSUITES
     */
    private fun simulateApplyBestIp(
        original: ProfileItem,
        bestIp: String,
        productionFinalMask: String = "{\"tcp\":[]}",
        productionCipherSuites: String = "TLS_AES_256_GCM_SHA384",
    ): ProfileItem {
        return original.copy(
            server = bestIp,
        ).apply {
            if (fingerPrint.isNullOrBlank()) fingerPrint = "unsafe"
            if (finalMask.isNullOrBlank()) finalMask = productionFinalMask
            if (cipherSuites.isNullOrBlank()) cipherSuites = productionCipherSuites
        }
    }

    @Test
    fun `server is replaced with best IP`() {
        val original = fullProfile(server = "old.example.com")
        val result = simulateApplyBestIp(original, "104.16.123.45")
        assertEquals("104.16.123.45", result.server)
    }

    @Test
    fun `SNI is preserved`() {
        val original = fullProfile().apply { sni = "sni.example.com" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("sni.example.com", result.sni)
    }

    @Test
    fun `HTTP Host is preserved`() {
        val original = fullProfile().apply { host = "host.example.com" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("host.example.com", result.host)
    }

    @Test
    fun `WebSocket Path is preserved`() {
        val original = fullProfile().apply { path = "/custom-ws-path" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("/custom-ws-path", result.path)
    }

    @Test
    fun `XHTTP mode is preserved`() {
        val original = fullProfile().apply { xhttpMode = "auto" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("auto", result.xhttpMode)
    }

    @Test
    fun `XHTTP extra is preserved`() {
        val original = fullProfile().apply { xhttpExtra = "{\"key\":\"value\"}" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("{\"key\":\"value\"}", result.xhttpExtra)
    }

    @Test
    fun `Network is preserved`() {
        val original = fullProfile().apply { network = "xhttp" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("xhttp", result.network)
    }

    @Test
    fun `Security is preserved`() {
        val original = fullProfile().apply { security = "tls" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("tls", result.security)
    }

    @Test
    fun `Server port is preserved`() {
        val original = fullProfile().apply { serverPort = "2053" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("2053", result.serverPort)
    }

    @Test
    fun `Auth params are preserved`() {
        val original = fullProfile().apply {
            password = "uuid-secret-123"
            username = "user1"
            method = "aes-256-gcm"
            publicKey = "wg-pubkey"
        }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("uuid-secret-123", result.password)
        assertEquals("user1", result.username)
        assertEquals("aes-256-gcm", result.method)
        assertEquals("wg-pubkey", result.publicKey)
    }

    @Test
    fun `ALPN is preserved`() {
        val original = fullProfile().apply { alpn = "h2,http/1.1" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("h2,http/1.1", result.alpn)
    }

    @Test
    fun `existing fingerPrint is NOT overwritten`() {
        val original = fullProfile().apply { fingerPrint = "chrome" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("chrome", result.fingerPrint)
    }

    @Test
    fun `blank fingerPrint is filled with unsafe`() {
        val original = fullProfile().apply { fingerPrint = null }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("unsafe", result.fingerPrint)
    }

    @Test
    fun `existing finalMask is NOT overwritten`() {
        val original = fullProfile().apply { finalMask = "{\"custom\":true}" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("{\"custom\":true}", result.finalMask)
    }

    @Test
    fun `existing cipherSuites is NOT overwritten`() {
        val original = fullProfile().apply { cipherSuites = "CUSTOM_CIPHERS" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("CUSTOM_CIPHERS", result.cipherSuites)
    }

    @Test
    fun `configType is preserved`() {
        val original = fullProfile(EConfigType.VMESS)
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals(EConfigType.VMESS, result.configType)
    }

    @Test
    fun `insecure flag is preserved`() {
        val original = fullProfile().apply { insecure = true }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals(true, result.insecure)
    }

    @Test
    fun `pinned CA256 is preserved`() {
        val original = fullProfile().apply { pinnedCA256 = "sha256:abc123" }
        val result = simulateApplyBestIp(original, "1.2.3.4")
        assertEquals("sha256:abc123", result.pinnedCA256)
    }

    /** Creates a VLESS profile with all transport/TLS fields populated. */
    private fun fullProfile(type: EConfigType = EConfigType.VLESS) =
        ProfileItem.create(type).apply {
            server = "original.example.com"
            serverPort = "443"
            network = "ws"
            security = "tls"
            password = "uuid-1234"
            sni = "sni.example.com"
            host = "host.example.com"
            path = "/ws"
            alpn = "h2,http/1.1"
            fingerPrint = "chrome"
            finalMask = "{\"existing\":true}"
            cipherSuites = "EXISTING_CIPHERS"
            insecure = false
            xhttpMode = "packet-up"
            xhttpExtra = "{}"
        }
}
