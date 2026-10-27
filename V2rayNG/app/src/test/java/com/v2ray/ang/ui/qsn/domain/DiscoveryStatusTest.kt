package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the IP Ranges screen is allowed to say about a discovery run.
 *
 * Paused is the state the foreground run ends in after it has enough ranges,
 * and it is deliberately not Completed: the rest of the dataset is still
 * unmeasured and the background pass continues from there. A screen that
 * called that "finished" would hide pending work.
 */
class DiscoveryStatusTest {

    @Test
    fun aRunningRunIsActive() {
        val status = DiscoveryStatus(phase = DiscoveryPhase.Running, done = 12, total = 632)
        assertTrue(status.isActive)
        assertFalse(status.isTerminal)
    }

    @Test
    fun aPausedRunIsNeitherActiveNorFinished() {
        val status = DiscoveryStatus(phase = DiscoveryPhase.Paused, qualified = 5, done = 40)
        assertFalse(status.isActive)
        assertFalse("paused must not read as completed", status.isTerminal)
    }

    @Test
    fun aCompletedRunIsTerminal() {
        val status = DiscoveryStatus(phase = DiscoveryPhase.Completed, qualified = 7)
        assertTrue(status.isTerminal)
        assertFalse(status.isActive)
    }

    @Test
    fun cancellationAndFailureAreTerminal() {
        assertTrue(DiscoveryStatus(phase = DiscoveryPhase.Cancelled).isTerminal)
        assertTrue(DiscoveryStatus(phase = DiscoveryPhase.Failed).isTerminal)
    }

    @Test
    fun idleIsNeitherActiveNorTerminalSoNothingSpins() {
        val status = DiscoveryStatus()
        assertEquals(DiscoveryPhase.Idle, status.phase)
        assertFalse(status.isActive)
        assertFalse(status.isTerminal)
    }

    @Test
    fun theBackgroundPassIsMarked() {
        val status = DiscoveryStatus(phase = DiscoveryPhase.Running, background = true)
        assertTrue(status.background)
    }
}
