package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.connect.AutoConfigFilter
import com.v2ray.ang.ui.connect.Ineligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplicate detection for manual imports, and the shared eligibility rule that
 * the manual import, the Configurations screen and the automatic flow all use.
 *
 * The eligibility cases moved here from the removed `ManualConfigRules`
 * copy of the rule: one rule, one set of tests, so a configuration accepted by
 * one entry point can never be refused by another.
 */
class ManualConfigRulesTest {

    private fun profile(type: EConfigType = EConfigType.VLESS) =
        ProfileItem.create(type).apply {
            server = "edge.example.com"
            serverPort = "443"
            network = "ws"
            security = "tls"
            sni = "edge.example.com"
            host = "edge.example.com"
            path = "/ws"
            password = "0f8fad5b-d9cb-469f-a165-70867728950e"
        }

    private fun ineligibilityOf(profile: ProfileItem) = AutoConfigFilter.ineligibility(profile)

    // ── Shared eligibility: accepted combinations ──

    @Test
    fun `ws over tls on 443 is eligible`() {
        assertEquals(Ineligibility.Eligible, ineligibilityOf(profile()))
    }

    @Test
    fun `xhttp over tls on 2053 is eligible`() {
        val p = profile().apply {
            network = "xhttp"
            serverPort = "2053"
        }
        assertEquals(Ineligibility.Eligible, ineligibilityOf(p))
    }

    @Test
    fun `every approved cloudflare https port is eligible`() {
        AutoConfigFilter.HTTPS_PORTS.forEach { port ->
            val p = profile().apply { serverPort = port }
            assertEquals("port " + port, Ineligibility.Eligible, ineligibilityOf(p))
        }
    }

    @Test
    fun `a vmess profile with the same transport is eligible too`() {
        assertEquals(Ineligibility.Eligible, ineligibilityOf(profile(EConfigType.VMESS)))
    }

    // ── Shared eligibility: rejected combinations ──

    @Test
    fun `tcp is an unsupported transport`() {
        val p = profile().apply { network = "tcp" }
        assertEquals(Ineligibility.UnsupportedTransport, ineligibilityOf(p))
    }

    @Test
    fun `grpc is an unsupported transport`() {
        val p = profile().apply { network = "grpc" }
        assertEquals(Ineligibility.UnsupportedTransport, ineligibilityOf(p))
    }

    @Test
    fun `httpupgrade is an unsupported transport`() {
        val p = profile().apply { network = "httpupgrade" }
        assertEquals(Ineligibility.UnsupportedTransport, ineligibilityOf(p))
    }

    @Test
    fun `h3 is not an accepted transport because the core cannot build it`() {
        // There is no HTTP/3 transport in NetworkType and no h3 branch in
        // CoreOutboundBuilder, so advertising it would only produce a broken
        // connection. It must be rejected, not silently rewritten.
        val p = profile().apply { network = "h3" }
        assertEquals(Ineligibility.UnsupportedTransport, ineligibilityOf(p))
    }

    @Test
    fun `ws without tls is rejected`() {
        assertEquals(Ineligibility.NotTls, ineligibilityOf(profile().apply { security = "none" }))
        assertEquals(Ineligibility.NotTls, ineligibilityOf(profile().apply { security = null }))
    }

    @Test
    fun `an http only port is rejected`() {
        AutoConfigFilter.HTTP_ONLY_PORTS.forEach { port ->
            val p = profile().apply { serverPort = port }
            assertEquals("port " + port, Ineligibility.PortNotAllowed, ineligibilityOf(p))
        }
    }

    @Test
    fun `port 80 and a missing port are rejected`() {
        assertEquals(Ineligibility.PortNotAllowed, ineligibilityOf(profile().apply { serverPort = "80" }))
        assertEquals(Ineligibility.PortNotAllowed, ineligibilityOf(profile().apply { serverPort = null }))
    }

    @Test
    fun `a missing server is rejected`() {
        assertEquals(Ineligibility.MissingServer, ineligibilityOf(profile().apply { server = "" }))
        assertEquals(Ineligibility.MissingServer, ineligibilityOf(profile().apply { server = null }))
    }

    @Test
    fun `a missing credential is rejected`() {
        assertEquals(
            Ineligibility.MissingCredential,
            ineligibilityOf(profile().apply { password = null }),
        )
    }

    @Test
    fun `a composite protocol is rejected`() {
        assertEquals(
            Ineligibility.UnsupportedProtocol,
            ineligibilityOf(profile(EConfigType.POLICYGROUP)),
        )
    }

    @Test
    fun `transport and security are compared exactly as the core reads them`() {
        // NetworkType.fromString and the core's security comparison are
        // case-sensitive; accepting "WS"/"TLS" here would build a plaintext
        // TCP outbound and then fail at connect time.
        assertEquals(
            Ineligibility.UnsupportedTransport,
            ineligibilityOf(profile().apply { network = "WS" }),
        )
        assertEquals(
            Ineligibility.NotTls,
            ineligibilityOf(profile().apply { security = "TLS" }),
        )
    }

    @Test
    fun `the rule never rewrites the configuration it judges`() {
        val p = profile().apply { network = "tcp" }
        val before = ManualConfigRules.keyOf(p)
        ineligibilityOf(p)
        assertEquals(before, ManualConfigRules.keyOf(p))
        assertEquals("tcp", p.network)
        assertEquals("tls", p.security)
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
}
