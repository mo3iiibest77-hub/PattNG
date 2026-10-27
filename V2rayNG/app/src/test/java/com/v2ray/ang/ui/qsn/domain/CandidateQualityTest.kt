package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.senpai.CandidateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The green/neutral/red status of a scan result.
 *
 * The rule is deliberately not "the probe answered": a candidate is good only
 * when its traffic measurements exist and the upload clears the same gate
 * discovery uses. A missing measurement stays missing instead of becoming a
 * zero, and a measured zero stays a measurement.
 */
class CandidateQualityTest {

    private fun candidate(
        pingMs: Long? = 40L,
        upload: Long? = 60L,
        download: Long? = 400L,
        successes: Int = 3,
        failures: Int = 0,
    ) = ScanCandidate(
        ip = "104.16.0.1",
        pingMs = pingMs,
        jitterMs = 3L,
        downloadKBps = download,
        uploadKBps = upload,
        successes = successes,
        failures = failures,
    )

    @Test
    fun aMeasuredCandidateAboveTheGateIsGood() {
        assertEquals(CandidateQuality.Good, candidate().quality)
    }

    @Test
    fun latencyAloneIsNotEnough() {
        // Answered, but the traffic test never ran: neutral, not green.
        val incomplete = candidate(upload = null, download = null)
        assertEquals(CandidateQuality.Incomplete, incomplete.quality)
        assertTrue(incomplete.answered)
    }

    @Test
    fun aMeasuredCandidateBelowTheGateIsOrdinary() {
        assertEquals(CandidateQuality.Ordinary, candidate(upload = 5L).quality)
    }

    @Test
    fun aMeasuredZeroIsNotAMissingValue() {
        // 0 KB/s is a real, bad measurement: ordinary, not "not measured".
        assertEquals(CandidateQuality.Ordinary, candidate(upload = 0L, download = 0L).quality)
    }

    @Test
    fun aHalfMeasuredCandidateIsIncomplete() {
        assertEquals(CandidateQuality.Incomplete, candidate(download = null).quality)
        assertEquals(CandidateQuality.Incomplete, candidate(upload = null).quality)
    }

    @Test
    fun aCandidateThatNeverAnsweredIsFailed() {
        val failed = candidate(pingMs = null, upload = null, download = null, successes = 0, failures = 3)
        assertEquals(CandidateQuality.Failed, failed.quality)
    }

    @Test
    fun aScannerResultWithoutTrafficMapsToIncomplete() {
        val result = CandidateResult(
            ip = "104.16.0.2",
            latencySamples = listOf(30L, 32L, 31L),
            hasTraffic = false,
        )
        val mapped = ScanCandidate.from(result)
        assertNull(mapped.uploadKBps)
        assertNull(mapped.downloadKBps)
        assertEquals(CandidateQuality.Incomplete, mapped.quality)
    }

    @Test
    fun aScannerResultWithTrafficKeepsBothNumbers() {
        val result = CandidateResult(
            ip = "104.16.0.3",
            latencySamples = listOf(30L, 32L, 31L),
            uploadKBps = 80L,
            downloadKBps = 900L,
            hasTraffic = true,
        )
        val mapped = ScanCandidate.from(result)
        assertEquals(80L, mapped.uploadKBps)
        assertEquals(900L, mapped.downloadKBps)
        assertEquals(CandidateQuality.Good, mapped.quality)
    }
}
