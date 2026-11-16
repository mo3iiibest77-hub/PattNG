package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class SmartSwitchReason {
    NONE,
    CDN_CONNECT_FAILED,
    CDN_HIGH_LATENCY,
    CDN_STALLED,
}

sealed class FailoverDecision {
    data object Healthy : FailoverDecision()
    data class StrikeRecorded(val strikes: Int) : FailoverDecision()
    data class SwitchToStandard(val reason: SmartSwitchReason) : FailoverDecision()
    data object CooldownActive : FailoverDecision()
}

data class SwitchBannerInfo(
    val epochMs: Long,
    val reason: SmartSwitchReason,
    val transportBadge: String,
    val delayMs: Long,
)

/**
 * Pure hysteresis + degradation evaluation policy for Smart Auto-Failover from CDN -> Standard.
 * Never oscillates back and forth rapidly and requires either consecutive probe failures or
 * sustained high latency with a clearly healthier Standard node before switching.
 */
object SmartFailoverPolicy {
    const val CDN_DEGRADED_LATENCY_MS = 2_400L
    const val DEGRADED_CONSECUTIVE_STRIKES = 2
    const val SWITCH_COOLDOWN_MS = 90_000L
    const val FAILOVER_HYSTERESIS_MS = 400L

    data class TrackerState(
        val highLatencyStrikes: Int = 0,
        val failureStrikes: Int = 0,
        val lastSwitchMs: Long = 0L,
    )

    fun evaluateDegradation(
        state: TrackerState,
        cdnDelayMs: Long,
        nowMs: Long,
        hasStandardCandidate: Boolean,
        smartFailoverArmed: Boolean,
    ): Pair<FailoverDecision, TrackerState> {
        if (!smartFailoverArmed || !hasStandardCandidate) {
            return FailoverDecision.Healthy to state.copy(highLatencyStrikes = 0, failureStrikes = 0)
        }
        if (state.lastSwitchMs > 0L && nowMs > state.lastSwitchMs && (nowMs - state.lastSwitchMs) < SWITCH_COOLDOWN_MS) {
            return FailoverDecision.CooldownActive to state
        }
        if (cdnDelayMs <= 0L) {
            val nextFailures = state.failureStrikes + 1
            if (nextFailures >= DEGRADED_CONSECUTIVE_STRIKES) {
                return FailoverDecision.SwitchToStandard(SmartSwitchReason.CDN_STALLED) to
                    TrackerState(highLatencyStrikes = 0, failureStrikes = 0, lastSwitchMs = nowMs)
            }
            return FailoverDecision.StrikeRecorded(nextFailures) to
                state.copy(failureStrikes = nextFailures, highLatencyStrikes = 0)
        }
        if (cdnDelayMs >= CDN_DEGRADED_LATENCY_MS) {
            val nextHigh = state.highLatencyStrikes + 1
            if (nextHigh >= DEGRADED_CONSECUTIVE_STRIKES) {
                return FailoverDecision.SwitchToStandard(SmartSwitchReason.CDN_HIGH_LATENCY) to
                    TrackerState(highLatencyStrikes = 0, failureStrikes = 0, lastSwitchMs = nowMs)
            }
            return FailoverDecision.StrikeRecorded(nextHigh) to
                state.copy(highLatencyStrikes = nextHigh, failureStrikes = 0)
        }
        return FailoverDecision.Healthy to state.copy(highLatencyStrikes = 0, failureStrikes = 0)
    }

    fun shouldCommitSwitch(
        reason: SmartSwitchReason,
        cdnDelayMs: Long,
        bestStandardDelayMs: Long,
    ): Boolean {
        if (bestStandardDelayMs <= 0L) return false
        return when (reason) {
            SmartSwitchReason.CDN_CONNECT_FAILED,
            SmartSwitchReason.CDN_STALLED -> true
            SmartSwitchReason.CDN_HIGH_LATENCY ->
                cdnDelayMs <= 0L || (bestStandardDelayMs + FAILOVER_HYSTERESIS_MS < cdnDelayMs)
            SmartSwitchReason.NONE -> false
        }
    }
}

/**
 * Multi-process MMKV state shared cleanly between the UI process and `:RunSoLibV2RayDaemon`.
 */
object SmartFailoverStore {
    const val PREF_SMART_AUTO_FAILOVER = "novapulse_smart_auto_failover"
    private const val PREF_SMART_FAILOVER_ARMED = "novapulse_smart_failover_armed"
    private const val PREF_LAST_SWITCH_EPOCH_MS = "novapulse_last_switch_epoch_ms"
    private const val PREF_LAST_SWITCH_REASON = "novapulse_last_switch_reason"
    private const val PREF_LAST_SWITCH_BADGE = "novapulse_last_switch_badge"
    private const val PREF_LAST_SWITCH_DELAY_MS = "novapulse_last_switch_delay_ms"

    fun isAutoFailoverEnabled(): Boolean {
        return MmkvManager.decodeSettingsBool(PREF_SMART_AUTO_FAILOVER, true)
    }

    fun setAutoFailoverEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(PREF_SMART_AUTO_FAILOVER, enabled)
    }

    fun isSmartFailoverArmed(): Boolean {
        return isAutoFailoverEnabled() &&
            MmkvManager.decodeSettingsBool(PREF_SMART_FAILOVER_ARMED, false)
    }

    fun setSmartFailoverArmed(armed: Boolean) {
        MmkvManager.encodeSettings(PREF_SMART_FAILOVER_ARMED, armed)
    }

    fun recordSwitchEvent(
        reason: SmartSwitchReason,
        transportBadge: String,
        delayMs: Long,
        epochMs: Long = System.currentTimeMillis(),
    ) {
        MmkvManager.encodeSettings(PREF_LAST_SWITCH_EPOCH_MS, epochMs.toString())
        MmkvManager.encodeSettings(PREF_LAST_SWITCH_REASON, reason.name)
        MmkvManager.encodeSettings(PREF_LAST_SWITCH_BADGE, transportBadge)
        MmkvManager.encodeSettings(PREF_LAST_SWITCH_DELAY_MS, delayMs.toString())
    }

    fun clearSwitchEvent() {
        MmkvManager.encodeSettings(PREF_LAST_SWITCH_EPOCH_MS, "0")
        MmkvManager.encodeSettings(PREF_LAST_SWITCH_REASON, "")
        MmkvManager.encodeSettings(PREF_LAST_SWITCH_BADGE, "")
        MmkvManager.encodeSettings(PREF_LAST_SWITCH_DELAY_MS, "0")
    }

    fun readLastSwitchBanner(): SwitchBannerInfo? {
        val epoch = MmkvManager.decodeSettingsString(PREF_LAST_SWITCH_EPOCH_MS, "0")
            ?.toLongOrNull() ?: 0L
        if (epoch <= 0L) return null
        val reasonRaw = MmkvManager.decodeSettingsString(PREF_LAST_SWITCH_REASON, "").orEmpty()
        val reason = runCatching { SmartSwitchReason.valueOf(reasonRaw) }
            .getOrDefault(SmartSwitchReason.NONE)
        if (reason == SmartSwitchReason.NONE) return null
        val badge = MmkvManager.decodeSettingsString(PREF_LAST_SWITCH_BADGE, "").orEmpty()
        val delay = MmkvManager.decodeSettingsString(PREF_LAST_SWITCH_DELAY_MS, "0")
            ?.toLongOrNull() ?: 0L
        return SwitchBannerInfo(epochMs = epoch, reason = reason, transportBadge = badge, delayMs = delay)
    }
}

/**
 * Probes Standard (`v2rayNG`: REALITY, Hysteria2, VLESS, VMess, Trojan, Shadowsocks, WireGuard)
 * configs using real Xray outbound delay measurement (`CoreNativeManager.measureOutboundDelay`),
 * completely separate from CDN scanners and TLS mutations.
 */
object StandardConfigProber {
    private const val TAG = "StandardConfigProber"

    data class ProbeResult(
        val guid: String,
        val profile: ProfileItem,
        val delayMs: Long,
        val transportBadge: String,
    )

    fun hasStandardCandidates(excludeGuid: String? = null): Boolean {
        return MmkvManager.decodeAllServerList().any { guid ->
            guid != excludeGuid &&
                ConfigPipelineClassifier.isStandardEligible(MmkvManager.decodeServerConfig(guid))
        }
    }

    suspend fun probeBestStandard(
        context: Context,
        excludeGuid: String? = null,
    ): ProbeResult? = coroutineScope {
        val appCtx = context.applicationContext
        CoreNativeManager.initCoreEnv(appCtx)

        val candidates = MmkvManager.decodeAllServerList()
            .asSequence()
            .filter { it != excludeGuid }
            .mapNotNull { guid ->
                val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                if (!ConfigPipelineClassifier.isStandardEligible(profile)) return@mapNotNull null
                guid to profile
            }
            .sortedBy { ConfigPipelineClassifier.protocolPriorityRank(it.second) }
            .toList()

        if (candidates.isEmpty()) return@coroutineScope null

        val semaphore = Semaphore(2)
        val primaryTestUrl = SettingsManager.getDelayTestUrl()
        val fallbackTestUrl = SettingsManager.getDelayTestUrl(true)

        val results = candidates.map { (guid, profile) ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val cfg = CoreConfigManager.getV2rayConfig4Speedtest(appCtx, guid)
                    if (!cfg.status || cfg.content.isBlank()) {
                        MmkvManager.encodeServerTestDelayMillis(guid, -1L)
                        return@withPermit null
                    }
                    var delay = CoreNativeManager.measureOutboundDelay(cfg.content, primaryTestUrl)
                    if (delay <= 0L && fallbackTestUrl != primaryTestUrl) {
                        delay = CoreNativeManager.measureOutboundDelay(cfg.content, fallbackTestUrl)
                    }
                    MmkvManager.encodeServerTestDelayMillis(guid, delay)
                    if (delay > 0L) {
                        ProbeResult(
                            guid = guid,
                            profile = profile,
                            delayMs = delay,
                            transportBadge = ConfigPipelineClassifier.safeTransportBadge(profile),
                        )
                    } else {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()

        val best = results.minByOrNull { it.delayMs }
        if (best != null) {
            LogUtil.i(TAG, "Standard probe selected ${best.guid} (${best.transportBadge}) at ${best.delayMs}ms")
        }
        best
    }
}
