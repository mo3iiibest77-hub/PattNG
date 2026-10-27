package com.v2ray.ang.ui.connect

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.qsn.domain.EdgeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for TLS/transport preservation when an edge is applied.
 *
 * Since the edge moved into its own field, applying it may only write
 * [ProfileItem.qsEdgeIp]. Everything else — the origin address, SNI, Host,
 * Path, network, security, auth params, cipherSuites and finalMask — must come
 * out of the operation unchanged.
 *
 * These tests call the real applier, not a copy of it.
 */
class CloudflareScannerPreservationTest {

    private fun applyTo(original: ProfileItem, bestIp: String): ProfileItem {
        val target = EdgeApplication.Target("guid", original)
        EdgeApplication.apply(
            targets = listOf(target),
            ip = bestIp,
        )
        return target.profile
    }

    @Test
    fun `the edge is written and the origin server is preserved`() {
        val original = fullProfile().apply { server = "old.example.com" }
        val result = applyTo(original, "104.16.123.45")
        assertEquals("104.16.123.45", result.qsEdgeIp)
        assertEquals("old.example.com", result.server)
    }

    @Test
    fun `SNI is preserved`() {
        val result = applyTo(fullProfile().apply { sni = "sni.example.com" }, "1.2.3.4")
        assertEquals("sni.example.com", result.sni)
    }

    @Test
    fun `HTTP Host is preserved`() {
        val result = applyTo(fullProfile().apply { host = "host.example.com" }, "1.2.3.4")
        assertEquals("host.example.com", result.host)
    }

    @Test
    fun `WebSocket Path is preserved`() {
        val result = applyTo(fullProfile().apply { path = "/custom-ws-path" }, "1.2.3.4")
        assertEquals("/custom-ws-path", result.path)
    }

    @Test
    fun `XHTTP mode is preserved`() {
        val result = applyTo(fullProfile().apply { xhttpMode = "auto" }, "1.2.3.4")
        assertEquals("auto", result.xhttpMode)
    }

    @Test
    fun `XHTTP extra is preserved`() {
        val result = applyTo(fullProfile().apply { xhttpExtra = "{\"key\":\"value\"}" }, "1.2.3.4")
        assertEquals("{\"key\":\"value\"}", result.xhttpExtra)
    }

    @Test
    fun `Network is preserved`() {
        val result = applyTo(fullProfile().apply { network = "xhttp" }, "1.2.3.4")
        assertEquals("xhttp", result.network)
    }

    @Test
    fun `Security is preserved`() {
        val result = applyTo(fullProfile().apply { security = "tls" }, "1.2.3.4")
        assertEquals("tls", result.security)
    }

    @Test
    fun `Server port is preserved`() {
        val result = applyTo(fullProfile().apply { serverPort = "2053" }, "1.2.3.4")
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
        val result = applyTo(original, "1.2.3.4")
        assertEquals("uuid-secret-123", result.password)
        assertEquals("user1", result.username)
        assertEquals("aes-256-gcm", result.method)
        assertEquals("wg-pubkey", result.publicKey)
    }

    @Test
    fun `ALPN is preserved`() {
        val result = applyTo(fullProfile().apply { alpn = "h2,http/1.1" }, "1.2.3.4")
        assertEquals("h2,http/1.1", result.alpn)
    }

    @Test
    fun `fingerPrint is not touched by the applier`() {
        val result = applyTo(fullProfile().apply { fingerPrint = "chrome" }, "1.2.3.4")
        assertEquals("chrome", result.fingerPrint)
    }

    @Test
    fun `existing finalMask is NOT overwritten`() {
        val result = applyTo(fullProfile().apply { finalMask = "{\"custom\":true}" }, "1.2.3.4")
        assertEquals("{\"custom\":true}", result.finalMask)
    }

    @Test
    fun `existing cipherSuites is NOT overwritten`() {
        val result = applyTo(fullProfile().apply { cipherSuites = "CUSTOM_CIPHERS" }, "1.2.3.4")
        assertEquals("CUSTOM_CIPHERS", result.cipherSuites)
    }

    @Test
    fun `configType is preserved`() {
        val result = applyTo(fullProfile(EConfigType.VMESS), "1.2.3.4")
        assertEquals(EConfigType.VMESS, result.configType)
    }

    @Test
    fun `insecure flag is preserved`() {
        val result = applyTo(fullProfile().apply { insecure = true }, "1.2.3.4")
        assertEquals(true, result.insecure)
    }

    @Test
    fun `pinned CA256 is preserved`() {
        val result = applyTo(fullProfile().apply { pinnedCA256 = "sha256:abc123" }, "1.2.3.4")
        assertEquals("sha256:abc123", result.pinnedCA256)
    }

    @Test
    fun `an unusable address leaves the profile alone`() {
        val result = applyTo(fullProfile(), "not-an-address")
        assertNull(result.qsEdgeIp)
        assertEquals("original.example.com", result.server)
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
