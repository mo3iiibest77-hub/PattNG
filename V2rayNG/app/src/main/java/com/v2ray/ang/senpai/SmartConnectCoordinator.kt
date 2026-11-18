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
    val activePipeline: ConfigPipeline = ConfigPipeline.CDN,
    val activeTransportBadge: String? = null,
    val switchReason: SmartSwitchReason = SmartSwitchReason.NONE,
    val switchNoticeText: String? = null,
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

    private data class ScannedEdgeSnapshot(
        val remarks: String,
        val serverPort: String,
        val password: String,
        val sni: String,
        val host: String,
        val originDomain: String,
        val scannedIp: String,
        val delayMs: Long,
    )

    private fun snapshotScannedEdgeProfiles(subId: String): List<ScannedEdgeSnapshot> {
        val guids = if (subId.isNotBlank()) MmkvManager.decodeServerList(subId) else MmkvManager.decodeAllServerList()
        val snapshots = mutableListOf<ScannedEdgeSnapshot>()
        for (guid in guids) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (!hasSavedEdgeIp(guid, profile)) continue
            val scannedIp = profile.server?.trim().orEmpty()
            val origin = OriginDomainStore.getSavedOriginDomain(guid).orEmpty()
            val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
            snapshots.add(
                ScannedEdgeSnapshot(
                    remarks = profile.remarks.trim(),
                    serverPort = profile.serverPort.orEmpty().trim(),
                    password = profile.password.orEmpty().trim(),
                    sni = profile.sni.orEmpty().trim(),
                    host = profile.host.orEmpty().trim(),
                    originDomain = origin,
                    scannedIp = scannedIp,
                    delayMs = delay,
                )
            )
        }
        return snapshots
    }

    private fun restoreScannedEdgeProfiles(subId: String, snapshots: List<ScannedEdgeSnapshot>) {
        if (snapshots.isEmpty()) return
        val newGuids = if (subId.isNotBlank()) MmkvManager.decodeServerList(subId) else MmkvManager.decodeAllServerList()
        var restored = 0
        for (guid in newGuids) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (ConfigPipelineClassifier.classify(profile) != ConfigPipeline.CDN) continue
            val newOrigin = OriginDomainStore.resolveDomainFromProfile(profile).orEmpty()
            val match = snapshots.firstOrNull { snap ->
                snap.remarks.equals(profile.remarks.trim(), ignoreCase = true) &&
                    snap.serverPort == profile.serverPort.orEmpty().trim() &&
                    snap.password == profile.password.orEmpty().trim() &&
                    (snap.originDomain.isEmpty() || newOrigin.isEmpty() || snap.originDomain.equals(newOrigin, ignoreCase = true))
            } ?: continue
            OriginDomainStore.rememberIfDomain(guid, profile)
            profile.server = match.scannedIp
            profile.description = AngConfigManager.generateDescription(profile)
            MmkvManager.encodeServerConfig(guid, profile)
            markEdgeIpScanned(guid, true)
            if (match.delayMs > 0L) {
                MmkvManager.encodeServerTestDelayMillis(guid, match.delayMs)
            }
            restored++
        }
        if (restored > 0) {
            AppLogBuffer.info(TAG, "Preserved $restored scanned CDN edge IP(s) across subscription sync")
        }
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

                // 2) Apply Cloudflare-only hardening & pick active pipeline config (CDN-first or explicit Standard)
                CloudflareConfigEnricher.enrichAllStoredCloudflareProfiles(appContext)
                _state.value = _state.value.copy(busy = true, step = SmartStep.SelectingProfile, errorMessage = null)
                val honorExplicit = !explicitGuid.isNullOrBlank() || isExplicitServerSelected()
                var targetGuid = resolveTargetGuid(appContext, explicitGuid, honorExplicit)
                    ?: error("هیچ کانفیگ معتبری برای اتصال یافت نشد.")
                var profile = MmkvManager.decodeServerConfig(targetGuid)
                    ?: error("اطلاعات کانفیگ انتخاب‌شده یافت نشد.")
                var activePipeline = ConfigPipelineClassifier.classify(profile)

                // Standard pipeline (REALITY / Hysteria2 / VMess / Trojan / SS / WireGuard / Socks / HTTP):
                // Completely bypasses CDN IP scanning, SNI rewriting, and Cloudflare TLS overrides.
                if (activePipeline == ConfigPipeline.STANDARD) {
                    SmartFailoverStore.setSmartFailoverArmed(false)
                    SmartFailoverStore.clearSwitchEvent()
                    pendingOptimization = null
                    MmkvManager.setSelectServer(targetGuid)
                    val stdState = _state.value.copy(
                        busy = false,
                        step = SmartStep.StartingVpn,
                        activeGuid = targetGuid,
                        activeRemarks = profile.remarks,
                        activePipeline = ConfigPipeline.STANDARD,
                        activeTransportBadge = ConfigPipelineClassifier.safeTransportBadge(profile),
                        switchReason = SmartSwitchReason.NONE,
                        switchNoticeText = null,
                        originDomain = null,
                        detectedCdn = CdnType.UNKNOWN,
                        bestIp = null,
                        scanDone = 0,
                        scanTotal = 0,
                    )
                    _state.value = stdState
                    AppLogBuffer.info(
                        TAG,
                        "Connecting STANDARD pipeline (${stdState.activeTransportBadge}): ${profile.remarks}"
                    )
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        onReadyToStartVpn(targetGuid, stdState)
                    }
                    return@launch
                }

                // Arm automatic degradation failover when running Smart Auto CDN mode
                SmartFailoverStore.setSmartFailoverArmed(!honorExplicit && SmartFailoverStore.isAutoFailoverEnabled())
                SmartFailoverStore.clearSwitchEvent()

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
                        activePipeline = ConfigPipeline.CDN,
                        activeTransportBadge = ConfigPipelineClassifier.safeTransportBadge(profile),
                        switchReason = SmartSwitchReason.NONE,
                        switchNoticeText = null,
                        originDomain = originDomain,
                        detectedCdn = quickCdn,
                        bestIp = profile.server,
                    )
                    _state.value = fastState
                    AppLogBuffer.info(
                        TAG,
                        "Fast-connecting CDN (${quickCdn.name}) via saved edge IP ${profile.server}"
                    )
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
                )

                if (preparedProfile == null) {
                    if (!honorExplicit && SmartFailoverStore.isAutoFailoverEnabled()) {
                        val stdFallback = StandardConfigProber.probeBestStandard(appContext, excludeGuid = targetGuid)
                        if (stdFallback != null) {
                            SmartFailoverStore.setSmartFailoverArmed(false)
                            SmartFailoverStore.recordSwitchEvent(
                                reason = SmartSwitchReason.CDN_CONNECT_FAILED,
                                transportBadge = stdFallback.transportBadge,
                                delayMs = stdFallback.delayMs,
                            )
                            MmkvManager.setSelectServer(stdFallback.guid)
                            pendingOptimization = null
                            val fbState = _state.value.copy(
                                busy = false,
                                step = SmartStep.StartingVpn,
                                activeGuid = stdFallback.guid,
                                activeRemarks = stdFallback.profile.remarks,
                                activePipeline = ConfigPipeline.STANDARD,
                                activeTransportBadge = stdFallback.transportBadge,
                                switchReason = SmartSwitchReason.CDN_CONNECT_FAILED,
                                switchNoticeText = "${stdFallback.transportBadge} (${stdFallback.delayMs}ms)",
                                detectedCdn = CdnType.UNKNOWN,
                                bestIp = null,
                                scanDone = 0,
                                scanTotal = 0,
                            )
                            _state.value = fbState
                            AppLogBuffer.warn(
                                TAG,
                                "CDN pre-connect failed -> auto-switched to ${stdFallback.transportBadge} (${stdFallback.delayMs}ms)"
                            )
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                onReadyToStartVpn(stdFallback.guid, fbState)
                            }
                            return@launch
                        }
                    }
                    error("کانفیگ انتخاب‌شده فاقد آدرس یا TLS SNI معتبر است.")
                }
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
                    activePipeline = ConfigPipeline.CDN,
                    activeTransportBadge = ConfigPipelineClassifier.safeTransportBadge(profile),
                    switchReason = SmartSwitchReason.NONE,
                    switchNoticeText = null,
                    originDomain = originDomain.ifBlank { profile.sni.orEmpty() },
                    detectedCdn = quickCdn,
                    bestIp = profile.server,
                    scanDone = 0,
                    scanTotal = 30,
                )
                _state.value = instantFirstState
                AppLogBuffer.info(
                    TAG,
                    "Instant first-connect CDN (${quickCdn.name}); post-connect 30-IP optimization queued"
                )
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
     * Triggered when starting a CDN profile in Smart Auto mode fails immediately: probes eligible
     * Standard (`REALITY` / `Hysteria2` / `v2rayNG`) configs and launches the best reachable one.
     */
    fun tryFailoverOnStartFailure(
        context: Context,
        failedGuid: String?,
        onFallbackReady: (guid: String, state: SmartFlowState) -> Unit,
        onNoFallback: () -> Unit,
    ) {
        if (!SmartFailoverStore.isSmartFailoverArmed()) {
            onNoFallback()
            return
        }
        val appCtx = context.applicationContext
        val failedProfile = failedGuid?.let { MmkvManager.decodeServerConfig(it) }
        if (failedProfile != null && ConfigPipelineClassifier.classify(failedProfile) != ConfigPipeline.CDN) {
            onNoFallback()
            return
        }
        activeJob?.cancel()
        activeJob = scope.launch {
            val stdBest = StandardConfigProber.probeBestStandard(appCtx, excludeGuid = failedGuid)
            if (stdBest == null) {
                SmartFailoverStore.setSmartFailoverArmed(false)
                kotlinx.coroutines.withContext(Dispatchers.Main) { onNoFallback() }
                return@launch
            }
            SmartFailoverStore.setSmartFailoverArmed(false)
            SmartFailoverStore.recordSwitchEvent(
                reason = SmartSwitchReason.CDN_CONNECT_FAILED,
                transportBadge = stdBest.transportBadge,
                delayMs = stdBest.delayMs,
            )
            MmkvManager.setSelectServer(stdBest.guid)
            val fbState = _state.value.copy(
                busy = false,
                step = SmartStep.StartingVpn,
                activeGuid = stdBest.guid,
                activeRemarks = stdBest.profile.remarks,
                activePipeline = ConfigPipeline.STANDARD,
                activeTransportBadge = stdBest.transportBadge,
                switchReason = SmartSwitchReason.CDN_CONNECT_FAILED,
                switchNoticeText = "${stdBest.transportBadge} (${stdBest.delayMs}ms)",
                detectedCdn = CdnType.UNKNOWN,
                bestIp = null,
                errorMessage = null,
            )
            _state.value = fbState
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onFallbackReady(stdBest.guid, fbState)
            }
        }
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

        val banner = SmartFailoverStore.readLastSwitchBanner()
        val runningProfile = targetGuid?.let { MmkvManager.decodeServerConfig(it) }
        val activePipeline = ConfigPipelineClassifier.classify(runningProfile)
        val prev = _state.value
        _state.value = prev.copy(
            busy = false,
            step = SmartStep.Connected,
            activeGuid = targetGuid,
            activeRemarks = runningProfile?.remarks ?: prev.activeRemarks,
            activePipeline = activePipeline,
            activeTransportBadge = runningProfile?.let { ConfigPipelineClassifier.safeTransportBadge(it) }
                ?: prev.activeTransportBadge,
            switchReason = banner?.reason ?: prev.switchReason,
            switchNoticeText = banner?.let { "${it.transportBadge} (${it.delayMs}ms)" } ?: prev.switchNoticeText,
            errorMessage = null,
        )
        if (activePipeline == ConfigPipeline.STANDARD) {
            pendingOptimization = null
            return
        }
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
                            } else {
                                AppLogBuffer.info(
                                    TAG,
                                    "Applied clean edge IP ${best.ip} (${best.latencyMs}ms, ↑${best.uploadKBps} KB/s)"
                                )
                            }
                        } else if (SmartFailoverStore.isSmartFailoverArmed() &&
                            StandardConfigProber.hasStandardCandidates(excludeGuid = targetGuid)
                        ) {
                            val stdBest = StandardConfigProber.probeBestStandard(appCtx, excludeGuid = targetGuid)
                            if (stdBest != null) {
                                LogUtil.w(TAG, "CDN scan found no working IP; failing over to ${stdBest.transportBadge} (${stdBest.delayMs}ms)")
                                SmartFailoverStore.setSmartFailoverArmed(false)
                                SmartFailoverStore.recordSwitchEvent(
                                    reason = SmartSwitchReason.CDN_CONNECT_FAILED,
                                    transportBadge = stdBest.transportBadge,
                                    delayMs = stdBest.delayMs,
                                )
                                MmkvManager.setSelectServer(stdBest.guid)
                                _state.value = _state.value.copy(
                                    busy = false,
                                    step = SmartStep.Connected,
                                    activeGuid = stdBest.guid,
                                    activeRemarks = stdBest.profile.remarks,
                                    activePipeline = ConfigPipeline.STANDARD,
                                    activeTransportBadge = stdBest.transportBadge,
                                    switchReason = SmartSwitchReason.CDN_CONNECT_FAILED,
                                    switchNoticeText = "${stdBest.transportBadge} (${stdBest.delayMs}ms)",
                                    detectedCdn = CdnType.UNKNOWN,
                                )
                                LauncherManager.restartService(appCtx)
                                return@launch
                            }
                        }

                        _state.value = _state.value.copy(
                            busy = false,
                            step = SmartStep.Connected,
                        )
                    }
                }

                val refreshedProfile = MmkvManager.decodeServerConfig(targetGuid) ?: initialProfile
                if (!ConfigPipelineClassifier.isCdn(refreshedProfile)) return@launch
                val allProviders = MmkvManager.decodeAllServerList().mapNotNull { g ->
                    val p = MmkvManager.decodeServerConfig(g) ?: return@mapNotNull null
                    if (!AutoConfigFilter.isCdnScanEligible(p)) return@mapNotNull null
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
        val snapshotsBySub = mutableMapOf<String, List<ScannedEdgeSnapshot>>()
        for (subCache in subs) {
            if (!subCache.subscription.enabled) continue
            snapshotsBySub[subCache.guid] = snapshotScannedEdgeProfiles(subCache.guid)
            val res = AngConfigManager.updateConfigViaSub(subCache)
            totalConfigs += res.configCount
            SubscriptionQuotaStore.fetchAndSaveHeader(
                subId = subCache.guid,
                url = subCache.subscription.url,
                userAgent = subCache.subscription.userAgent
            )
        }
        CdnPingClassifier.classifyAllProfilesIfNeeded(context.applicationContext, forceRefresh = false)
        CloudflareConfigEnricher.enrichAllStoredCloudflareProfiles(context.applicationContext)
        snapshotsBySub.forEach { (subId, snaps) -> restoreScannedEdgeProfiles(subId, snaps) }
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
                val edgeSnapshots = snapshotScannedEdgeProfiles(subId)
                val updateRes = AngConfigManager.updateConfigViaSub(cache)
                val appCtx = context.applicationContext
                CdnPingClassifier.classifyAllProfilesIfNeeded(appCtx, forceRefresh = !isStatusRefresh)
                CloudflareConfigEnricher.enrichAllStoredCloudflareProfiles(appCtx)
                restoreScannedEdgeProfiles(subId, edgeSnapshots)
                if (!isStatusRefresh) {
                    markExplicitServerSelected(false)
                }
                ensureCloudflarePrioritySelection(appCtx)
                AppLogBuffer.info(
                    TAG,
                    "Subscription synced (${updateRes.configCount} configs, statusRefresh=$isStatusRefresh)"
                )
                if (redeemed.quota != null) {
                    SubscriptionQuotaStore.save(subId, redeemed.quota)
                } else {
                    SubscriptionQuotaStore.fetchAndSaveHeader(subId, item.url, item.userAgent)
                }
                if (updateRes.configCount > 0) {
                    Result.success(updateRes.configCount)
                } else {
                    Result.failure(IllegalStateException("هیچ کانفیگ معتبری برای این لایسنس یافت نشد."))
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
        if (honorExplicitSelection && !selected.isNullOrBlank()) {
            val selectedProfile = pairs.firstOrNull { it.first == selected }?.second
            if (ConfigPipelineClassifier.isSupportedImport(selectedProfile)) {
                return selected
            }
        }
        val frontCidrs = ProviderRangeStore.getEntries(context, CdnType.CLOUDFRONT)
            .map { it.cidr }
            .ifEmpty { ProviderRangeStore.loadBundledCloudFrontCidrs(context) }
        val cachedCdns = CdnPingClassifier.CdnDetectionStore.getAllSaved(allGuids)
        val cdnPick = AutoConfigFilter.pickBestConnectGuid(
            selectedGuid = selected,
            profilesInOrder = pairs,
            honorExplicitSelection = honorExplicitSelection,
            cloudFrontCidrs = frontCidrs,
            cachedCdns = cachedCdns,
        )
        if (!cdnPick.isNullOrBlank()) return cdnPick
        val standardPairs = pairs.mapNotNull { (g, p) -> if (p != null) g to p else null }
        val cachedDelays = standardPairs.associate { (g, _) ->
            g to (MmkvManager.decodeServerAffiliationInfo(g)?.testDelayMillis ?: 0L)
        }
        return ConfigPipelineClassifier.selectBestStandardGuid(standardPairs, cachedDelays)
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
