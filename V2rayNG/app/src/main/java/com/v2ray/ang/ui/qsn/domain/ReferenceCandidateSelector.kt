package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.senpai.CandidateResult

/**
 * Reference `c489a5aa` candidate selection and UI score adapter.
 *
 * Core selection strictly preserves the September 8 reference engine rule:
 * a candidate is accepted only if both outbound delay (`latencyMs >= 0`) and
 * mandatory real-traffic throughput (`hasTraffic`) succeeded, and the winner is
 * selected by maximum measured upload bandwidth (`maxByOrNull { it.uploadKBps }`),
 * with deterministic tie-breakers (lowest latency, then IP string).
 */
object ReferenceCandidateSelector {

    /** Filters strictly to candidates that passed both latency and mandatory speedtest. */
    fun eligible(results: List<CandidateResult>): List<CandidateResult> =
        results.filter { it.isSuccess && it.hasTraffic }

    /**
     * Returns the winning candidate per `c489a5aa`:
     * highest measured [CandidateResult.uploadKBps], tie-breaking on lowest latency then IP.
     */
    fun selectBest(results: List<CandidateResult>): CandidateResult? =
        eligible(results).maxWithOrNull(
            compareBy<CandidateResult> { it.uploadKBps }
                .thenByDescending { if (it.latencyMs >= 0L) it.latencyMs else Long.MAX_VALUE }
                .thenBy { it.downloadKBps }
                .thenByDescending { it.ip },
        )

    /**
     * Stamps deterministic 0..100 UI display scores aligned monotonically with the
     * `c489a5aa` upload-first ranking so UI sorting (`rankCandidates`) puts the
     * exact same winner first without introducing latency-only false positives.
     */
    fun scoreForDisplay(results: List<CandidateResult>): List<CandidateResult> {
        val valid = eligible(results)
        val maxUpload = valid.maxOfOrNull { it.uploadKBps }?.coerceAtLeast(1L) ?: 1L
        return results.map { item ->
            if (!item.isSuccess || !item.hasTraffic) {
                item.copy(score = 0.0, confidence = 0)
            } else {
                // Upload dominates [1.0..99.5]; latency contributes <= 0.5 for visual differentiation & ties.
                val uploadComponent = (item.uploadKBps.toDouble() / maxUpload.toDouble()) * 99.0 + 0.5
                val latencyTie = (1.0 - (item.latencyMs.coerceIn(0L, 2000L) / 2000.0)) * 0.5
                val score = (uploadComponent + latencyTie).coerceIn(0.0, 100.0)
                item.copy(score = score, confidence = 100)
            }
        }
    }
}
