package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class SmartStep {
    Idle,
    RedeemingAccess,
    RefreshingSubscription,
    SelectingProfile,
    DetectingCdnViaPing,
    ScanningBestIp,
    ApplyingBestIp,
    StartingVpn,
    Connected,
    Error
}

data class SmartFlowState(
    val busy: Boolean = false,
    val step: SmartStep = SmartStep.Idle,
    val activeGuid: String? = null,
    val activeRemarks: String? = null,
    val originDomain: String? = null,
    val detectedCdn: CdnType = CdnType.UNKNOWN,
    val domainPingIp: String? = null,
    val domainPingRttMs: Long? = null,
    val scanDone: Int = 0,
    val scanTotal: Int = 30,
    val bestIp: String? = null,
    val bestUploadKBps: Long = 0L,
    val bestLatencyMs: Long = -1L,
    val errorMessage: String? = null,
)

/**
 * End-to-end Smart Connect pipeline layered directly on top of stage-1's reference engine:
 * 1) Redeem license or import direct subscription link (with quota parsing)
 * 2) Pick eligible VLESS+TLS (`xhttp`/`ws`) config
 * 3) Ping/resolve config domain -> auto-classify [CdnType.CLOUDFLARE] vs [CdnType.CLOUDFRONT]
 * 4) Run matching untouched September 8 IP scanner -> [CloudflareScanner.applyBestIp]
 * 5) Hand off to VPN permission + [com.v2ray.ang.core.LauncherManager.startService]
 * 6) Trigger post-first-connect discovery and schedule 2-hour background optimization.
 */
object SmartConnectCoordinator {

    private const val TAG = "SmartConnectCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    private val _state = MutableStateFlow(SmartFlowState())
    val state: StateFlow<SmartFlowState> = _state.asStateFlow()

    fun redeemInputOnly(
        context: Context,
        rawInput: String,
        remarksHint: String = "QuietStorm",
        onComplete: (Result<Int>) -> Unit,
    ) {
        scope.launch {
            val result = performRedeemAndImport(context.applicationContext, rawInput, remarksHint)
            onComplete(result)
        }
    }

    /**
     * Prepares the best config, detects CDN via domain Ping, scans optimal edge IP with the
     * untouched September 8 engine, applies `bestIp`, and invokes [onReadyToStartVpn] on Main
     * so the host Activity can check `VpnService.prepare` and call `LauncherManager.startService`.
     */
    fun runPrepareAndScan(
        context: Context,
        rawActivationInput: String? = null,
        explicitGuid: String? = null,
        forcedCdn: CdnType? = null,
        onReadyToStartVpn: (guid: String, state: SmartFlowState) -> Unit,
    ) {
        activeJob?.cancel()
        val appContext = context.applicationContext
        activeJob = scope.launch {
            try {
                // 1) Optional license / subscription import
                val input = rawActivationInput?.trim().orEmpty()
                if (input.isNotEmpty() && MmkvManager.decodeAllServerList().isEmpty()) {
                    _state.value = SmartFlowState(busy = true, step = SmartStep.RedeemingAccess)
                    performRedeemAndImport(appContext, input, "QuietStorm").getOrThrow()
                } else if (MmkvManager.decodeAllServerList().isEmpty() &&
                    MmkvManager.decodeSubscriptions().isNotEmpty()
                ) {
                    _state.value = SmartFlowState(busy = true, step = SmartStep.RefreshingSubscription)
                    refreshAllSubscriptionsWithQuota(appContext)
                }

                // 2) Select target config
                _state.value = _state.value.copy(busy = true, step = SmartStep.SelectingProfile, errorMessage = null)
                val targetGuid = resolveTargetGuid(explicitGuid)
                    ?: error("No valid proxy profile found. Add a subscription or config first.")
                val profile = MmkvManager.decodeServerConfig(targetGuid)
                    ?: error("Selected configuration is missing.")
                OriginDomainStore.rememberIfDomain(targetGuid, profile)
                MmkvManager.setSelectServer(targetGuid)

                // 3) Detect Cloudflare vs CloudFront by pinging origin domain
                _state.value = _state.value.copy(
                    step = SmartStep.DetectingCdnViaPing,
                    activeGuid = targetGuid,
                    activeRemarks = profile.remarks,
                    originDomain = OriginDomainStore.getOriginDomain(targetGuid, profile),
                )
                val detection = if (forcedCdn != null && forcedCdn != CdnType.UNKNOWN) {
                    CdnDetectionResult(
                        domain = OriginDomainStore.getOriginDomain(targetGuid, profile).orEmpty(),
                        resolvedIp = null,
                        pingRttMs = null,
                        cdnType = forcedCdn,
                        reason = "Manual CDN override"
                    )
                } else {
                    CdnPingClassifier.detectForProfile(appContext, targetGuid, profile)
                }
                val resolvedCdn = if (detection.cdnType == CdnType.UNKNOWN) {
                    CdnType.CLOUDFLARE
                } else {
                    detection.cdnType
                }

                _state.value = _state.value.copy(
                    step = SmartStep.ScanningBestIp,
                    detectedCdn = resolvedCdn,
                    domainPingIp = detection.resolvedIp,
                    domainPingRttMs = detection.pingRttMs,
                    scanDone = 0,
                    scanTotal = 30,
                )

                // 4) Run matching September 8 scanner core
                val best = if (resolvedCdn == CdnType.CLOUDFRONT) {
                    awaitCloudFrontScan(appContext, targetGuid)
                } else {
                    awaitCloudflareScan(appContext, targetGuid)
                }

                if (best != null) {
                    _state.value = _state.value.copy(
                        step = SmartStep.ApplyingBestIp,
                        bestIp = best.ip,
                        bestUploadKBps = best.uploadKBps,
                        bestLatencyMs = best.latencyMs,
                    )
                    CloudflareScanner.applyBestIp(targetGuid, best.ip)
                } else if (profile.server.isNullOrBlank()) {
                    error("No reachable CDN IP found during scan")
                }

                val readyState = _state.value.copy(
                    busy = false,
                    step = SmartStep.StartingVpn,
                )
                _state.value = readyState
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onReadyToStartVpn(targetGuid, readyState)
                }
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(busy = false, step = SmartStep.Idle)
            } catch (e: Exception) {
                LogUtil.e(TAG, "Smart flow failed: ${e.message}", e)
                _state.value = _state.value.copy(
                    busy = false,
                    step = SmartStep.Error,
                    errorMessage = e.message ?: "Smart connection failed",
                )
            }
        }
    }

    /**
     * Called when the VPN daemon confirms active running state (`MSG_STATE_RUNNING` /
     * `MSG_STATE_START_SUCCESS`). Schedules 2-hour periodic optimization and starts first-connect
     * sequential CloudFront -> Cloudflare ISP discovery on physical uplink.
     */
    fun notifyVpnConnected(context: Context, guid: String?) {
        val targetGuid = guid ?: MmkvManager.getSelectServer()
        _state.value = _state.value.copy(
            busy = false,
            step = SmartStep.Connected,
            activeGuid = targetGuid,
            errorMessage = null,
        )
        SmartOptimizeWorker.schedule(context)
        if (!targetGuid.isNullOrBlank()) {
            val profile = MmkvManager.decodeServerConfig(targetGuid)
            if (AutoConfigFilter.isCdnScanEligible(profile)) {
                PostConnectDiscoveryRunner.maybeRunAfterFirstConnect(context, targetGuid)
            }
        }
    }

    fun cancelActiveFlow() {
        activeJob?.cancel()
        CloudflareScanner.cancel()
        CloudFrontScanner.cancel()
        _state.value = _state.value.copy(busy = false, step = SmartStep.Idle)
    }

    fun refreshAllSubscriptionsWithQuota(context: Context): Int {
        val subs = MmkvManager.decodeSubscriptions()
        var totalConfigs = 0
        for (subCache in subs) {
            if (!subCache.subscription.enabled) continue
            val res = AngConfigManager.updateConfigViaSub(subCache)
            totalConfigs += res.configCount
            SubscriptionQuotaStore.fetchAndSaveHeader(
                subId = subCache.guid,
                url = subCache.subscription.url,
                userAgent = subCache.subscription.userAgent
            )
        }
        return totalConfigs
    }

    private fun performRedeemAndImport(
        context: Context,
        rawInput: String,
        remarksHint: String,
    ): Result<Int> {
        val deviceId = runCatching { Utils.getDeviceIdForXUDPBaseKey() }.getOrDefault("")
        return when (val redeemed = LicenseBackendClient.redeem(rawInput, remarksHint, deviceId)) {
            is LicenseResolutionResult.BackendEndpointRequired ->
                Result.failure(
                    IllegalStateException(
                        "License saved (${redeemed.licenseKey}). Enter a direct subscription URL or configure Panel Backend URL in Settings."
                    )
                )

            is LicenseResolutionResult.Failure ->
                Result.failure(IllegalStateException(redeemed.reason))

            is LicenseResolutionResult.ReadySubscription -> {
                val existing = MmkvManager.decodeSubscriptions()
                    .firstOrNull { it.subscription.url.trim() == redeemed.subscriptionUrl.trim() }
                val subId = existing?.guid ?: Utils.getUuid()
                val item = existing?.subscription?.apply {
                    enabled = true
                    if (remarks.isBlank()) remarks = redeemed.remarks
                } ?: SubscriptionItem(
                    remarks = redeemed.remarks,
                    url = redeemed.subscriptionUrl,
                    enabled = true,
                )
                MmkvManager.encodeSubscription(subId, item)
                val cache = com.v2ray.ang.dto.entities.SubscriptionCache(subId, item)
                val updateRes = AngConfigManager.updateConfigViaSub(cache)
                if (redeemed.quota != null) {
                    SubscriptionQuotaStore.save(subId, redeemed.quota)
                } else {
                    SubscriptionQuotaStore.fetchAndSaveHeader(subId, item.url, item.userAgent)
                }
                if (updateRes.configCount > 0) {
                    Result.success(updateRes.configCount)
                } else {
                    Result.failure(IllegalStateException("Subscription imported 0 valid configs"))
                }
            }
        }
    }

    private fun resolveTargetGuid(explicitGuid: String?): String? {
        val selected = explicitGuid ?: MmkvManager.getSelectServer()
        val allGuids = MmkvManager.decodeAllServerList()
        val pairs: List<Pair<String, ProfileItem?>> = allGuids.map { it to MmkvManager.decodeServerConfig(it) }
        return AutoConfigFilter.pickBestConnectGuid(selected, pairs)
    }

    private suspend fun awaitCloudflareScan(
        context: Context,
        guid: String,
    ): CandidateResult? = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { CloudflareScanner.cancel() }
        CloudflareScanner.scan(
            context = context,
            guid = guid,
            candidates = emptyList(),
            callback = object : ScanCallback {
                override fun onProgress(result: CandidateResult, done: Int, total: Int) {
                    _state.value = _state.value.copy(
                        scanDone = done,
                        scanTotal = total.coerceAtLeast(1),
                        bestIp = if (result.isSuccess && result.uploadKBps > _state.value.bestUploadKBps) {
                            result.ip
                        } else _state.value.bestIp,
                        bestUploadKBps = maxOf(_state.value.bestUploadKBps, if (result.isSuccess) result.uploadKBps else 0L),
                    )
                }

                override fun onFinish(best: CandidateResult?) {
                    if (cont.isActive) cont.resume(best)
                }

                override fun onCancelled() {
                    if (cont.isActive) cont.resume(null)
                }
            }
        )
    }

    private suspend fun awaitCloudFrontScan(
        context: Context,
        guid: String,
    ): CandidateResult? = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { CloudFrontScanner.cancel() }
        CloudFrontScanner.scan(
            context = context,
            guid = guid,
            candidates = emptyList(),
            callback = object : ScanCallback {
                override fun onProgress(result: CandidateResult, done: Int, total: Int) {
                    _state.value = _state.value.copy(
                        scanDone = done,
                        scanTotal = total.coerceAtLeast(1),
                        bestIp = if (result.isSuccess && result.uploadKBps > _state.value.bestUploadKBps) {
                            result.ip
                        } else _state.value.bestIp,
                        bestUploadKBps = maxOf(_state.value.bestUploadKBps, if (result.isSuccess) result.uploadKBps else 0L),
                    )
                }

                override fun onFinish(best: CandidateResult?) {
                    if (cont.isActive) cont.resume(best)
                }

                override fun onCancelled() {
                    if (cont.isActive) cont.resume(null)
                }
            }
        )
    }
}
