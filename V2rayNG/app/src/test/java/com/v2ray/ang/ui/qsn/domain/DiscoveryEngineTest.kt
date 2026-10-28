package com.v2ray.ang.ui.qsn.domain

import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The discovery engine, exercised without Android.
 *
 * These are the behaviours the requirement names: measure every range, keep
 * going after a failure, persist each result as it arrives, resume without
 * redoing a completed range, retry an uncertain measurement, and never stop
 * after an arbitrary number of good ranges.
 */
class DiscoveryEngineTest {

    private val dataset = listOf(
        "104.16.0.0/24",
        "104.17.0.0/24",
        "104.18.0.0/24",
        "104.19.0.0/24",
        "104.20.0.0/24",
    )

    private class MemoryStore : DiscoveryStore {
        val outcomes = LinkedHashMap<String, RangeOutcome>()
        val writes = mutableListOf<RangeOutcome>()
        var phase: DiscoveryPhase = DiscoveryPhase.Idle
        var total: Int = 0
        var scanned: Int = 0

        @Synchronized
        override fun snapshot(isp: String): DiscoverySnapshot =
            DiscoverySnapshot(isp, phase, total, outcomes.toMap())

        @Synchronized
        override fun record(isp: String, outcome: RangeOutcome) {
            outcomes[outcome.cidr] = outcome
            writes.add(outcome)
        }

        @Synchronized
        override fun setPhase(isp: String, phase: DiscoveryPhase, total: Int, scanned: Int) {
            this.phase = phase
            this.total = total
            this.scanned = scanned
        }
    }

    private fun good(cidr: String) = RangeMeasurement(
        reachable = true,
        uploadKBps = 80L,
        downloadKBps = 200L,
        latencyMs = 40L,
    )

    private fun failed() = RangeMeasurement(reachable = false)

    @Test
    fun everyRangeIsMeasuredAndTheRunCompletes() {
        val store = MemoryStore()
        val probes = Collections.synchronizedList(mutableListOf<String>())
        val engine = DiscoveryEngine(
            store = store,
            probe = { cidr -> probes.add(cidr); good(cidr) },
            concurrency = 2,
            retryDelayMs = 0,
        )

        val summary = runBlocking { engine.run("MCI", dataset) }

        assertEquals(dataset, probes.sorted())
        assertEquals(5, summary.scanned)
        assertEquals(5, summary.good)
        assertTrue(summary.completed)
        assertEquals(DiscoveryPhase.Completed, store.phase)
        assertEquals(5, store.writes.size)
    }

    @Test
    fun aFailedRangeDoesNotStopTheRest() {
        val store = MemoryStore()
        val probes = mutableListOf<String>()
        val engine = DiscoveryEngine(
            store = store,
            probe = { cidr ->
                probes.add(cidr)
                if (cidr == "104.17.0.0/24") failed() else good(cidr)
            },
            concurrency = 1,
            retryDelayMs = 0,
        )

        val summary = runBlocking { engine.run("MCI", dataset) }

        assertEquals(5, probes.size)
        assertEquals(4, summary.good)
        assertEquals(1, summary.failed)
        assertTrue(summary.completed)
        assertEquals(DiscoveryPhase.Completed, store.phase)
    }

    @Test
    fun aRunKeepsGoingPastAnyNumberOfGoodRanges() {
        val many = (0 until 40).map { index -> "104." + (16 + index) + ".0.0/24" }
        val store = MemoryStore()
        val probes = AtomicInteger()
        val engine = DiscoveryEngine(
            store = store,
            probe = { probes.incrementAndGet(); good("x") },
            concurrency = 4,
            retryDelayMs = 0,
        )

        val summary = runBlocking { engine.run("MCI", many) }

        assertEquals(40, probes.get())
        assertEquals(40, summary.good)
        assertTrue(summary.completed)
    }

    @Test
    fun aResumeOnlyMeasuresRangesWithoutAFinalResult() {
        val store = MemoryStore()
        store.outcomes["104.16.0.0/24"] = RangeOutcome(
            cidr = "104.16.0.0/24",
            state = RangeState.Good,
            uploadKBps = 70L,
        )
        store.outcomes["104.17.0.0/24"] = RangeOutcome(
            cidr = "104.17.0.0/24",
            state = RangeState.Failed,
        )
        store.outcomes["104.18.0.0/24"] = RangeOutcome(
            cidr = "104.18.0.0/24",
            state = RangeState.Error,
            attempts = 2,
        )
        val probes = Collections.synchronizedList(mutableListOf<String>())
        val engine = DiscoveryEngine(
            store = store,
            probe = { cidr -> probes.add(cidr); good(cidr) },
            concurrency = 1,
            retryDelayMs = 0,
        )

        val summary = runBlocking { engine.run("MCI", dataset) }

        assertEquals(
            listOf("104.18.0.0/24", "104.19.0.0/24", "104.20.0.0/24"),
            probes.sorted(),
        )
        assertEquals(5, summary.scanned)
        assertEquals(4, summary.good)
        assertTrue(summary.completed)
        assertEquals("the stored Good result is untouched", 70L, store.outcomes["104.16.0.0/24"]?.uploadKBps ?: -1L)
    }

    @Test
    fun aCompletedDatasetIsNotProbedAgain() {
        val store = MemoryStore()
        dataset.forEach { cidr ->
            store.outcomes[cidr] = RangeOutcome(cidr = cidr, state = RangeState.Good, uploadKBps = 10L)
        }
        val probes = AtomicInteger()
        val engine = DiscoveryEngine(
            store = store,
            probe = { probes.incrementAndGet(); good("x") },
            retryDelayMs = 0,
        )

        val summary = runBlocking { engine.run("MCI", dataset) }

        assertEquals(0, probes.get())
        assertTrue(summary.completed)
        assertEquals(DiscoveryPhase.Completed, store.phase)
    }

    @Test
    fun anUncertainRangeIsRetriedThenRecordedAsUncertain() {
        val store = MemoryStore()
        var attempts = 0
        val engine = DiscoveryEngine(
            store = store,
            probe = {
                attempts++
                RangeMeasurement(reachable = false, error = true)
            },
            concurrency = 1,
            maxAttempts = 2,
            retryDelayMs = 0,
        )

        val summary = runBlocking { engine.run("MCI", listOf("104.16.0.0/24")) }

        assertEquals(2, attempts)
        assertEquals(1, summary.errors)
        assertEquals(0, summary.good)
        assertFalse("an uncertain range is not a final result", summary.completed)
        assertEquals(DiscoveryPhase.Paused, store.phase)
        assertEquals(2, store.outcomes["104.16.0.0/24"]?.attempts ?: -1)
    }

    @Test
    fun aTransientErrorIsRetriedAndCanStillQualify() {
        val store = MemoryStore()
        var attempts = 0
        val engine = DiscoveryEngine(
            store = store,
            probe = {
                attempts++
                if (attempts == 1) {
                    RangeMeasurement(reachable = false, error = true)
                } else {
                    good("x")
                }
            },
            concurrency = 1,
            maxAttempts = 2,
            retryDelayMs = 0,
        )

        val summary = runBlocking { engine.run("MCI", listOf("104.16.0.0/24")) }

        assertEquals(2, attempts)
        assertEquals(1, summary.good)
        assertTrue(summary.completed)
        assertEquals(RangeState.Good, store.outcomes["104.16.0.0/24"]?.state)
    }

    @Test
    fun pendingSkipsEveryFinalResultAndKeepsRetryableOnes() {
        val snapshot = DiscoverySnapshot(
            isp = "MCI",
            phase = DiscoveryPhase.Paused,
            total = 4,
            outcomes = mapOf(
                "104.16.0.0/24" to RangeOutcome("104.16.0.0/24", RangeState.Good),
                "104.17.0.0/24" to RangeOutcome("104.17.0.0/24", RangeState.Failed),
                "104.18.0.0/24" to RangeOutcome("104.18.0.0/24", RangeState.Error),
                "104.19.0.0/24" to RangeOutcome("104.19.0.0/24", RangeState.Running),
            ),
        )
        val pending = DiscoveryPlan.pending(
            listOf("104.16.0.0/24", "104.17.0.0/24", "104.18.0.0/24", "104.19.0.0/24"),
            snapshot,
        )
        assertEquals(listOf("104.18.0.0/24", "104.19.0.0/24"), pending)
    }
}

