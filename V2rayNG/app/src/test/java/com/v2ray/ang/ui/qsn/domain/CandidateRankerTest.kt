package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CandidateRanker] and [Statistics].
 *
 * Every test drives the real production functions with plain data; nothing is
 * re-implemented here. The weights are asserted explicitly because the whole
 * point of the ranker is that its priorities are visible and reviewable.
 */
class CandidateRankerTest {

    // ── Statistics ──

    @Test
    fun `median of an odd list is the middle value`() {
        assertEquals(3L, Statistics.median(listOf(5L, 1L, 3L)))
    }

    @Test
    fun `median of an even list averages the two middle values`() {
        assertEquals(4L, Statistics.median(listOf(1L, 3L, 5L, 7L)))
    }

    @Test
    fun `median of an empty list is null`() {
        assertNull(Statistics.median(emptyList<Long>()))
    }

    @Test
    fun `mad of a single sample is zero`() {
        assertEquals(0L, Statistics.medianAbsoluteDeviation(listOf(40L)))
    }

    @Test
    fun `mad ignores a single spike`() {
        // median(38,40,42,900) = 41, deviations 3,1,1,859 -> median 2
        assertEquals(2L, Statistics.medianAbsoluteDeviation(listOf(38L, 40L, 42L, 900L)))
    }

    @Test
    fun `mad of an empty list is null`() {
        assertNull(Statistics.medianAbsoluteDeviation(emptyList<Long>()))
    }

    // ── Ranking ──

    @Test
    fun `unreachable candidates are dropped`() {
        val ranked = CandidateRanker.rank(
            listOf(
                metrics("1.1.1.1", reachable = false, latency = emptyList()),
                metrics("2.2.2.2"),
            )
        )
        assertEquals(1, ranked.size)
        assertEquals("2.2.2.2", ranked[0].ip)
    }

    @Test
    fun `an empty batch ranks nothing`() {
        assertTrue(CandidateRanker.rank(emptyList()).isEmpty())
        assertNull(CandidateRanker.best(emptyList()))
    }

    @Test
    fun `lower latency wins when throughput is identical`() {
        val fast = metrics("1.0.0.1", latency = listOf(30L, 30L, 30L))
        val slow = metrics("1.0.0.2", latency = listOf(400L, 400L, 400L))
        assertEquals("1.0.0.1", CandidateRanker.best(listOf(slow, fast))!!.ip)
    }

    @Test
    fun `higher download wins when latency is identical`() {
        val fastDown = metrics("1.0.0.1", download = 900L, upload = 100L)
        val slowDown = metrics("1.0.0.2", download = 100L, upload = 100L)
        assertEquals("1.0.0.1", CandidateRanker.best(listOf(slowDown, fastDown))!!.ip)
    }

    @Test
    fun `download outweighs upload`() {
        // The old scanner picked maxByOrNull { uploadKBps }, so this is the
        // exact regression the ranker exists to prevent.
        val downloadHeavy = metrics("1.0.0.1", download = 1000L, upload = 100L)
        val uploadHeavy = metrics("1.0.0.2", download = 100L, upload = 1000L)
        assertEquals("1.0.0.1", CandidateRanker.best(listOf(uploadHeavy, downloadHeavy))!!.ip)
    }

    @Test
    fun `failed probes lower the score of an otherwise identical candidate`() {
        val clean = metrics("1.0.0.1")
        val flaky = metrics("1.0.0.2", failures = 3)
        val ranked = CandidateRanker.rank(listOf(flaky, clean))
        assertEquals("1.0.0.1", ranked[0].ip)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun `a candidate without traffic measurements cannot beat a measured one`() {
        val measured = metrics("1.0.0.1")
        val unmeasured = metrics("1.0.0.2", download = null, upload = null)
        val ranked = CandidateRanker.rank(listOf(unmeasured, measured))
        assertEquals("1.0.0.1", ranked[0].ip)
    }

    @Test
    fun `confidence reflects how many dimensions were measured`() {
        val full = metrics("1.0.0.1", latency = listOf(10L, 12L, 11L))
        val latencyOnly = metrics("1.0.0.2", latency = listOf(10L, 12L, 11L), download = null, upload = null)
        assertEquals(100, CandidateRanker.confidence(full))
        assertTrue(CandidateRanker.confidence(latencyOnly) < 100)
    }

    @Test
    fun `ties break deterministically and do not depend on input order`() {
        val a = metrics("9.9.9.9", latency = listOf(50L, 50L, 50L), download = 300L, upload = 100L)
        val b = metrics("8.8.8.8", latency = listOf(50L, 50L, 50L), download = 300L, upload = 100L)
        assertEquals(
            CandidateRanker.rank(listOf(a, b)).map { it.ip },
            CandidateRanker.rank(listOf(b, a)).map { it.ip },
        )
        assertEquals(listOf("8.8.8.8", "9.9.9.9"), CandidateRanker.rank(listOf(a, b)).map { it.ip })
    }

    @Test
    fun `score stays inside 0 to 100`() {
        val ranked = CandidateRanker.rank(
            listOf(metrics("1.1.1.1", latency = listOf(1L, 1L, 1L), download = 99_999L, upload = 99_999L))
        )
        assertTrue(ranked[0].score in 0.0..100.0)
    }

    @Test
    fun `the documented weights sum to one`() {
        val sum = CandidateRanker.WEIGHT_LATENCY +
            CandidateRanker.WEIGHT_JITTER +
            CandidateRanker.WEIGHT_DOWNLOAD +
            CandidateRanker.WEIGHT_UPLOAD +
            CandidateRanker.WEIGHT_STABILITY
        assertEquals(1.0, sum, 1e-9)
    }

    @Test
    fun `latency score is full at the best bound and zero at the worst`() {
        assertEquals(100.0, CandidateRanker.latencyScore(25L), 1e-9)
        assertEquals(0.0, CandidateRanker.latencyScore(700L), 1e-9)
        assertEquals(0.0, CandidateRanker.latencyScore(5_000L), 1e-9)
        assertEquals(0.0, CandidateRanker.latencyScore(null), 1e-9)
    }

    @Test
    fun `jitter score decreases as jitter grows`() {
        val stable = CandidateRanker.jitterScore(0L)
        val shaky = CandidateRanker.jitterScore(120L)
        val terrible = CandidateRanker.jitterScore(250L)
        assertTrue(stable > shaky)
        assertTrue(shaky > terrible)
        assertEquals(0.0, terrible, 1e-9)
    }

    @Test
    fun `relative score is zero when the batch has no measurement`() {
        assertEquals(0.0, CandidateRanker.relativeScore(500L, null), 1e-9)
        assertEquals(0.0, CandidateRanker.relativeScore(null, 500.0), 1e-9)
        assertEquals(100.0, CandidateRanker.relativeScore(500L, 500.0), 1e-9)
    }

    private fun metrics(
        ip: String,
        reachable: Boolean = true,
        latency: List<Long> = listOf(50L, 50L, 50L),
        download: Long? = 500L,
        upload: Long? = 200L,
        failures: Int = 0,
        timeouts: Int = 0,
    ) = CandidateMetrics(
        ip = ip,
        reachable = reachable,
        latencySamples = latency,
        downloadKBps = download,
        uploadKBps = upload,
        successes = latency.size,
        failures = failures,
        timeouts = timeouts,
    )
}
