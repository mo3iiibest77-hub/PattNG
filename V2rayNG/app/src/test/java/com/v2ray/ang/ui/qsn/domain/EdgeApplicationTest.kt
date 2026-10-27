package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.connect.AutoConfigFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edge-selection contract.
 *
 * One chosen Cloudflare IP must reach every eligible config of the selected
 * subscription, must never reach another subscription, and must never damage
 * the stored connection identity.
 */
class EdgeApplicationTest {

    private fun profile(
        subscriptionId: String,
        eligible: Boolean = true,
        remarks: String = "config",
    ): ProfileItem = ProfileItem.create(EConfigType.VLESS).apply {
        this.subscriptionId = subscriptionId
        this.remarks = remarks
        server = "origin.example.com"
        serverPort = if (eligible) "443" else "8080"
        network = "ws"
        security = "tls"
        password = "uuid-1234"
        sni = "sni.example.com"
        host = "host.example.com"
        path = "/ws"
        cipherSuites = "CUSTOM_CIPHERS"
        finalMask = "{\"custom\":true}"
    }

    private fun target(guid: String, profile: ProfileItem) = EdgeApplication.Target(guid, profile)

    private fun inScope(subscriptionId: String, targets: List<EdgeApplication.Target>) =
        EdgeApplication.targets(
            scope = EdgeApplication.Scope.Subscription(subscriptionId),
            candidates = targets,
            isEligible = AutoConfigFilter::isEligible,
        )

    @Test
    fun `the edge reaches every eligible config of the subscription`() {
        val targets = listOf(
            target("a", profile("sub-1")),
            target("b", profile("sub-1")),
            target("c", profile("sub-1")),
        )
        val changed = EdgeApplication.apply(inScope("sub-1", targets), "104.16.1.1")
        assertEquals(3, changed.size)
        assertTrue(targets.all { it.profile.qsEdgeIp == "104.16.1.1" })
    }

    @Test
    fun `configs of another subscription are untouched`() {
        val mine = target("a", profile("sub-1"))
        val other = target("b", profile("sub-2"))
        EdgeApplication.apply(inScope("sub-1", listOf(mine, other)), "104.16.1.1")
        assertEquals("104.16.1.1", mine.profile.qsEdgeIp)
        assertNull(other.profile.qsEdgeIp)
    }

    @Test
    fun `an ineligible config is never modified`() {
        val eligible = target("a", profile("sub-1"))
        val ineligible = target("b", profile("sub-1", eligible = false))
        EdgeApplication.apply(inScope("sub-1", listOf(eligible, ineligible)), "104.16.1.1")
        assertNull(ineligible.profile.qsEdgeIp)
    }

    @Test
    fun `the origin server is preserved so details can show it`() {
        val only = target("a", profile("sub-1"))
        EdgeApplication.apply(inScope("sub-1", listOf(only)), "104.16.1.1")
        assertEquals("origin.example.com", only.profile.server)
        assertEquals("104.16.1.1", only.profile.qsEdgeIp)
    }

    @Test
    fun `tls sni host path cs and finalMask survive`() {
        val only = target("a", profile("sub-1"))
        EdgeApplication.apply(inScope("sub-1", listOf(only)), "104.16.1.1")
        val p = only.profile
        assertEquals("tls", p.security)
        assertEquals("sni.example.com", p.sni)
        assertEquals("host.example.com", p.host)
        assertEquals("/ws", p.path)
        assertEquals("CUSTOM_CIPHERS", p.cipherSuites)
        assertEquals("{\"custom\":true}", p.finalMask)
        assertEquals("ws", p.network)
        assertEquals("443", p.serverPort)
    }

    @Test
    fun `identity name and protocol survive`() {
        val only = target("a", profile("sub-1", remarks = "my config"))
        EdgeApplication.apply(inScope("sub-1", listOf(only)), "104.16.1.1")
        assertEquals("my config", only.profile.remarks)
        assertEquals(EConfigType.VLESS, only.profile.configType)
        assertEquals("uuid-1234", only.profile.password)
        assertEquals("sub-1", only.profile.subscriptionId)
    }

    @Test
    fun `a single scope touches only the pinned config`() {
        val pinned = target("a", profile("sub-1"))
        val sibling = target("b", profile("sub-1"))
        val scope = EdgeApplication.targets(
            scope = EdgeApplication.Scope.Single("a"),
            candidates = listOf(pinned, sibling),
            isEligible = AutoConfigFilter::isEligible,
        )
        EdgeApplication.apply(scope, "104.16.1.1")
        assertEquals("104.16.1.1", pinned.profile.qsEdgeIp)
        assertNull(sibling.profile.qsEdgeIp)
    }

    @Test
    fun `an invalid address is rejected instead of written`() {
        val only = target("a", profile("sub-1"))
        val changed = EdgeApplication.apply(inScope("sub-1", listOf(only)), "not-an-ip")
        assertTrue(changed.isEmpty())
        assertNull(only.profile.qsEdgeIp)
    }

    @Test
    fun `re-applying the same address reports no change`() {
        val only = target("a", profile("sub-1"))
        EdgeApplication.apply(inScope("sub-1", listOf(only)), "104.16.1.1")
        val again = EdgeApplication.apply(inScope("sub-1", listOf(only)), "104.16.1.1")
        assertTrue(again.isEmpty())
        assertEquals("104.16.1.1", only.profile.qsEdgeIp)
    }

    @Test
    fun `an empty scope applies nothing`() {
        val changed = EdgeApplication.apply(inScope("sub-1", emptyList()), "104.16.1.1")
        assertTrue(changed.isEmpty())
        assertFalse(inScope("sub-1", emptyList()).isNotEmpty())
    }
}
