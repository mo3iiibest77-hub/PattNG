package com.v2ray.ang.ui.connect

import com.v2ray.ang.senpai.ProbeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probe isolation: the rule that keeps a scan from creating configurations.
 *
 * A scan measures through throw-away profiles. The bug was that those profiles
 * were published into the user's own subscription, so every candidate appeared
 * in the Configurations screen as a copy of the selected configuration (same
 * remarks, same port) until it was deleted by hand.
 *
 * The fix is an identity rule, not a prefix convention: a probe lives under a
 * private subscription id that is never registered, so it cannot appear in any
 * list built from the user's subscriptions.
 */
class ProbeProfileTest {

    private val baseGuid = "6f1c8f0e-4a2b-4c3d-9e8f-0123456789ab"

    @Test
    fun aProbeGetsItsOwnGuidAndNeverReusesTheBaseOne() {
        val probe = ProbeProfile.guidFor(baseGuid, "104.16.21.7")
        assertNotEquals(baseGuid, probe)
        assertTrue("the base guid is kept only for diagnostics", probe.contains(baseGuid))
    }

    @Test
    fun everyAddressGetsADistinctProbeGuid() {
        val first = ProbeProfile.guidFor(baseGuid, "104.16.21.7")
        val second = ProbeProfile.guidFor(baseGuid, "104.16.21.8")
        assertNotEquals(first, second)
        assertEquals(first, ProbeProfile.guidFor(baseGuid, "104.16.21.7"))
    }

    @Test
    fun theProbeGuidIsRecognisable() {
        val probe = ProbeProfile.guidFor(baseGuid, "104.16.21.7")
        assertTrue(ProbeProfile.isProbe(probe))
        assertFalse(ProbeProfile.isProbe(baseGuid))
        assertFalse(ProbeProfile.isProbe(""))
    }

    @Test
    fun theProbeSubscriptionIsNotTheUsersSubscription() {
        // Not the default group, not a real subscription guid: a private index.
        assertTrue(ProbeProfile.isIsolated(ProbeProfile.SUBSCRIPTION_ID))
        assertFalse(ProbeProfile.isIsolated(null))
        assertFalse(ProbeProfile.isIsolated(""))
        assertFalse(ProbeProfile.isIsolated("6f1c8f0e-4a2b-4c3d-9e8f-0123456789ab"))
    }

    @Test
    fun theProbeSubscriptionIdIsStable() {
        // Persisted probes must still be purgeable after an upgrade.
        assertEquals("qsn-probe", ProbeProfile.SUBSCRIPTION_ID)
    }

    @Test
    fun theProbePrefixIsStable() {
        assertEquals("cfscanner-", ProbeProfile.GUID_PREFIX)
    }

    @Test
    fun anIpv6LiteralStillProducesASafeGuid() {
        val probe = ProbeProfile.guidFor(baseGuid, "2606:4700::1")
        assertFalse(probe.contains(":"))
        assertTrue(ProbeProfile.isProbe(probe))
    }
}
