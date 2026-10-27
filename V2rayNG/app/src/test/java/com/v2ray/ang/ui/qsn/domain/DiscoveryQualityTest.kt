package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The discovery quality rule and the accumulator that stops it.
 *
 * The rule is the one the historical ISP discovery used: a range qualifies when
 * a sampled address answers **and** carries real upload traffic. These tests
 * pin that, and pin the "five good ranges then stop" behaviour that lets a
 * connection start before the whole dataset is measured.
 */
class DiscoveryQualityTest {

    // ── The rule ──

    @Test
    fun anAnswerWithEnoughUploadQualifies() {
        assertEquals(RangeVerdict.Good, DiscoveryQuality.verdict(reachable = true, uploadKBps = 20L))
        assertEquals(RangeVerdict.Good, DiscoveryQuality.verdict(reachable = true, uploadKBps = 250L))
    }

    @Test
    fun anAnswerBelowTheGateDoesNotQualify() {
        assertEquals(RangeVerdict.NotQualified, DiscoveryQuality.verdict(reachable = true, uploadKBps = 19L))
        assertEquals(RangeVerdict.NotQualified, DiscoveryQuality.verdict(reachable = true, uploadKBps = 0L))
    }

    @Test
    fun aMissingMeasurementIsNotAZero() {
        // The traffic test may not have run at all. That is neither a pass nor a
        // failure, so it must not be scored as 0 KB/s.
        assertEquals(RangeVerdict.NotMeasured, DiscoveryQuality.verdict(reachable = true, uploadKBps = null))
        assertFalse(DiscoveryQuality.qualifies(reachable = true, uploadKBps = null))
    }

    @Test
    fun aCandidateThatNeverAnsweredCannotQualify() {
        assertEquals(RangeVerdict.NotQualified, DiscoveryQuality.verdict(reachable = false, uploadKBps = 500L))
        assertEquals(RangeVerdict.NotQualified, DiscoveryQuality.verdict(reachable = false, uploadKBps = null))
    }

    @Test
    fun theGateIsTheDocumentedTwentyKilobytesPerSecond() {
        assertEquals(20L, DiscoveryQuality.MIN_UPLOAD_KBPS)
        assertEquals(5, DiscoveryQuality.TARGET_RANGES)
    }

    // ── The accumulator ──

    private fun range(cidr: String, upload: Long = 50L) =
        QualifiedRange(cidr = cidr, uploadKBps = upload, latencyMs = 40L, validatedAt = 1L)

    @Test
    fun fiveDistinctRangesStopTheRun() {
        val accumulator = DiscoveryAccumulator()
        val accepted = listOf(
            "104.16.0.0/24",
            "104.17.0.0/24",
            "104.18.0.0/24",
            "104.19.0.0/24",
            "104.20.0.0/24",
        ).map { accumulator.offer(range(it)) }

        assertTrue(accepted.all { it })
        assertTrue(accumulator.reachedTarget)
        assertEquals(5, accumulator.size)
        assertFalse("a sixth range must be refused", accumulator.offer(range("104.21.0.0/24")))
        assertEquals(5, accumulator.qualified.size)
    }

    @Test
    fun theSameRangeIsNeverCountedTwice() {
        val accumulator = DiscoveryAccumulator(target = 5)
        assertTrue(accumulator.offer(range("104.16.0.0/24")))
        assertFalse(accumulator.offer(range("104.16.0.0/24")))
        assertEquals(1, accumulator.size)
        assertFalse(accumulator.reachedTarget)
    }

    @Test
    fun aBlankRangeIsIgnored() {
        val accumulator = DiscoveryAccumulator()
        assertFalse(accumulator.offer(range("   ")))
        assertEquals(0, accumulator.size)
    }

    @Test
    fun theAccumulatorKeepsInsertionOrder() {
        val accumulator = DiscoveryAccumulator()
        accumulator.offer(range("104.18.0.0/24"))
        accumulator.offer(range("104.16.0.0/24"))
        assertEquals(
            listOf("104.18.0.0/24", "104.16.0.0/24"),
            accumulator.qualified.map { it.cidr },
        )
    }

    // ── The persisted set ──

    @Test
    fun corruptRangesAreDroppedInsteadOfRepaired() {
        val clean = RangeSet.sanitize(
            listOf(
                range("104.16.0.0/24"),
                range("not-a-cidr"),
                range("104.17.0.0/33"),
                range("104.18.0.0/8"),
                range(""),
            ),
        )
        assertEquals(listOf("104.16.0.0/24"), clean.map { it.cidr })
    }

    @Test
    fun duplicatesAreFoldedIntoOneEntry() {
        val clean = RangeSet.sanitize(
            listOf(
                range("104.16.0.0/24", upload = 30L),
                range("104.16.0.0/24", upload = 90L),
            ),
        )
        assertEquals(1, clean.size)
        assertEquals(90L, clean.first().uploadKBps)
    }

    @Test
    fun aRangeIsNormalisedToItsNetworkAddress() {
        val clean = RangeSet.sanitize(listOf(range("104.16.5.9/24")))
        assertEquals("104.16.5.0/24", clean.first().cidr)
    }

    @Test
    fun theDatabaseIsBounded() {
        val many = (0 until RangeSet.MAX_RANGES + 10).map { index ->
            range("10." + (index / 256) + "." + (index % 256) + ".0/24", upload = index.toLong())
        }
        assertEquals(RangeSet.MAX_RANGES, RangeSet.sanitize(many).size)
    }

    @Test
    fun mergingKeepsWhatWasAlreadyQualified() {
        val existing = listOf(range("104.16.0.0/24", upload = 100L))
        val found = listOf(range("104.17.0.0/24", upload = 40L))
        val merged = RangeSet.merge(existing, found)
        assertEquals(2, merged.size)
        assertEquals("104.16.0.0/24", merged.first().cidr)
    }

    @Test
    fun mergingTheSameRangeKeepsTheBetterMeasurement() {
        val merged = RangeSet.merge(
            existing = listOf(range("104.16.0.0/24", upload = 100L)),
            found = listOf(range("104.16.0.0/24", upload = 40L)),
        )
        assertEquals(1, merged.size)
        assertEquals(100L, merged.first().uploadKBps)
    }

    @Test
    fun theCidrListIsTheValidatedOne() {
        val cidrs = RangeSet.cidrs(listOf(range("104.16.0.0/24"), range("bogus")))
        assertEquals(listOf("104.16.0.0/24"), cidrs)
    }
}
