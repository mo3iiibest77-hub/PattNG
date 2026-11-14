package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.ProfileItem

/** Switch decision produced by [AutoOptimizationPolicy]. */
sealed interface OptimizationDecision {
    data class Switch(val reason: SwitchReason) : OptimizationDecision
    data class KeepCurrent(val reason: KeepReason) : OptimizationDecision
}

enum class SwitchReason {
    CURRENT_UNHEALTHY,
    UPGRADE_MEASURED,
    MEANINGFUL_SPEED_GAIN,
}

enum class KeepReason {
    NO_VALID_CANDIDATE,
    SAME_IP_BEST,
    WITHIN_HYSTERESIS,
}

/** Observed quality of the currently active edge IP before evaluating a switch. */
data class CurrentEdgeHealth(
    val ip: String?,
    val healthy: Boolean,
    val uploadKBps: Long = 0L,
    val latencyMs: Long = -1L,
)

/** Candidate evaluated through the reference latency + traffic speed gate. */
data class MeasuredEdgeCandidate(
    val ip: String,
    val passedGate: Boolean,
    val uploadKBps: Long,
    val latencyMs: Long,
)

/**
 * Controlled IP switching and periodic interval rules (2h..3h configurable, default 2h).
 *
 * Avoids switching cleanly working IPs on tiny measurement noise; switches immediately when the
 * current edge fails health checks or when a candidate clears the reference gate with meaningful
 * upload speed improvement.
 */
object AutoOptimizationPolicy {

    const val DEFAULT_INTERVAL_HOURS = 2
    const val MIN_INTERVAL_HOURS = 2
    const val MAX_INTERVAL_HOURS = 3

    const val MIN_IMPROVEMENT_RATIO = 1.15
    const val MIN_IMPROVEMENT_DELTA_KBPS = 50L

    fun clampIntervalHours(hours: Int): Int =
        hours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)

    fun evaluate(
        current: CurrentEdgeHealth,
        candidate: MeasuredEdgeCandidate?,
        minRatio: Double = MIN_IMPROVEMENT_RATIO,
        minDeltaKBps: Long = MIN_IMPROVEMENT_DELTA_KBPS,
    ): OptimizationDecision {
        if (candidate == null || !candidate.passedGate || !IpAddress.isIpv4(candidate.ip)) {
            return OptimizationDecision.KeepCurrent(KeepReason.NO_VALID_CANDIDATE)
        }
        if (current.ip.isNullOrBlank()) {
            return OptimizationDecision.Switch(SwitchReason.UPGRADE_MEASURED)
        }
        if (!current.healthy) {
            return if (candidate.ip == current.ip) {
                OptimizationDecision.KeepCurrent(KeepReason.SAME_IP_BEST)
            } else {
                OptimizationDecision.Switch(SwitchReason.CURRENT_UNHEALTHY)
            }
        }
        if (candidate.ip == current.ip) {
            return OptimizationDecision.KeepCurrent(KeepReason.SAME_IP_BEST)
        }
        if (current.uploadKBps <= 0L) {
            return OptimizationDecision.Switch(SwitchReason.UPGRADE_MEASURED)
        }
        val delta = candidate.uploadKBps - current.uploadKBps
        val ratioOk = candidate.uploadKBps.toDouble() >= current.uploadKBps.toDouble() * minRatio
        return if (ratioOk && delta >= minDeltaKBps) {
            OptimizationDecision.Switch(SwitchReason.MEANINGFUL_SPEED_GAIN)
        } else {
            OptimizationDecision.KeepCurrent(KeepReason.WITHIN_HYSTERESIS)
        }
    }
}

/**
 * Snapshot of previous edge values across affected profiles so an unsuccessful IP switch can roll
 * back deterministically without losing SNI, Host, Path, TLS, or origin server fields.
 */
data class EdgeRollbackSnapshot(
    val previousSelection: IpSelection?,
    val previousEdgesByGuid: Map<String, String?>,
) {
    fun restoreTo(profilesByGuid: Map<String, ProfileItem>) {
        previousEdgesByGuid.forEach { (guid, prevEdge) ->
            profilesByGuid[guid]?.qsEdgeIp = prevEdge
        }
    }
}
