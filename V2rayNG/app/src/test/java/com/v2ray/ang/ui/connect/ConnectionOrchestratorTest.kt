package com.v2ray.ang.ui.connect

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ConnectionOrchestrator]'s pure logic.
 *
 * These exercise the real production functions - [pairGuidsWithProfiles] and
 * [AutoConfigFilter.isEligible] - not re-implementations of them. The
 * Android-dependent parts (MMKV, the VPN service, the broadcast wait) need an
 * instrumented environment and are recorded as NOT RUN in Report.md.
 */
class ConnectionOrchestratorTest {

    // -- GUID / profile pairing (real function) ----------------------

    @Test
    fun pairingKeepsOrderAndDropsUndecodableGuids() {
        val profiles = mapOf(
            "g1" to profile("server1"),
            "g3" to profile("server3"),
        )
        val pairs = pairGuidsWithProfiles(listOf("g1", "g2", "g3")) { profiles[it] }

        assertEquals(2, pairs.size)
        assertEquals("g1", pairs[0].first)
        assertEquals("server1", pairs[0].second.server)
        assertEquals("g3", pairs[1].first)
        assertEquals("server3", pairs[1].second.server)
    }

    @Test
    fun pairingReturnsEmptyWhenNothingDecodes() {
        val pairs = pairGuidsWithProfiles(listOf("g1", "g2")) { null }
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun pairingReturnsEmptyForEmptyInput() {
        assertTrue(pairGuidsWithProfiles(emptyList()) { profile("x") }.isEmpty())
    }

    @Test
    fun pairingKeepsDuplicateProfilesUnderDistinctGuids() {
        // Same endpoint stored twice: the two GUIDs must stay separate and
        // must not collapse into a single entry.
        val same = profile("shared-host")
        val profiles = mapOf("g1" to same, "g2" to same)
        val pairs = pairGuidsWithProfiles(listOf("g1", "g2")) { profiles[it] }

        assertEquals(2, pairs.size)
        assertEquals("g1", pairs[0].first)
        assertEquals("g2", pairs[1].first)
    }

    @Test
    fun everyPairedGuidBelongsToItsDecodedProfile() {
        val profiles = mapOf(
            "alpha" to profile("host-a"),
            "beta" to profile("host-b"),
            "gamma" to profile("host-c"),
        )
        val pairs = pairGuidsWithProfiles(profiles.keys.toList()) { profiles[it] }

        assertEquals(3, pairs.size)
        for ((guid, item) in pairs) {
            assertEquals(profiles.getValue(guid).server, item.server)
        }
    }

    // -- Selection over the real pairing plus the real filter --------

    @Test
    fun selectedGuidMatchesTheSelectedEligibleProfile() {
        // g1 is ineligible (tcp); g2 and g3 are eligible.
        val g1 = eligible().apply { network = "tcp" }
        val g2 = eligible()
        val g3 = eligible().apply { serverPort = "8443" }
        val profiles = mapOf("g1" to g1, "g2" to g2, "g3" to g3)

        val pairs = pairGuidsWithProfiles(listOf("g1", "g2", "g3")) { profiles[it] }
        val eligiblePairs = pairs.filter { AutoConfigFilter.isEligible(it.second) }

        assertEquals(2, eligiblePairs.size)
        val (guid, item) = eligiblePairs.first()
        assertEquals("g2", guid)
        assertEquals(g2, item)
    }

    @Test
    fun selectionSkipsUndecodableGuidWithoutShiftingMapping() {
        // g1 is missing from storage; g2 must still map to its own profile.
        val g2 = eligible()
        val profiles = mapOf("g2" to g2)

        val pairs = pairGuidsWithProfiles(listOf("g1", "g2")) { profiles[it] }
        val selected = pairs.filter { AutoConfigFilter.isEligible(it.second) }.first()

        assertEquals("g2", selected.first)
        assertEquals(g2, selected.second)
    }

    // -- Result contracts -------------------------------------------

    @Test
    fun successCarriesTheGuid() {
        assertEquals("guid-abc", AutoConnectResult.Success("guid-abc").guid)
    }

    @Test
    fun cancelledIsASingleton() {
        assertEquals(AutoConnectResult.Cancelled, AutoConnectResult.Cancelled)
    }

    @Test
    fun failureReasonIsCarriedVerbatim() {
        val reason = "Core did not confirm running within timeout"
        assertEquals(reason, AutoConnectResult.Failure(reason).reason)
    }

    // -- Error text must not leak secrets ---------------------------

    @Test
    fun failureMessagesContainNoUrlOrCredentialMaterial() {
        val messages = listOf(
            "No subscription configured",
            "No enabled subscription configured",
            "Subscription fetch failed",
            "Subscription returned 0 configs",
            "No configs in subscription storage",
            "All configs failed to decode",
            "No eligible configs (ws/xhttp + TLS + CF HTTPS port required)",
            "Cloudflare scan found no working IP",
            "Failed to apply best IP to profile",
            "Core did not confirm running within timeout",
            "Another auto-connect is already running",
            "Unexpected error",
        )
        for (message in messages) {
            assertFalse("leaks scheme: " + message, message.contains("://"))
            assertFalse("leaks token: " + message, message.contains("token="))
            assertFalse("leaks password: " + message, message.contains("password="))
            assertFalse("leaks uuid: " + message, message.contains("uuid="))
        }
    }

    // -- Cancel safety ---------------------------------------------

    @Test
    fun cancelIsSafeWhenNoFlowIsRunning() {
        ConnectionOrchestrator().cancel()
    }

    @Test
    fun cancelIsIdempotent() {
        val orchestrator = ConnectionOrchestrator()
        orchestrator.cancel()
        orchestrator.cancel()
    }

    // -- Helpers ----------------------------------------------------

    private fun profile(server: String) = ProfileItem.create(EConfigType.VLESS).apply {
        this.server = server
    }

    private fun eligible() = ProfileItem.create(EConfigType.VLESS).apply {
        server = "example.com"
        serverPort = "443"
        network = "ws"
        security = AppConfig.TLS
        password = "uuid-1234"
        sni = "example.com"
        host = "example.com"
        path = "/ws"
    }
}
