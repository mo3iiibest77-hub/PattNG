package com.v2ray.ang.ui.connect

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eligibility is about the transport the CDN can carry, never about the
 * protocol name and never about free text.
 */
class AutoConfigFilterEligibilityTest {

    private fun profile(
        type: EConfigType = EConfigType.VLESS,
        network: String? = "ws",
        security: String? = "tls",
        port: String? = "443",
        remarks: String = "config",
    ): ProfileItem = ProfileItem.create(type).apply {
        server = "origin.example.com"
        serverPort = port
        this.network = network
        this.security = security
        this.remarks = remarks
        password = "secret"
        username = "user"
        method = "aes-256-gcm"
        publicKey = "pub"
    }

    @Test
    fun `a ws tls config on a cloudflare https port is eligible`() {
        assertTrue(AutoConfigFilter.isEligible(profile()))
        assertEquals(Ineligibility.Eligible, AutoConfigFilter.ineligibility(profile()))
    }

    @Test
    fun `an xhttp tls config is eligible`() {
        assertTrue(AutoConfigFilter.isEligible(profile(network = "xhttp", port = "2053")))
    }

    @Test
    fun `a config without tls is rejected`() {
        assertFalse(AutoConfigFilter.isEligible(profile(security = null)))
        assertEquals(Ineligibility.NotTls, AutoConfigFilter.ineligibility(profile(security = "none")))
    }

    @Test
    fun `a config with another transport is rejected`() {
        assertFalse(AutoConfigFilter.isEligible(profile(network = "grpc")))
        assertEquals(
            Ineligibility.UnsupportedTransport,
            AutoConfigFilter.ineligibility(profile(network = "tcp")),
        )
    }

    @Test
    fun `a port outside the https policy is rejected`() {
        assertFalse(AutoConfigFilter.isEligible(profile(port = "8080")))
        assertEquals(Ineligibility.PortNotAllowed, AutoConfigFilter.ineligibility(profile(port = "80")))
    }

    @Test
    fun `the port is read from the port field and not from the name`() {
        // The remarks claim 443; the real port is 8080, so the config cannot
        // reach the CDN and must be rejected.
        val misleading = profile(port = "8080", remarks = "super fast 443 tls ws")
        assertFalse(AutoConfigFilter.isEligible(misleading))
        assertEquals(Ineligibility.PortNotAllowed, AutoConfigFilter.ineligibility(misleading))
    }

    @Test
    fun `a config without a server is rejected`() {
        val noServer = profile().apply { server = null }
        assertFalse(AutoConfigFilter.isEligible(noServer))
        assertEquals(Ineligibility.MissingServer, AutoConfigFilter.ineligibility(noServer))
    }

    @Test
    fun `every protocol with a cdn transport stays eligible`() {
        // Filtering is transport based: it must not single out one protocol.
        val types = listOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN)
        types.forEach { type ->
            assertTrue(type.name, AutoConfigFilter.isEligible(profile(type = type)))
        }
    }

    @Test
    fun `a policy group is not a standalone outbound`() {
        assertFalse(AutoConfigFilter.isEligible(profile(type = EConfigType.POLICYGROUP)))
    }

    @Test
    fun `filtering never mutates or drops the caller list`() {
        val all = listOf(profile(), profile(network = "grpc"), profile(type = EConfigType.POLICYGROUP))
        val eligible = AutoConfigFilter.filterEligible(all)
        assertEquals(3, all.size)
        assertEquals(1, eligible.size)
        assertEquals("config", all[1].remarks)
    }
}
