package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a resumed run actually measures.
 *
 * Discovery is interrupted all the time: the screen is closed, the app is
 * swiped away, the process is killed, the device reboots. What makes those
 * interruptions cheap is a rule about the persisted outcomes, not about the
 * place the run happens to live: a final result is never measured twice, an
 * unfinished range is always measured again, and the order stays the dataset
 * order. These are the guarantees the resume path relies on.
 */
class DiscoveryResumeTest {

    private val dataset = listOf(
        "104.16.0.0/24",
        "104.16.1.0/24",
        "104.16.2.0/24",
        "104.16.3.0/24",
        "104.16.4.0/24",
        "104.16.5.0/24",
    )

    private fun snapshot(vararg outcomes: RangeOutcome) = DiscoverySnapshot(
        isp = "MCI",
        dataset = dataset,
        outcomes = outcomes.associateBy { it.cidr },
    )

    @Test
    fun aResumeStartsAtTheFirstUnfinishedRange() {
        val pending = DiscoveryPlan.pending(
            dataset,
            snapshot(
                RangeOutcome("104.16.0.0/24", RangeState.Good, uploadKBps = 900L),
                RangeOutcome("104.16.1.0/24", RangeState.Failed),
            ),
        )
        assertEquals(dataset.subList(2, dataset.size), pending)
    }

    @Test
    fun aRangeThatWasRunningWhenTheProcessDiedIsMeasuredAgain() {
        // The process can die in the middle of a range. That range has no
        // result, so it must be the first thing the resume measures again
        // instead of being skipped as if it had finished.
        val pending = DiscoveryPlan.pending(
            dataset,
            snapshot(
                RangeOutcome("104.16.0.0/24", RangeState.Good, uploadKBps = 900L),
                RangeOutcome("104.16.1.0/24", RangeState.Running),
            ),
        )
        assertEquals("104.16.1.0/24", pending.first())
    }

    @Test
    fun aFinalResultIsNeverMeasuredTwice() {
        val all = dataset.map { cidr -> RangeOutcome(cidr, RangeState.Failed) }
        assertTrue(DiscoveryPlan.pending(dataset, snapshot(*all.toTypedArray())).isEmpty())
    }

    @Test
    fun onlyGoodAndFailedAreFinal() {
        assertTrue(RangeOutcome("104.16.0.0/24", RangeState.Good).isTerminal)
        assertTrue(RangeOutcome("104.16.0.0/24", RangeState.Failed).isTerminal)
        assertFalse(RangeOutcome("104.16.0.0/24", RangeState.Pending).isTerminal)
        assertFalse(RangeOutcome("104.16.0.0/24", RangeState.Running).isTerminal)
        assertFalse(RangeOutcome("104.16.0.0/24", RangeState.Error).isTerminal)
    }

    @Test
    fun anUncertainRangeIsRetriedOnResume() {
        // An Error means the probe itself failed, not that the range is bad.
        // Dropping it would silently lose a range nobody really measured.
        val pending = DiscoveryPlan.pending(
            dataset,
            snapshot(RangeOutcome("104.16.3.0/24", RangeState.Error)),
        )
        assertTrue(pending.contains("104.16.3.0/24"))
        assertEquals(dataset.size, pending.size)
    }

    @Test
    fun theResumeOrderIsTheDatasetOrder() {
        val pending = DiscoveryPlan.pending(
            dataset,
            snapshot(RangeOutcome("104.16.4.0/24", RangeState.Good, uploadKBps = 500L)),
        )
        assertEquals(
            listOf("104.16.0.0/24", "104.16.1.0/24", "104.16.2.0/24", "104.16.3.0/24", "104.16.5.0/24"),
            pending,
        )
    }

    @Test
    fun aRangeWrittenTwiceInTheDatasetIsMeasuredOnce() {
        val duplicated = dataset + listOf("104.16.2.0/24", " 104.16.2.0/24 ")
        val pending = DiscoveryPlan.pending(duplicated, snapshot())
        assertEquals(dataset.size, pending.size)
        assertEquals(1, pending.count { it == "104.16.2.0/24" })
    }

    @Test
    fun oneIspProgressNeverConsumesAnotherIspsRanges() {
        // Progress is stored per ISP. A completed range of one ISP must not
        // remove that CIDR from another ISP's plan: the two networks measure
        // the same dataset independently.
        val mci = DiscoverySnapshot(
            isp = "MCI",
            dataset = dataset,
            outcomes = mapOf("104.16.0.0/24" to RangeOutcome("104.16.0.0/24", RangeState.Good)),
        )
        val irancell = DiscoverySnapshot(isp = "Irancell", dataset = dataset)
        assertEquals(dataset.size - 1, DiscoveryPlan.pending(dataset, mci).size)
        assertEquals(dataset.size, DiscoveryPlan.pending(dataset, irancell).size)
    }

    @Test
    fun aPausedRunStillHasWorkLeft() {
        val status = DiscoveryStatus(
            phase = DiscoveryPhase.Paused,
            ispName = "MCI",
            done = 2,
            total = dataset.size,
            qualified = 1,
        )
        assertTrue(status.isResumable)
        assertFalse(status.isTerminal)
        assertEquals(33, status.percent)
    }

    @Test
    fun anInterruptedRunIsResumableAndACompletedOneIsNot() {
        assertTrue(DiscoveryStatus(phase = DiscoveryPhase.Interrupted).isResumable)
        assertTrue(DiscoveryStatus(phase = DiscoveryPhase.Paused).isResumable)
        assertFalse(DiscoveryStatus(phase = DiscoveryPhase.Completed).isResumable)
        assertTrue(DiscoveryStatus(phase = DiscoveryPhase.Completed).isTerminal)
    }
}