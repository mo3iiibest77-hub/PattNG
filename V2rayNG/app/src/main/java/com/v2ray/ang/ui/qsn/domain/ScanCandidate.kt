package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.senpai.CandidateResult
import java.util.Locale

/** How a candidate reads at a glance, derived only from real measurements. */
enum class CandidateQuality {
    /** Answered and carried upload traffic above the discovery gate. */
    Good,

    /** Fully measured, but below the gate. */
    Ordinary,

    /** Never answered. */
    Failed,

    /** Answered, but at least one traffic measurement is missing. */
    Incomplete,
}

/**
 * One measured Cloudflare candidate, in the shape the UI renders.
 *
 * Every nullable field means "this was not measured". A value of 0 is a real
 * measurement and is never used as a stand-in for "unknown", which is why the
 * throughput fields stay null when the bounded traffic test did not run.
 */
data class ScanCandidate(
    val ip: String,
    /** Median latency in milliseconds, null when the edge never answered. */
    val pingMs: Long? = null,
    /** Median absolute deviation of the latency samples, null below two samples. */
    val jitterMs: Long? = null,
    val downloadKBps: Long? = null,
    val uploadKBps: Long? = null,
    val successes: Int = 0,
    val failures: Int = 0,
    val timeouts: Int = 0,
    val score: Double = 0.0,
    val confidence: Int = 0,
) {
    /** True when at least one probe came back. */
    val answered: Boolean get() = successes > 0

    /**
     * The status the card colours itself by.
     *
     * Latency alone never makes a candidate good: the traffic numbers must
     * exist and the upload must clear the same gate discovery uses. A missing
     * measurement is [CandidateQuality.Incomplete], never zero throughput and
     * never a silent downgrade.
     */
    val quality: CandidateQuality
        get() = when {
            !answered -> CandidateQuality.Failed
            uploadKBps == null || downloadKBps == null -> CandidateQuality.Incomplete
            DiscoveryQuality.qualifies(reachable = true, uploadKBps = uploadKBps) -> CandidateQuality.Good
            else -> CandidateQuality.Ordinary
        }

    /** Share of probes that answered, 0..100; null when nothing was probed. */
    val stabilityPercent: Int?
        get() {
            val total = successes + failures + timeouts
            if (total <= 0) return null
            return successes * 100 / total
        }

    companion object {
        /**
         * Maps a real scanner result.
         *
         * Jitter needs at least two samples to be meaningful, so a single
         * sample reports "N/A" instead of a fabricated 0.
         */
        fun from(result: CandidateResult): ScanCandidate = ScanCandidate(
            ip = result.ip,
            pingMs = result.latencyMs.takeIf { it >= 0L },
            jitterMs = if (result.latencySamples.size >= 2) result.jitterMs else null,
            downloadKBps = if (result.hasTraffic) result.downloadKBps else null,
            uploadKBps = if (result.hasTraffic) result.uploadKBps else null,
            successes = result.latencySamples.size,
            failures = result.failures,
            timeouts = result.timeouts,
            score = result.score,
            confidence = result.confidence,
        )
    }
}

/**
 * Formatting for measured values.
 *
 * "N/A" is the only representation of a missing measurement: no placeholder
 * number is ever rendered, because a number in this table is a promise that
 * something was measured.
 */
object MetricFormat {

    const val NOT_AVAILABLE = "N/A"

    fun latency(ms: Long?): String = if (ms == null) NOT_AVAILABLE else ms.toString() + " ms"

    fun jitter(ms: Long?): String = latency(ms)

    fun percent(value: Int?): String = if (value == null) NOT_AVAILABLE else value.toString() + "%"

    /** Throughput in the unit the requirement asks for: KB/s below 1 MB/s, then MB/s. */
    fun speed(kbps: Long?): String = when {
        kbps == null -> NOT_AVAILABLE
        kbps >= 1024L -> String.format(Locale.US, "%.1f MB/s", kbps / 1024.0)
        else -> kbps.toString() + " KB/s"
    }

    /** Same formatting for a live byte/second rate reported by the core. */
    fun rate(bytesPerSecond: Double?): String = when {
        bytesPerSecond == null -> NOT_AVAILABLE
        else -> speed((bytesPerSecond / 1024.0).toLong())
    }

    /** Cumulative volume, with the unit that keeps the number readable. */
    fun bytes(value: Long?): String {
        if (value == null || value < 0L) return NOT_AVAILABLE
        val kb = value / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }
}
