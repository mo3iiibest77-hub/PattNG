package com.v2ray.ang.ui.qsn.domain

/**
 * Which measured candidate the flow offers when the ranker has nothing.
 *
 * A finished scan is not a failed connection. When the ranker has no winner
 * because the scan timed out, or because the winning result never reached it,
 * the candidates that did answer are still usable and the user is asked to
 * choose one. Only a batch where nothing answered at all has no suggestion.
 *
 * Pure on purpose: this is the rule that decides whether the flow continues or
 * reports "no working IP", so it is tested without Android.
 */
object CandidateSelection {

    /**
     * @param winnerIp the ranker's winner, or null when it had none.
     * @param measured every candidate that produced a result.
     * @return the address to highlight, or null when nothing answered.
     */
    fun suggestion(winnerIp: String?, measured: List<ScanCandidate>): String? {
        if (!winnerIp.isNullOrBlank()) return winnerIp
        return measured
            .asSequence()
            .filter { it.answered }
            .minWithOrNull(
                compareBy<ScanCandidate> { it.pingMs ?: Long.MAX_VALUE }.thenBy { it.ip },
            )
            ?.ip
    }

    /** True when the flow can offer a choice at all. */
    fun canContinue(winnerIp: String?, measured: List<ScanCandidate>, earlyPick: String?): Boolean =
        earlyPick != null || suggestion(winnerIp, measured) != null
}
