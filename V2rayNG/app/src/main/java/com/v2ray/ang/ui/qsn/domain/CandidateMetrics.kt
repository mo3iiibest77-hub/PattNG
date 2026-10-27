package com.v2ray.ang.ui.qsn.domain

import kotlin.math.abs

/**
 * Raw measurements for one candidate, exactly as they were observed.
 *
 * A null metric means "not measured", which is different from zero: the ranker
 * treats unknown dimensions as a zero contribution to the score and lowers the
 * confidence, so a candidate can never win because one number is missing.
 */
data class CandidateMetrics(
    val ip: String,
    /** True when the probe really connected and the config was built by the core. */
    val reachable: Boolean,
    /** Successful latency samples in milliseconds, in probe order. */
    val latencySamples: List<Long> = emptyList(),
    /** Measured download throughput in KB/s, null when the traffic test did not run or failed. */
    val downloadKBps: Long? = null,
    /** Measured upload throughput in KB/s, null when the traffic test did not run or failed. */
    val uploadKBps: Long? = null,
    val successes: Int = 0,
    val failures: Int = 0,
    val timeouts: Int = 0,
) {
    val probeCount: Int get() = successes + failures + timeouts

    /** Share of probes that did not produce a usable answer, 0..1. */
    val failureRatio: Double
        get() = if (probeCount == 0) 1.0 else (failures + timeouts).toDouble() / probeCount.toDouble()

    val medianLatencyMs: Long? get() = Statistics.median(latencySamples)

    /** Median absolute deviation of the latency samples: robust to a single spike. */
    val jitterMs: Long? get() = Statistics.medianAbsoluteDeviation(latencySamples)
}

/** Small robust statistics helpers, kept separate so they can be unit tested. */
object Statistics {

    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2L
        }
    }

    /**
     * Median absolute deviation: median(|x - median(x)|).
     *
     * Used instead of (max - min) because one slow sample would otherwise
     * dominate the jitter of an otherwise stable edge.
     */
    fun medianAbsoluteDeviation(values: List<Long>): Long? {
        val center = median(values) ?: return null
        if (values.size < 2) return 0L
        return median(values.map { abs(it - center) })
    }
}

/** A candidate with its computed score. */
data class ScoredCandidate(
    val metrics: CandidateMetrics,
    /** 0..100, already penalised for failures. */
    val score: Double,
    /** 0..100: how much of the measurement set was actually available. */
    val confidence: Int,
) {
    val ip: String get() = metrics.ip
}

/**
 * Deterministic, documented ranking for Cloudflare edge candidates.
 *
 * The old scanner picked `maxByOrNull { it.uploadKBps }`, so a fast upload could
 * hide a dead connection and download speed never mattered. This ranker:
 *
 *  - rejects candidates that never connected,
 *  - scores five independent dimensions on a common 0..100 scale,
 *  - weights them explicitly (see the constants),
 *  - multiplies by a failure penalty,
 *  - breaks ties deterministically.
 *
 * Weights (sum = 1.00):
 *   latency 0.30, jitter 0.10, download 0.25, upload 0.15, stability 0.20
 *
 * Download outweighs upload on purpose: for browsing and streaming the
 * downstream direction is what the user feels, while upload still counts.
 */
object CandidateRanker {

    const val WEIGHT_LATENCY = 0.30
    const val WEIGHT_JITTER = 0.10
    const val WEIGHT_DOWNLOAD = 0.25
    const val WEIGHT_UPLOAD = 0.15
    const val WEIGHT_STABILITY = 0.20

    /** Latency at or below this is a perfect latency score. */
    const val BEST_LATENCY_MS = 25.0

    /** Latency at or above this scores zero. */
    const val WORST_LATENCY_MS = 700.0

    /** Jitter at or above this scores zero. */
    const val WORST_JITTER_MS = 250.0

    /** Maximum share of the score removed by failed probes. */
    const val FAILURE_PENALTY = 0.35

    /**
     * Ranks [candidates] best-first. Unreachable candidates are dropped.
     *
     * Throughput is normalised against the best candidate of the same batch,
     * because an absolute KB/s scale would depend on the device and network.
     */
    fun rank(candidates: List<CandidateMetrics>): List<ScoredCandidate> {
        val usable = candidates.filter { it.reachable }
        if (usable.isEmpty()) return emptyList()

        val maxDownload = usable.mapNotNull { it.downloadKBps }.maxOrNull()?.toDouble()
        val maxUpload = usable.mapNotNull { it.uploadKBps }.maxOrNull()?.toDouble()

        return usable
            .map { metrics ->
                val latency = latencyScore(metrics.medianLatencyMs)
                val jitter = jitterScore(metrics.jitterMs)
                val download = relativeScore(metrics.downloadKBps, maxDownload)
                val upload = relativeScore(metrics.uploadKBps, maxUpload)
                val stability = stabilityScore(metrics)

                val base = WEIGHT_LATENCY * latency +
                    WEIGHT_JITTER * jitter +
                    WEIGHT_DOWNLOAD * download +
                    WEIGHT_UPLOAD * upload +
                    WEIGHT_STABILITY * stability

                val penalised = base * (1.0 - FAILURE_PENALTY * metrics.failureRatio)
                ScoredCandidate(
                    metrics = metrics,
                    score = penalised.coerceIn(0.0, 100.0),
                    confidence = confidence(metrics),
                )
            }
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.score }
                    .thenBy { it.metrics.medianLatencyMs ?: Long.MAX_VALUE }
                    .thenBy { it.ip },
            )
    }

    /** @return the winning candidate, or null when nothing was reachable. */
    fun best(candidates: List<CandidateMetrics>): ScoredCandidate? = rank(candidates).firstOrNull()

    fun latencyScore(medianMs: Long?): Double {
        if (medianMs == null) return 0.0
        val span = WORST_LATENCY_MS - BEST_LATENCY_MS
        val normalised = (WORST_LATENCY_MS - medianMs.toDouble()) / span
        return (normalised.coerceIn(0.0, 1.0)) * 100.0
    }

    fun jitterScore(jitterMs: Long?): Double {
        if (jitterMs == null) return 0.0
        val normalised = 1.0 - (jitterMs.toDouble() / WORST_JITTER_MS)
        return (normalised.coerceIn(0.0, 1.0)) * 100.0
    }

    fun relativeScore(value: Long?, best: Double?): Double {
        if (value == null || best == null || best <= 0.0) return 0.0
        return ((value.toDouble() / best).coerceIn(0.0, 1.0)) * 100.0
    }

    fun stabilityScore(metrics: CandidateMetrics): Double {
        if (metrics.probeCount == 0) return 0.0
        return metrics.successes.toDouble() / metrics.probeCount.toDouble() * 100.0
    }

    /** How many of the five dimensions were actually measured. */
    fun confidence(metrics: CandidateMetrics): Int {
        var available = 0
        if (metrics.medianLatencyMs != null) available++
        if (metrics.jitterMs != null) available++
        if (metrics.downloadKBps != null) available++
        if (metrics.uploadKBps != null) available++
        if (metrics.probeCount > 0) available++
        return available * 100 / 5
    }
}
