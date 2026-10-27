package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.senpai.CandidateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measured values versus unavailable ones.
 *
 * A number in the UI is a promise that something was measured, so a missing
 * measurement must render as "N/A" and a real zero must render as zero.
 */
class TrafficPresentationTest {

    @Test
    fun `a missing latency is not a zero`() {
        assertEquals("N/A", MetricFormat.latency(null))
        assertEquals("0 ms", MetricFormat.latency(0L))
        assertEquals("23 ms", MetricFormat.latency(23L))
    }

    @Test
    fun `a missing rate is not a zero`() {
        assertEquals("N/A", MetricFormat.rate(null))
        assertEquals("0 KB/s", MetricFormat.rate(0.0))
    }

    @Test
    fun `units switch from KB per second to MB per second`() {
        assertEquals("512 KB/s", MetricFormat.speed(512L))
        assertEquals("2.0 MB/s", MetricFormat.speed(2048L))
        assertEquals("N/A", MetricFormat.speed(null))
    }

    @Test
    fun `volume uses a readable unit`() {
        assertEquals("512 KB", MetricFormat.bytes(512L * 1024L))
        assertEquals("1.5 MB", MetricFormat.bytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("N/A", MetricFormat.bytes(null))
    }

    @Test
    fun `throughput is null when the traffic test did not run`() {
        val result = CandidateResult(ip = "1.1.1.1", latencySamples = listOf(20, 22, 24), hasTraffic = false)
        val candidate = ScanCandidate.from(result)
        assertNull(candidate.downloadKBps)
        assertNull(candidate.uploadKBps)
        assertEquals("N/A", MetricFormat.speed(candidate.downloadKBps))
    }

    @Test
    fun `throughput is reported when it was really measured`() {
        val result = CandidateResult(
            ip = "1.1.1.1",
            latencySamples = listOf(20, 22, 24),
            downloadKBps = 900,
            uploadKBps = 200,
            hasTraffic = true,
        )
        val candidate = ScanCandidate.from(result)
        assertEquals(900L, candidate.downloadKBps)
        assertEquals(200L, candidate.uploadKBps)
    }

    @Test
    fun `jitter needs more than one sample`() {
        val single = ScanCandidate.from(CandidateResult(ip = "1.1.1.1", latencySamples = listOf(20)))
        assertNull(single.jitterMs)
        val several = ScanCandidate.from(
            CandidateResult(ip = "1.1.1.1", latencySamples = listOf(20, 30, 40)),
        )
        assertEquals(10L, several.jitterMs)
        // Ping and jitter are different measurements.
        assertEquals(30L, several.pingMs)
    }

    @Test
    fun `stability is a real ratio and unavailable without probes`() {
        val none = ScanCandidate(ip = "1.1.1.1")
        assertNull(none.stabilityPercent)
        val perfect = ScanCandidate(ip = "1.1.1.1", successes = 3)
        assertEquals(100, perfect.stabilityPercent)
        val partial = ScanCandidate(ip = "1.1.1.1", successes = 2, failures = 1, timeouts = 1)
        assertEquals(50, partial.stabilityPercent)
        assertFalse(ScanCandidate(ip = "1.1.1.1", failures = 2).answered)
    }

    @Test
    fun `the notification default only applies when nothing was stored`() {
        assertTrue(NotificationDefaults.enableTrafficWhenUnset(keyExists = false))
        assertFalse(NotificationDefaults.enableTrafficWhenUnset(keyExists = true))
    }
}
