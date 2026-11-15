package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
 * End-to-end Smart Connect pipeline layered directly on top of the September 8 engine:
 * 1) Redeem license code (subscription URLs rejected) and fetch server-filtered CDN configs + quota
 * 2) Pick eligible VLESS+TLS (`xhttp`/`ws`/`splithttp`) config
 * 3) On first connect (or explicit rescan), detect Cloudflare vs CloudFront via DoH/Ping and run
 *    the untouched September 8 IP scanner -> [CloudflareScanner.applyBestIp]
 * 4) On subsequent reconnects when a scanned edge IP is already applied, connect immediately
 *    without re-scanning every toggle; let [SmartOptimizeWorker] refresh IP silently every 2h
 * 5) Trigger one-time-forever provider-scoped Discovery and schedule 2-hour background optimization.
 */
object SmartConnectCoordinator {

    private const val TAG = "SmartConnectCoordinator"
    private const val PREF_SCANNED_GUID_PREFIX = "np_scanned_edge_ready_"
    private const val PREF_USER_EXPLICIT_SERVER = "np_user_explicit_server_choice"
    private const val RECONNECT_CONFIRM_TIMEOUT_MS = 14_000L

    private data class PendingOptimization(
        val guid: String,
        val forcedCdn: CdnType?,
        val previousServer: String?,
        val initialSni: String?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var postConnectOptimizeJob: Job? = null
    @Volatile
    private var pendingOptimization: PendingOptimization? = null
    @Volatile
    private var reconnectDeferred: CompletableDeferred<Boolean>? = null

    private val _state = MutableStateFlow(SmartFlowState())
    val state: StateFlow<SmartFlowState> = _state.asStateFlow()

    fun hasSavedEdgeIp(guid: String, profile: ProfileItem): Boolean {
        val flag = MmkvManager.decodeSettingsBool(PREF_SCANNED_GUID_PREFIX + guid, false)
        val srv = profile.server.orEmpty().trim()
        return flag && CloudflareSeedRanges.ipv4ToLong(srv) != null
    }

    private fun markEdgeIpScanned(guid: String, ready: Boolean = true) {
        MmkvManager.encodeSettings(PREF_SCANNED_GUID_PREFIX + guid, ready)
    }

    fun markExplicitServerSelected(explicit: Boolean) {
        MmkvManager.encodeSettings(PREF_USER_EXPLICIT_SERVER, explicit)
    }

    fun isExplicitServerSelected(): Boolean =
        MmkvManager.decodeSettingsBool(PREF_USER_EXPLICIT_SERVER, false)

    fun redeemInputOnly(
        context: Context,
        rawInput: String,
        remarksHint: String = "NovaPulse",
        onComplete: (Result<Int>) -> Unit,
    ) {
        scope.launch {
            val result = performRedeemAndImport(context.applicationContext, rawInput, remarksHint)
            onComplete(result)
        }
    }

    fun refreshQuotaAndSubscription(
        context: Context,
        onComplete: (Result<Int>) -> Unit,
    ) {
        scope.launch {
            val appCtx = context.applicationContext
            val savedKey = LicenseBackendClient.getSavedLicenseKey()
            val result = if (savedKey.isNotEmpty()) {
                performRedeemAndImport(appCtx, savedKey, "NovaPulse", isStatusRefresh = true)
            } else {
                Result.success(refreshAllSubscriptionsWithQuota(appCtx))
            }
            onComplete(result)
        }
    }

    /**
     * Connects [explicitGuid] cleanly:
     * - If [forceRescan] is false and the profile already has a verified scanned edge IPv4,
     *   launches VPN immediately without redundant re-scanning.
     * - Otherwise detects CDN via DoH/Ping, runs September 8 scanner, saves `bestIp`, and starts VPN.
     */
    fun runPrepareAndScan(
        context: Context,
        rawActivationInput: String? = null,
        explicitGuid: String? = null,
        forcedCdn: CdnType? = null,
        forceRescan: Boolean = false,
        onReadyToStartVpn: (guid: String, state: SmartFlowState) -> Unit,
    ) {
        activeJob?.cancel()
        val appContext = context.applicationContext
        activeJob = scope.launch {
            try {
                // 1) Optional license import if server list empty
                val input = rawActivationInput?.trim().orEmpty()
                if (input.isNotEmpty() && MmkvManager.decodeAllServerList().isEmpty()) {
                    _state.value = SmartFlowState(busy = true, step = SmartStep.RedeemingAccess)
                    performRedeemAndImport(appContext, input, "NovaPulse").getOrThrow()
                } else if (MmkvManager.decodeAllServerList().isEmpty() &&
                    MmkvManager.decodeSubscriptions().isNotEmpty()
                ) {
                    _state.value = SmartFlowState(busy = true, step = SmartStep.RefreshingSubscription)
                    refreshAllSubscriptionsWithQuota(appContext)
                }

                // 2) Apply Cloudflare-only hardening & pick Cloudflare-first CDN config
                CloudflareConfigEnricher.enrichAllStoredCloudflareProfiles(appContext)
                _state.value = _state.value.copy(busy = true, step = SmartStep.SelectingProfile, errorMessage = null)
                val honorExplicit = !explicitGuid.isNullOrBlank() || isExplicitServerSelected()
                val targetGuid = resolveTargetGuid(appContext, explicitGuid, honorExplicit)
                    ?: error("هیچ کانفیگ CDN معتبری یافت نشد.")
                var profile = MmkvManager.decodeServerConfig(targetGuid)
                    ?: error("اطلاعات کانفیگ انتخاب‌شده یافت نشد.")
                OriginDomainStore.rememberIfDomain(targetGuid, profile)
                MmkvManager.setSelectServer(targetGuid)

                val cfCidrs = CdnPingClassifier.getAllCloudflareCidrs(appContext)
                val frontCidrs = ProviderRangeStore.getEntries(appContext, CdnType.CLOUDFRONT)
                    .map { it.cidr }
                    .ifEmpty { ProviderRangeStore.loadBundledCloudFrontCidrs(appContext) }
                val originDomain = OriginDomainStore.getOriginDomain(targetGuid, profile).orEmpty()
                val cachedCdn = CdnPingClassifier.CdnDetectionStore.getSavedCdnType(targetGuid)

                val quickCdn = forcedCdn?.takeIf { it != CdnType.UNKNOWN }
                    ?: cachedCdn
                    ?: if (CloudflareConfigEnricher.isAmazonOrCloudFront(profile, originDomain, frontCidrs, cachedCdn)) {
                        CdnType.CLOUDFRONT
                    } else {
                        CdnPingClassifier.inferQuickCdnType(profile, originDomain, frontCidrs, cfCidrs, cachedCdn)
                            .let { if (it == CdnType.UNKNOWN) CdnType.CLOUDFLARE else it }
                    }

                // Fast path: already scanned earlier and user simply toggled reconnect
                if (!forceRescan && hasSavedEdgeIp(targetGuid, profile)) {
                    pendingOptimization = null
                    val fastState = _state.value.copy(
                        busy = false,
                        step = SmartStep.StartingVpn,
                        activeGuid = targetGuid,
                        activeRemarks = profile.remarks,
                        originDomain = originDomain,
                        detectedCdn = quickCdn,
                        bestIp = profile.server,
                    )
                    _state.value = fastState
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        onReadyToStartVpn(targetGuid, fastState)
                    }
                    return@launch
                }

                // Fast first-connect path: put TLS Certification SNI into server address, keep
                // TLS SNI/Host/XHTTP and CF-only CipherSuites/FinalMask intact, connect VPN
                // IMMEDIATELY, and run 30-IP scan + controlled reconnect right after VPN comes up.
                val previousServerBeforeSni = profile.server
                val preparedProfile = CloudflareConfigEnricher.prepareFirstConnectWithTlsSni(
                    guid = targetGuid,
                    profile = profile,
                    cloudFrontCidrs = frontCidrs,
                ) ?: error("کانفیگ انتخاب‌شده فاقد آدرس یا TLS SNI معتبر است.")
                profile = preparedProfile

                pendingOptimization = PendingOptimization(
                    guid = targetGuid,
                    forcedCdn = forcedCdn,
                    previousServer = previousServerBeforeSni,
                    initialSni = profile.sni,
                )

                val instantFirstState = _state.value.copy(
                    busy = false,
                    step = SmartStep.StartingVpn,
                    activeGuid = targetGuid,
                    activeRemarks = profile.remarks,
                    originDomain = originDomain.ifBlank { profile.sni.orEmpty() },
                    detectedCdn = quickCdn,
                    bestIp = profile.server,
                    scanDone = 0,
                    scanTotal = 30,
                )
                _state.value = instantFirstState
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onReadyToStartVpn(targetGuid, instantFirstState)
                }
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(busy = false, step = SmartStep.Idle)
            } catch (e: Exception) {
                pendingOptimization = null
                LogUtil.e(TAG, "Smart flow failed: ${e.message}", e)
                _state.value = _state.value.copy(
                    busy = false,
                    step = SmartStep.Error,
                    errorMessage = e.message ?: "خطا در آماده‌سازی اتصال",
                )
            }
        }
    }

    fun notifyVpnStartFailed(errorMessage: String?) {
        val waiting = reconnectDeferred
        if (waiting != null && waiting.isActive) {
            waiting.complete(false)
            return
        }
        pendingOptimization = null
        postConnectOptimizeJob?.cancel()
        _state.value = _state.value.copy(
            busy = false,
            step = SmartStep.Error,
            errorMessage = errorMessage?.takeIf { it.isNotBlank() } ?: "خطا در شروع سرویس VPN",
        )
    }

    /**
     * Called when VPN daemon confirms active running state.
     * - Completes any controlled-reconnect verification handshake cleanly.
     * - If initial connect used TLS SNI without a saved scanned IP, runs background 30-IP scan
     *   over physical internet while the VPN remains active, then applies best IP + controlled
     *   reconnect with automatic rollback if verification fails.
     * - Schedules 2h periodic optimization and triggers one-time-forever Discovery afterward.
     */
    fun notifyVpnConnected(context: Context, guid: String?) {
        val targetGuid = guid ?: MmkvManager.getSelectServer()
        val waitingReconnect = reconnectDeferred
        if (waitingReconnect != null && waitingReconnect.isActive) {
            waitingReconnect.complete(true)
            _state.value = _state.value.copy(
                busy = false,
                step = SmartStep.Connected,
                activeGuid = targetGuid,
                errorMessage = null,
            )
            return
        }

        val prev = _state.value
        _state.value = prev.copy(
            busy = false,
            step = SmartStep.Connected,
            activeGuid = targetGuid,
            errorMessage = null,
        )
        SmartOptimizeWorker.schedule(context)
        if (targetGuid.isNullOrBlank()) return

        val pending = pendingOptimization?.takeIf { it.guid == targetGuid }
        if (pending != null) {
            pendingOptimization = null
        } else if (postConnectOptimizeJob?.isActive == true) {
            return
        }

        val appCtx = context.applicationContext
        postConnectOptimizeJob = scope.launch {
            try {
                val initialProfile = MmkvManager.decodeServerConfig(targetGuid) ?: return@launch
                if (!AutoConfigFilter.isCdnScanEligible(initialProfile)) return@launch

                val cfCidrs = CdnPingClassifier.getAllCloudflareCidrs(appCtx)
                val frontCidrs = ProviderRangeStore.getEntries(appCtx, CdnType.CLOUDFRONT)
                    .map { it.cidr }
                    .ifEmpty { ProviderRangeStore.loadBundledCloudFrontCidrs(appCtx) }

                var resolvedCdn = prev.detectedCdn.takeIf { it != CdnType.UNKNOWN }
                    ?: CdnPingClassifier.inferQuickCdnType(
                        initialProfile,
                        OriginDomainStore.getOriginDomain(targetGuid, initialProfile) ?: initialProfile.server.orEmpty(),
                        frontCidrs,
                        cfCidrs
                    ).let { if (it == CdnType.UNKNOWN) CdnType.CLOUDFLARE else it }

                if (pending != null) {
                    delay(1200L)
                    if (!PhysicalNetworkGuard.hasPhysicalInternet(appCtx)) {
                        LogUtil.w(TAG, "Post-connect scan deferred: no physical internet available")
                    } else {
                        val detection = if (pending.forcedCdn != null && pending.forcedCdn != CdnType.UNKNOWN) {
                            CdnDetectionResult(
                                domain = OriginDomainStore.getOriginDomain(targetGuid, initialProfile).orEmpty(),
                                resolvedIp = null,
                                pingRttMs = null,
                                cdnType = pending.forcedCdn,
                                reason = "انتخاب دستی CDN"
                            )
                        } else {
                            CdnPingClassifier.detectForProfile(appCtx, targetGuid, initialProfile)
                        }
                        resolvedCdn = if (detection.cdnType == CdnType.UNKNOWN) CdnType.CLOUDFLARE else detection.cdnType

                        _state.value = _state.value.copy(
                            step = SmartStep.ScanningBestIp,
                            detectedCdn = resolvedCdn,
                            domainPingIp = detection.resolvedIp,
                            domainPingRttMs = detection.pingRttMs,
                            scanDone = 0,
                            scanTotal = 30,
                        )

                        val best = if (resolvedCdn == CdnType.CLOUDFRONT) {
                            awaitCloudFrontScan(appCtx, targetGuid)
                        } else {
                            awaitCloudflareScan(appCtx, targetGuid)
                        }

                        if (best != null && best.isSuccess) {
                            val currentBeforeSwitch = MmkvManager.decodeServerConfig(targetGuid)?.server
                                ?: pending.initialSni
                                ?: pending.previousServer

                            _state.value = _state.value.copy(
                                step = SmartStep.ApplyingBestIp,
                                bestIp = best.ip,
                                bestUploadKBps = best.uploadKBps,
                                bestLatencyMs = best.latencyMs,
                            )
                            CloudflareScanner.applyBestIp(targetGuid, best.ip)
                            markEdgeIpScanned(targetGuid, true)

                            val waitConfirm = CompletableDeferred<Boolean>()
                            reconnectDeferred = waitConfirm
                            LauncherManager.restartService(appCtx)
                            val confirmed = withTimeoutOrNull(RECONNECT_CONFIRM_TIMEOUT_MS) {
                                waitConfirm.await()
                            } ?: false
                            if (reconnectDeferred === waitConfirm) {
                                reconnectDeferred = null
                            }

                            if (!confirmed) {
                                LogUtil.w(TAG, "Controlled reconnect to ${best.ip} failed/timed out; rolling back to $currentBeforeSwitch")
                                if (!currentBeforeSwitch.isNullOrBlank()) {
                                    CloudflareScanner.applyBestIp(targetGuid, currentBeforeSwitch)
                                }
                                markEdgeIpScanned(targetGuid, false)
                                LauncherManager.restartService(appCtx)
                            }
                        }

                        _state.value = _state.value.copy(
                            busy = false,
                            step = SmartStep.Connected,
                        )
                    }
                }

                val refreshedProfile = MmkvManager.decodeServerConfig(targetGuid) ?: initialProfile
                val allProviders = MmkvManager.decodeAllServerList().mapNotNull { g ->
                    val p = MmkvManager.decodeServerConfig(g) ?: return@mapNotNull null
                    val dom = OriginDomainStore.getOriginDomain(g, p) ?: p.server.orEmpty()
                    CdnPingClassifier.inferQuickCdnType(p, dom, frontCidrs, cfCidrs)
                }
                val activeProvider = resolvedCdn.takeIf { it != CdnType.UNKNOWN }
                    ?: CdnPingClassifier.inferQuickCdnType(
                        refreshedProfile,
                        OriginDomainStore.getOriginDomain(targetGuid, refreshedProfile) ?: refreshedProfile.server.orEmpty(),
                        frontCidrs,
                        cfCidrs
                    ).let { if (it == CdnType.UNKNOWN) CdnType.CLOUDFLARE else it }

                PostConnectDiscoveryRunner.maybeRunAfterFirstConnect(
                    context = appCtx,
                    guid = targetGuid,
                    activeProvider = activeProvider,
                    subscriptionProviders = allProviders,
                )
            } catch (_: CancellationException) {
                reconnectDeferred?.cancel()
                reconnectDeferred = null
            } catch (e: Exception) {
                LogUtil.e(TAG, "Post-connect optimization failed: ${e.message}", e)
                _state.value = _state.value.copy(busy = false, step = SmartStep.Connected)
            }
        }
    }

    fun cancelActiveFlow() {
        activeJob?.cancel()
        postConnectOptimizeJob?.cancel()
        pendingOptimization = null
        reconnectDeferred?.takeIf { it.isActive }?.complete(false)
        reconnectDeferred = null
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
        purgeNonCdnConfigs()
        CdnPingClassifier.classifyAllProfilesIfNeeded(context.applicationContext, forceRefresh = false)
        CloudflareConfigEnricher.enrichAllStoredCloudflareProfiles(context.applicationContext)
        ensureCloudflarePrioritySelection(context.applicationContext)
        return totalConfigs
    }

    private fun ensureCloudflarePrioritySelection(context: Context) {
        val selected = MmkvManager.getSelectServer()
        val honorExplicit = isExplicitServerSelected()
        val bestGuid = resolveTargetGuid(context, selected, honorExplicitSelection = honorExplicit)
        if (!bestGuid.isNullOrBlank() && bestGuid != selected) {
            MmkvManager.setSelectServer(bestGuid)
        }
    }

    private fun purgeNonCdnConfigs() {
        for (guid in MmkvManager.decodeAllServerList()) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (!AutoConfigFilter.isCdnScanEligible(profile)) {
                MmkvManager.removeServer(guid)
            }
        }
    }

    private fun performRedeemAndImport(
        context: Context,
        rawInput: String,
        remarksHint: String,
        isStatusRefresh: Boolean = false,
    ): Result<Int> {
        val deviceId = runCatching { DeviceIdentityManager.getDeviceId(context) }.getOrDefault("")
        val redeemed = if (isStatusRefresh) {
            LicenseBackendClient.refreshStatus(rawInput, deviceId, remarksHint)
        } else {
            LicenseBackendClient.redeem(rawInput, remarksHint, deviceId)
        }
        return when (redeemed) {
            is LicenseResolutionResult.BackendEndpointRequired ->
                Result.failure(IllegalStateException("آدرس سرور لایسنس تنظیم نشده است."))

            is LicenseResolutionResult.Failure ->
                Result.failure(IllegalStateException(redeemed.reason))

            is LicenseResolutionResult.ReadySubscription -> {
                val existing = MmkvManager.decodeSubscriptions()
                    .firstOrNull { it.subscription.url.trim() == redeemed.subscriptionUrl.trim() }
                    ?: MmkvManager.decodeSubscriptions().firstOrNull()
                val subId = existing?.guid ?: Utils.getUuid()
                val item = existing?.subscription?.apply {
                    enabled = true
                    url = redeemed.subscriptionUrl
                    remarks = redeemed.remarks
                } ?: SubscriptionItem(
                    remarks = redeemed.remarks,
                    url = redeemed.subscriptionUrl,
                    enabled = true,
                )
                MmkvManager.encodeSubscription(subId, item)
                LicenseBackendClient.setActiveLicenseSubId(subId)
                val cache = SubscriptionCache(subId, item)
                val updateRes = AngConfigManager.updateConfigViaSub(cache)
                purgeNonCdnConfigs()
                val appCtx = context.applicationContext
                CdnPingClassifier.classifyAllProfilesIfNeeded(appCtx, forceRefresh = !isStatusRefresh)
                CloudflareConfigEnricher.enrichAllStoredCloudflareProfiles(appCtx)
                if (!isStatusRefresh) {
                    markExplicitServerSelected(false)
                }
                ensureCloudflarePrioritySelection(appCtx)
                if (redeemed.quota != null) {
                    SubscriptionQuotaStore.save(subId, redeemed.quota)
                } else {
                    SubscriptionQuotaStore.fetchAndSaveHeader(subId, item.url, item.userAgent)
                }
                if (updateRes.configCount > 0) {
                    Result.success(updateRes.configCount)
                } else {
                    Result.failure(IllegalStateException("هیچ کانفیگ CDN معتبری برای این لایسنس یافت نشد."))
                }
            }
        }
    }

    private fun resolveTargetGuid(
        context: Context,
        explicitGuid: String?,
        honorExplicitSelection: Boolean = false,
    ): String? {
        val selected = explicitGuid ?: MmkvManager.getSelectServer()
        val allGuids = MmkvManager.decodeAllServerList()
        val pairs: List<Pair<String, ProfileItem?>> = allGuids.map { it to MmkvManager.decodeServerConfig(it) }
        val frontCidrs = ProviderRangeStore.getEntries(context, CdnType.CLOUDFRONT)
            .map { it.cidr }
            .ifEmpty { ProviderRangeStore.loadBundledCloudFrontCidrs(context) }
        val cachedCdns = CdnPingClassifier.CdnDetectionStore.getAllSaved(allGuids)
        return AutoConfigFilter.pickBestConnectGuid(
            selectedGuid = selected,
            profilesInOrder = pairs,
            honorExplicitSelection = honorExplicitSelection,
            cloudFrontCidrs = frontCidrs,
            cachedCdns = cachedCdns,
        )
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
