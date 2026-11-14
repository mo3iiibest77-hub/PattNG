package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.senpai.CandidateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceCandidateSelectorTest {

    @Test
    fun rejectsLatencyOnlyOrFailedCandidatesPerSeptember8Engine() {
        val results = listOf(
            CandidateResult(
                ip = "104.16.0.1",
                latencySamples = listOf(20L),
                uploadKBps = 0L,
                downloadKBps = 0L,
                hasTraffic = false,
            ),
            CandidateResult(
                ip = "104.16.0.2",
                latencySamples = emptyList(),
                uploadKBps = 900L,
                downloadKBps = 900L,
                hasTraffic = true,
            ),
        )
        assertTrue(ReferenceCandidateSelector.eligible(results).isEmpty())
        assertNull(ReferenceCandidateSelector.selectBest(results))
    }

    @Test
    fun selectsMaximumUploadSpeedEvenWithHigherPingOrLowerDownload() {
        val highDownloadLowerUpload = CandidateResult(
            ip = "104.16.0.10",
            latencySamples = listOf(35L),
            uploadKBps = 450L,
            downloadKBps = 4000L,
            hasTraffic = true,
        )
        val winnerHighUpload = CandidateResult(
            ip = "172.64.0.20",
            latencySamples = listOf(120L),
            uploadKBps = 950L,
            downloadKBps = 1200L,
            hasTraffic = true,
        )
        val best = ReferenceCandidateSelector.selectBest(
            listOf(highDownloadLowerUpload, winnerHighUpload),
        )
        assertEquals("172.64.0.20", best?.ip)

        val scored = ReferenceCandidateSelector.scoreForDisplay(
            listOf(highDownloadLowerUpload, winnerHighUpload),
        )
        val topScored = scored.maxByOrNull { it.score }
        assertEquals("172.64.0.20", topScored?.ip)
    }

    @Test
    fun tiesBreakDeterministicallyByLowestLatencyThenIp() {
        val a = CandidateResult(
            ip = "104.16.0.9",
            latencySamples = listOf(90L),
            uploadKBps = 500L,
            downloadKBps = 1000L,
            hasTraffic = true,
        )
        val b = CandidateResult(
            ip = "104.16.0.8",
            latencySamples = listOf(60L),
            uploadKBps = 500L,
            downloadKBps = 1000L,
            hasTraffic = true,
        )
        assertEquals("104.16.0.8", ReferenceCandidateSelector.selectBest(listOf(a, b))?.ip)
    }
}
