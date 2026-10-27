package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection decision table.
 *
 * Every row of the table is a test, because this is the rule that decides
 * whether a Connect tap scans, reuses, discovers, or connects a configuration
 * as it is. A mistake here is exactly the "it scanned when I asked it to
 * connect" bug.
 */
class ConnectPolicyTest {

    private fun decide(
        mode: ConnectMode = ConnectMode.Automatic,
        guid: String = "guid-1",
        savedEdgeUsable: Boolean = false,
        hasQualifiedRanges: Boolean = false,
        refreshRequested: Boolean = false,
    ) = ConnectPolicy.decide(mode, guid, savedEdgeUsable, hasQualifiedRanges, refreshRequested)

    // ── Direct: the Configurations screen ──

    @Test
    fun directAlwaysConnectsTheChosenConfiguration() {
        assertEquals(ConnectDecision.Direct("guid-1"), decide(mode = ConnectMode.Direct))
        assertEquals(
            ConnectDecision.Direct("guid-1"),
            decide(mode = ConnectMode.Direct, savedEdgeUsable = true, hasQualifiedRanges = true),
        )
        assertEquals(
            ConnectDecision.Direct("guid-1"),
            decide(mode = ConnectMode.Direct, refreshRequested = true),
        )
    }

    @Test
    fun directNeverScansOrDiscovers() {
        listOf(
            decide(mode = ConnectMode.Direct),
            decide(mode = ConnectMode.Direct, savedEdgeUsable = true),
            decide(mode = ConnectMode.Direct, hasQualifiedRanges = true),
        ).forEach { decision ->
            assertTrue(ConnectPolicy.isDirect(decision))
            assertFalse(decision is ConnectDecision.ScanQualifiedRanges)
            assertFalse(decision is ConnectDecision.DiscoverThenScan)
            assertFalse(decision is ConnectDecision.ReuseSavedEdge)
        }
    }

    // ── Automatic: the Connection screen ──

    @Test
    fun aUsableSavedEdgeIsReused() {
        assertEquals(
            ConnectDecision.ReuseSavedEdge,
            decide(savedEdgeUsable = true, hasQualifiedRanges = true),
        )
    }

    @Test
    fun qualifiedRangesAreScannedWhenNoEdgeCanBeReused() {
        assertEquals(
            ConnectDecision.ScanQualifiedRanges,
            decide(savedEdgeUsable = false, hasQualifiedRanges = true),
        )
    }

    @Test
    fun discoveryRunsWhenNothingIsQualified() {
        assertEquals(
            ConnectDecision.DiscoverThenScan,
            decide(savedEdgeUsable = false, hasQualifiedRanges = false),
        )
    }

    @Test
    fun anExplicitRefreshForcesDiscoveryEvenWhenEverythingIsSaved() {
        assertEquals(
            ConnectDecision.DiscoverThenScan,
            decide(savedEdgeUsable = true, hasQualifiedRanges = true, refreshRequested = true),
        )
    }

    @Test
    fun theDecisionIsTotalForEveryCombination() {
        val modes = listOf(ConnectMode.Automatic, ConnectMode.Direct)
        val flags = listOf(false, true)
        modes.forEach { mode ->
            flags.forEach { saved ->
                flags.forEach { ranges ->
                    flags.forEach { refresh ->
                        val decision = decide(mode, "g", saved, ranges, refresh)
                        if (mode == ConnectMode.Direct) {
                            assertTrue(decision is ConnectDecision.Direct)
                        } else {
                            assertFalse(decision is ConnectDecision.Direct)
                        }
                    }
                }
            }
        }
    }
}
