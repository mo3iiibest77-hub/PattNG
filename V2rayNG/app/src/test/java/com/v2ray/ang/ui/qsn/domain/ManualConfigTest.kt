package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the eligibility rules and duplicate detection of manual imports. */
class ManualConfigRulesTest {

    private fun profile() = ProfileItem.create(EConfigType.VLESS).apply {
        server = "edge.example.com"
        serverPort = "443"
        network = "ws"
        security = "tls"
        sni = "edge.example.com"
        host = "edge.example.com"
        path = "/ws"
        password = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }

    @Test
    fun `ws over tls on 443 is eligible`() {
        assertEquals(Eligibility.Eligible, eligibility())
        assertTrue(ManualConfigRules.isEligible("ws", "tls", 443, "edge.example.com"))
    }

    @Test
    fun `xhttp over tls on 2053 is eligible`() {
        assertTrue(ManualConfigRules.isEligible("xhttp", "tls", 2053, "edge.example.com"))
    }

    @Test
    fun `every cloudflare https port is eligible`() {
        ManualConfigRules.HTTPS_PORTS.forEach { port ->
            assertTrue("port " + port, ManualConfigRules.isEligible("ws", "tls", port, "a.example.com"))
        }
    }

    @Test
    fun `tcp is an unsupported transport`() {
        assertEquals(
            Eligibility.UnsupportedTransport,
            ManualConfigRules.eligibility("tcp", "tls", 443, "edge.example.com"),
        )
    }

    @Test
    fun `grpc is an unsupported transport`() {
        assertEquals(
            Eligibility.UnsupportedTransport,
            ManualConfigRules.eligibility("grpc", "tls", 443, "edge.example.com"),
        )
    }

    @Test
    fun `ws without tls is not eligible`() {
        assertEquals(
            Eligibility.NotTls,
            ManualConfigRules.eligibility("ws", null, 443, "edge.example.com"),
        )
        assertEquals(
            Eligibility.NotTls,
            ManualConfigRules.eligibility("ws", "none", 443, "edge.example.com"),
        )
    }

    @Test
    fun `port 80 is an unsupported port`() {
        assertEquals(
            Eligibility.UnsupportedPort,
            ManualConfigRules.eligibility("ws", "tls", 80, "edge.example.com"),
        )
    }

    @Test
    fun `a missing server is reported before anything else`() {
        assertEquals(
            Eligibility.MissingServer,
            ManualConfigRules.eligibility("tcp", null, 80, null),
        )
        assertEquals(
            Eligibility.MissingServer,
            ManualConfigRules.eligibility("ws", "tls", 443, "   "),
        )
    }

    @Test
    fun `the check is case insensitive`() {
        assertTrue(ManualConfigRules.isEligible("WS", "TLS", 443, "edge.example.com"))
    }

    // ── Duplicate detection ──

    @Test
    fun `the content key is stable for the same profile`() {
        assertEquals(ManualConfigRules.keyOf(profile()), ManualConfigRules.keyOf(profile()))
    }

    @Test
    fun `the content key ignores remarks and timestamps`() {
        val renamed = profile().apply {
            remarks = "a different name"
            addedTime = 1L
            subscriptionId = "other"
        }
        assertEquals(ManualConfigRules.keyOf(profile()), ManualConfigRules.keyOf(renamed))
    }

    @Test
    fun `the content key changes when the credential changes`() {
        val other = profile().apply { password = "another-uuid" }
        assertNotEquals(ManualConfigRules.keyOf(profile()), ManualConfigRules.keyOf(other))
    }

    @Test
    fun `the content key changes when the server changes`() {
        val other = profile().apply { server = "other.example.com" }
        assertNotEquals(ManualConfigRules.keyOf(profile()), ManualConfigRules.keyOf(other))
    }

    @Test
    fun `the content key changes when the port changes`() {
        val other = profile().apply { serverPort = "8443" }
        assertNotEquals(ManualConfigRules.keyOf(profile()), ManualConfigRules.keyOf(other))
    }

    @Test
    fun `the content key is a sha256 digest`() {
        val key = ManualConfigRules.keyOf(profile())
        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
    }

    @Test
    fun `the content key never contains the credential`() {
        val credential = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertFalse(ManualConfigRules.keyOf(profile()).contains(credential))
    }

    private fun eligibility(): Eligibility = ManualConfigRules.eligibility(
        network = "ws",
        security = "tls",
        port = 443,
        server = "edge.example.com",
    )
}
