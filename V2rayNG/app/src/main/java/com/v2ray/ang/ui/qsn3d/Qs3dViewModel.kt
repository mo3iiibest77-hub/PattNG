package com.v2ray.ang.ui.qsn3d

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.ApkAssetOption
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.ApkInstallResult
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.NovaPulseApkDownloader
import com.v2ray.ang.handler.UpdateCheckerManager
import java.io.File
import com.v2ray.ang.senpai.AppLogBuffer
import com.v2ray.ang.senpai.AppLogEntry
import com.v2ray.ang.senpai.AutoConfigFilter
import com.v2ray.ang.senpai.CdnDetectionResult
import com.v2ray.ang.senpai.CdnPingClassifier
import com.v2ray.ang.senpai.CdnType
import com.v2ray.ang.senpai.CloudFrontScanner
import com.v2ray.ang.senpai.CloudflareConfigEnricher
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.ConfigListBucket
import com.v2ray.ang.senpai.ConfigPipeline
import com.v2ray.ang.senpai.ConfigPipelineClassifier
import com.v2ray.ang.senpai.DeviceIdentityManager
import com.v2ray.ang.senpai.LicenseBackendClient
import com.v2ray.ang.senpai.LicenseResolutionResult
import com.v2ray.ang.senpai.LicenseSessionCleaner
import com.v2ray.ang.senpai.LicenseSessionStore
import com.v2ray.ang.senpai.LogoutOutcome
import com.v2ray.ang.senpai.OriginDomainStore
import com.v2ray.ang.senpai.PendingLogoutWorker
import com.v2ray.ang.senpai.PostConnectDiscoveryRunner
import com.v2ray.ang.senpai.PostConnectDiscoveryState
import com.v2ray.ang.senpai.ProviderRangeEntry
import com.v2ray.ang.senpai.ProviderRangeStore
import com.v2ray.ang.senpai.SmartConnectCoordinator
import com.v2ray.ang.senpai.SmartFailoverStore
import com.v2ray.ang.senpai.SmartFlowState
import com.v2ray.ang.senpai.SmartOptimizeWorker
import com.v2ray.ang.senpai.SubscriptionQuotaInfo
import com.v2ray.ang.senpai.SubscriptionQuotaStore
import com.v2ray.ang.senpai.SwitchBannerInfo
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.ui.main.MainDataSource
import com.v2ray.ang.ui.main.MainRepository
import com.v2ray.ang.ui.main.MainServiceEvent
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class Qs3dTab { Connect, Configs, Ranges, Apps }

data class SubscriptionUiRow(
    val guid: String,
    val remarks: String,
    val enabled: Boolean,
    val lastUpdated: Long,
    val configCount: Int,
    val quota: SubscriptionQuotaInfo?,
)

/**
 * Privacy-hardened config representation for UI: strips protocol, host, transport, and raw
 * endpoint details while keeping user-facing name, connection delay, and coarse CDN eligibility.
 */
data class ConfigUiRow(
    val guid: String,
    val subscriptionId: String,
    val remarks: String,
    val delayMillis: Long,
    val cdnHint: CdnType,
    val eligibleForScan: Boolean,
    val pipeline: ConfigPipeline = ConfigPipeline.CDN,
    val transportBadge: String = "",
    val isManual: Boolean = false,
)

data class Qs3dUiState(
    val activeTab: Qs3dTab = Qs3dTab.Connect,
    val showSubscriptionDetails: Boolean = false,
    val showRangeExplorer: Boolean = false,
    val showAboutPromo: Boolean = false,
    val showLogsScreen: Boolean = false,
    val showOptimizeConsentPrompt: Boolean = false,
    val hasActiveLicense: Boolean = false,
    val isRedeemingLicense: Boolean = false,
    val activationProgressPercent: Int = 0,
    val activationStageKey: String = "",
    val isLoggingOut: Boolean = false,
    val coreRunning: Boolean = false,
    val selectedGuid: String? = null,
    val connectedLatencyMs: Long? = null,
    val connectedCountry: String = "",
    val connectedTestMessage: String? = null,
    val liveDownloadBps: Long = 0L,
    val liveUploadBps: Long = 0L,
    val speedDisplayEnabled: Boolean = true,
    val activationInput: String = "",
    val toastMessage: String? = null,
    val activeNoticeDialog: String? = null,
    val activeNoticeTitle: String? = null,
    val activeNoticeBadge: String? = null,
    val smartFlow: SmartFlowState = SmartFlowState(),
    val discoveryState: PostConnectDiscoveryState = PostConnectDiscoveryState(),
    val lastCdnDetection: CdnDetectionResult? = null,
    val forcedCdn: CdnType? = null,
    val detectingCdn: Boolean = false,
    // Configs & Subscriptions
    val subscriptions: List<SubscriptionUiRow> = emptyList(),
    val configs: List<ConfigUiRow> = emptyList(),
    val configBucket: ConfigListBucket = ConfigListBucket.ALL,
    val configSearchQuery: String = "",
    val bulkSelectedGuids: Set<String> = emptySet(),
    val isDelayTestingAll: Boolean = false,
    val delayTestProgress: String = "",
    val isExplicitSelection: Boolean = false,
    val failoverBanner: SwitchBannerInfo? = null,
    // Ranges
    val activeRangeProvider: CdnType = CdnType.CLOUDFLARE,
    val rangeEntries: List<ProviderRangeEntry> = emptyList(),
    val rangeSearchQuery: String = "",
    val isManualDiscoveryRunning: Boolean = false,
    val manualDiscoveryDone: Int = 0,
    val manualDiscoveryTotal: Int = 0,
    val manualDiscoveryCidr: String = "",
    val isSyncingAws: Boolean = false,
    val cloudFrontLastSyncedAt: Long = 0L,
    // Settings / Automation
    val autoPostConnectDiscovery: Boolean = true,
    val auto2hOptimize: Boolean = true,
    val autoSmartFailover: Boolean = true,
    val lastOptimizeAt: Long = 0L,
    val lastOptimizeIp: String = "",
    val lastOptimizeUploadKBps: Long = 0L,
    val currentLanguageCode: String = "fa",
    // Self-Updater
    val isCheckingUpdate: Boolean = false,
    val availableUpdate: CheckUpdateResult? = null,
    val showUpdateDialog: Boolean = false,
    val updateDownloadProgress: Int? = null,
    val readyUpdateApkPath: String? = null,
    val updateNeedsInstallPerm: Boolean = false,
    // Diagnostics Logs (redacted)
    val diagnosticLogs: List<AppLogEntry> = emptyList(),
    val logSearchQuery: String = "",
    val logFilterBucket: LogFilterBucket = LogFilterBucket.ALL,
) {
    val liveDownloadFormatted: String get() = liveDownloadBps.toSpeedString()
    val liveUploadFormatted: String get() = liveUploadBps.toSpeedString()
}

class Qs3dViewModel(
    application: Application,
    private val dataSource: MainDataSource,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(Qs3dUiState())
    val uiState: StateFlow<Qs3dUiState> = _uiState.asStateFlow()

    companion object {
        private const val PREF_OPTIMIZE_CONSENT_ASKED = "np_2h_optimize_consent_asked"
        private const val PREF_CAMPAIGN_DIALOG_ID = "np_campaign_dialog_id"
        private const val PREF_CAMPAIGN_DIALOG_AT_MS = "np_campaign_dialog_at_ms"
        private const val PREF_CAMPAIGN_NOTIFY_ID = "np_campaign_notify_id"
        private const val PREF_CAMPAIGN_NOTIFY_AT_MS = "np_campaign_notify_at_ms"
    }

    @Volatile
    private var backgroundClassifyLaunched = false
    @Volatile
    private var autoCountryProbeDoneForSession = false
    @Volatile
    private var lastLicenseVerifyMs = 0L
    @Volatile
    private var lastUpdateCheckMs = 0L
    @Volatile
    private var lastSubSyncMs = 0L
    @Volatile
    private var lastUpdateDialogDismissMs = 0L
    @Volatile
    private var forceRevokingLicense = false
    private val verifyInFlight = AtomicBoolean(false)
    private val autoInitialPingTriggered = AtomicBoolean(false)
    private var licenseWatchdogJob: Job? = null
    @Volatile
    private var cachedSystemCoreLogs: List<AppLogEntry> = emptyList()

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            dataSource.initAssets()
        }
        collectServiceEvents()
        collectCoordinatorFlows()
        collectDiagnosticLogs()
        refreshAllData(fetchRemoteQuota = true)
        applyPendingDaemonNoticeIfAny()
        verifyLicenseSessionNow(force = true)
        viewModelScope.launch(Dispatchers.IO) {
            delay(1500L)
            checkForAppUpdates(silent = true)
        }
    }

    private fun collectDiagnosticLogs() {
        viewModelScope.launch {
            AppLogBuffer.logsFlow.collect { bufferEntries ->
                val merged = (cachedSystemCoreLogs + bufferEntries).distinctBy { it.id }
                _uiState.update { it.copy(diagnosticLogs = merged) }
            }
        }
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                when (event) {
                    MainServiceEvent.StateRunning -> {
                        _uiState.update {
                            it.copy(
                                coreRunning = true,
                                failoverBanner = SmartFailoverStore.readLastSwitchBanner(),
                            )
                        }
                        SmartConnectCoordinator.notifyVpnConnected(getApplication(), MmkvManager.getSelectServer())
                        loadConfigsAndSubs()
                        scheduleAutoCountryPing()
                        startLicenseWatchdog()
                    }

                    MainServiceEvent.StateStartSuccess -> {
                        val consentAsked = MmkvManager.decodeSettingsBool(PREF_OPTIMIZE_CONSENT_ASKED, false)
                        _uiState.update {
                            it.copy(
                                coreRunning = true,
                                showOptimizeConsentPrompt = !consentAsked,
                            )
                        }
                        SmartConnectCoordinator.notifyVpnConnected(getApplication(), MmkvManager.getSelectServer())
                        loadConfigsAndSubs()
                        scheduleAutoCountryPing()
                        startLicenseWatchdog()
                    }

                    MainServiceEvent.StateNotRunning,
                    MainServiceEvent.StateStopSuccess -> {
                        autoCountryProbeDoneForSession = false
                        licenseWatchdogJob?.cancel()
                        licenseWatchdogJob = null
                        _uiState.update {
                            it.copy(
                                hasActiveLicense = LicenseBackendClient.isLicenseSessionActive(),
                                coreRunning = false,
                                connectedLatencyMs = null,
                                connectedCountry = "",
                                connectedTestMessage = null,
                                liveDownloadBps = 0L,
                                liveUploadBps = 0L,
                            )
                        }
                        applyPendingDaemonNoticeIfAny()
                    }

                    is MainServiceEvent.StateStartFailure -> {
                        val failedGuid = MmkvManager.getSelectServer()
                        val appCtx = getApplication<Application>()
                        if (SmartFailoverStore.isSmartFailoverArmed()) {
                            SmartConnectCoordinator.tryFailoverOnStartFailure(
                                context = appCtx,
                                failedGuid = failedGuid,
                                onFallbackReady = { fallbackGuid, _ ->
                                    _uiState.update {
                                        it.copy(
                                            selectedGuid = fallbackGuid,
                                            failoverBanner = SmartFailoverStore.readLastSwitchBanner(),
                                        )
                                    }
                                    loadConfigsAndSubs()
                                    LauncherManager.startService(appCtx, fallbackGuid)
                                },
                                onNoFallback = {
                                    SmartConnectCoordinator.notifyVpnStartFailed(event.errorMessage)
                                    val baseFail = appCtx.getString(R.string.novapulse_vpn_start_failed)
                                    val detail = event.errorMessage?.trim().orEmpty()
                                    val fullMsg = if (detail.isNotEmpty() && detail != baseFail) "$baseFail: $detail" else baseFail
                                    _uiState.update {
                                        it.copy(
                                            coreRunning = false,
                                            liveDownloadBps = 0L,
                                            liveUploadBps = 0L,
                                            toastMessage = fullMsg
                                        )
                                    }
                                }
                            )
                        } else {
                            SmartConnectCoordinator.notifyVpnStartFailed(event.errorMessage)
                            val baseFail = appCtx.getString(R.string.novapulse_vpn_start_failed)
                            val detail = event.errorMessage?.trim().orEmpty()
                            val fullMsg = if (detail.isNotEmpty() && detail != baseFail) "$baseFail: $detail" else baseFail
                            _uiState.update {
                                it.copy(
                                    coreRunning = false,
                                    liveDownloadBps = 0L,
                                    liveUploadBps = 0L,
                                    toastMessage = fullMsg
                                )
                            }
                        }
                    }

                    is MainServiceEvent.MeasureDelayResult -> {
                        val ms = event.result.delayMillis
                        val ctry = event.result.country.orEmpty().trim()
                        _uiState.update {
                            it.copy(
                                connectedLatencyMs = if (ms >= 0) ms else null,
                                connectedCountry = ctry.ifEmpty { it.connectedCountry },
                                connectedTestMessage = if (ms >= 0) {
                                    if (ctry.isNotEmpty()) "${ms}ms • $ctry" else "${ms}ms"
                                } else event.result.errorMessage
                            )
                        }
                    }

                    is MainServiceEvent.LiveSpeedUpdate -> {
                        _uiState.update {
                            it.copy(
                                liveDownloadBps = event.proxyDownlinkBps,
                                liveUploadBps = event.proxyUplinkBps,
                            )
                        }
                    }

                    MainServiceEvent.MeasureConfigSuccess -> loadConfigsAndSubs()

                    is MainServiceEvent.MeasureConfigNotify -> {
                        _uiState.update { it.copy(delayTestProgress = event.progress) }
                    }

                    is MainServiceEvent.MeasureConfigFinish -> {
                        _uiState.update { it.copy(isDelayTestingAll = false, delayTestProgress = "") }
                        loadConfigsAndSubs()
                    }
                }
            }
        }
    }

    private fun collectCoordinatorFlows() {
        viewModelScope.launch {
            SmartConnectCoordinator.state.collect { flowState ->
                _uiState.update { it.copy(smartFlow = flowState) }
                if (!flowState.busy) {
                    loadConfigsAndSubs()
                }
            }
        }
        viewModelScope.launch {
            PostConnectDiscoveryRunner.state.collect { discState ->
                _uiState.update {
                    it.copy(
                        discoveryState = discState,
                        isManualDiscoveryRunning = if (discState.running) it.isManualDiscoveryRunning else false,
                        manualDiscoveryDone = if (discState.running) discState.done else it.manualDiscoveryDone,
                        manualDiscoveryTotal = if (discState.running) discState.total else it.manualDiscoveryTotal,
                        manualDiscoveryCidr = if (discState.running) discState.currentCidr else it.manualDiscoveryCidr,
                    )
                }
                if (!discState.running) {
                    loadRanges(_uiState.value.activeRangeProvider)
                }
            }
        }
    }

    fun selectTab(tab: Qs3dTab) {
        _uiState.update {
            it.copy(
                activeTab = tab,
                showSubscriptionDetails = false,
                showRangeExplorer = false,
                showAboutPromo = false,
                showLogsScreen = false,
            )
        }
        if (tab == Qs3dTab.Ranges) loadRanges(_uiState.value.activeRangeProvider)
        if (tab == Qs3dTab.Configs || tab == Qs3dTab.Connect) {
            loadConfigsAndSubs()
            if (tab == Qs3dTab.Configs) {
                autoTestUntestedDelaysIfNeeded()
            }
        }
    }

    fun openSubscriptionDetails(open: Boolean) {
        _uiState.update {
            it.copy(
                showSubscriptionDetails = open,
                showAboutPromo = if (open) false else it.showAboutPromo,
                showLogsScreen = if (open) false else it.showLogsScreen,
            )
        }
        if (open) refreshQuotaOnly()
    }

    fun openRangeExplorer(open: Boolean) {
        _uiState.update {
            it.copy(
                showRangeExplorer = open,
                showAboutPromo = if (open) false else it.showAboutPromo,
                showLogsScreen = if (open) false else it.showLogsScreen,
            )
        }
        if (open) loadRanges(_uiState.value.activeRangeProvider)
    }

    fun openAboutPromo(open: Boolean) {
        _uiState.update {
            it.copy(
                showAboutPromo = open,
                showSubscriptionDetails = if (open) false else it.showSubscriptionDetails,
                showRangeExplorer = if (open) false else it.showRangeExplorer,
                showLogsScreen = if (open) false else it.showLogsScreen,
            )
        }
    }

    fun openLogsScreen(open: Boolean) {
        _uiState.update {
            it.copy(
                showLogsScreen = open,
                showSubscriptionDetails = if (open) false else it.showSubscriptionDetails,
                showRangeExplorer = if (open) false else it.showRangeExplorer,
                showAboutPromo = if (open) false else it.showAboutPromo,
            )
        }
        if (open) refreshSystemLogs()
    }

    fun setLogSearchQuery(query: String) {
        _uiState.update { it.copy(logSearchQuery = query) }
    }

    fun setLogFilterBucket(bucket: LogFilterBucket) {
        _uiState.update { it.copy(logFilterBucket = bucket) }
    }

    fun refreshSystemLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val sys = AppLogBuffer.readSanitizedSystemCoreLines()
            cachedSystemCoreLogs = sys
            val merged = (sys + AppLogBuffer.snapshot()).distinctBy { it.id }
            _uiState.update { it.copy(diagnosticLogs = merged) }
        }
    }

    fun clearDiagnosticLogs() {
        cachedSystemCoreLogs = emptyList()
        AppLogBuffer.clear()
        _uiState.update { it.copy(diagnosticLogs = emptyList()) }
    }

    fun copyLogsToClipboard() {
        val text = AppLogBuffer.formatExportText(_uiState.value.diagnosticLogs)
        if (text.isBlank()) return
        val app = getApplication<Application>()
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("novapulse_logs", text))
        _uiState.update { it.copy(toastMessage = app.getString(R.string.novapulse_logs_copied)) }
    }

    fun respondToOptimizeConsent(accepted: Boolean) {
        MmkvManager.encodeSettings(PREF_OPTIMIZE_CONSENT_ASKED, true)
        SmartOptimizeWorker.setEnabled(getApplication(), accepted)
        _uiState.update {
            it.copy(
                showOptimizeConsentPrompt = false,
                auto2hOptimize = accepted,
            )
        }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun dismissNoticeDialog() {
        _uiState.update {
            it.copy(
                activeNoticeDialog = null,
                activeNoticeTitle = null,
                activeNoticeBadge = null,
            )
        }
    }

    fun openCampaignNotice(title: String?, body: String) {
        val cleanBody = body.trim()
        if (cleanBody.isEmpty()) return
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: "پیام NovaPulse"
        val badge = if (cleanTitle.contains("هدیه")) "پیشنهاد ویژه NovaPulse" else "پیام همراهان NovaPulse"
        _uiState.update {
            it.copy(
                activeNoticeDialog = cleanBody,
                activeNoticeTitle = cleanTitle,
                activeNoticeBadge = badge,
            )
        }
    }

    private fun clearCampaignThrottleForLicenseChange() {
        runCatching {
            MmkvManager.encodeSettings(PREF_CAMPAIGN_DIALOG_ID, "")
            MmkvManager.encodeSettings(PREF_CAMPAIGN_DIALOG_AT_MS, 0L)
            MmkvManager.encodeSettings(PREF_CAMPAIGN_NOTIFY_ID, "")
            MmkvManager.encodeSettings(PREF_CAMPAIGN_NOTIFY_AT_MS, 0L)
        }
    }

    private fun applyCampaignNotice(notice: GitHubRelease.CampaignNoticePayload?) {
        if (notice == null || notice.body.isBlank()) return
        val app = getApplication<Application>()
        val nowMs = System.currentTimeMillis()
        val noticeId = notice.id.ifBlank { "default_${notice.segment}" }
        val minDialogMs = (notice.minDialogIntervalSec.coerceAtLeast(60L)) * 1000L
        val minNotifyMs = (notice.minNotifyIntervalSec.coerceAtLeast(60L)) * 1000L
        val lastDialogId = MmkvManager.decodeSettingsString(PREF_CAMPAIGN_DIALOG_ID).orEmpty()
        val lastDialogMs = MmkvManager.decodeSettingsLong(PREF_CAMPAIGN_DIALOG_AT_MS, 0L)
        val shouldShowDialog = lastDialogId != noticeId || (nowMs - lastDialogMs) >= minDialogMs

        if (shouldShowDialog) {
            val cur = _uiState.value
            val busyWithHigherPriority = !cur.activeNoticeDialog.isNullOrBlank() ||
                cur.showUpdateDialog ||
                cur.updateDownloadProgress != null ||
                cur.isRedeemingLicense
            if (!busyWithHigherPriority) {
                MmkvManager.encodeSettings(PREF_CAMPAIGN_DIALOG_ID, noticeId)
                MmkvManager.encodeSettings(PREF_CAMPAIGN_DIALOG_AT_MS, nowMs)
                val badge = if (notice.segment.equals("gift", ignoreCase = true)) {
                    "پیشنهاد ویژه NovaPulse"
                } else {
                    "پیام همراهان NovaPulse"
                }
                _uiState.update {
                    if (!it.activeNoticeDialog.isNullOrBlank() || it.showUpdateDialog) {
                        it
                    } else {
                        it.copy(
                            activeNoticeDialog = notice.body,
                            activeNoticeTitle = notice.title.ifBlank { null },
                            activeNoticeBadge = badge,
                        )
                    }
                }
            }
        }

        if (notice.notifyEnabled) {
            val lastNotifyId = MmkvManager.decodeSettingsString(PREF_CAMPAIGN_NOTIFY_ID).orEmpty()
            val lastNotifyMs = MmkvManager.decodeSettingsLong(PREF_CAMPAIGN_NOTIFY_AT_MS, 0L)
            val shouldNotify = lastNotifyId != noticeId || (nowMs - lastNotifyMs) >= minNotifyMs
            if (shouldNotify) {
                val posted = NotificationManager.showCampaignNoticeNotification(
                    context = app,
                    noticeId = noticeId,
                    title = notice.notifyTitle.ifBlank { notice.title },
                    shortText = notice.notifyBody.ifBlank { notice.title },
                    fullBody = notice.body,
                )
                if (posted) {
                    MmkvManager.encodeSettings(PREF_CAMPAIGN_NOTIFY_ID, noticeId)
                    MmkvManager.encodeSettings(PREF_CAMPAIGN_NOTIFY_AT_MS, nowMs)
                }
            }
        }
    }

    fun setActivationInput(text: String) {
        _uiState.update { it.copy(activationInput = text) }
    }

    fun setForcedCdn(cdnType: CdnType?) {
        _uiState.update { it.copy(forcedCdn = cdnType) }
    }

    fun refreshAllData(fetchRemoteQuota: Boolean = false) {
        loadConfigsAndSubs()
        loadRanges(_uiState.value.activeRangeProvider)
        refreshSmartSettings()
        if (fetchRemoteQuota && LicenseBackendClient.getSavedLicenseKey().isNotEmpty()) {
            refreshQuotaOnly()
        }
    }

    fun syncSubscriptionIfStale(minIntervalMs: Long = 180_000L) {
        val now = System.currentTimeMillis()
        if ((now - lastSubSyncMs) < minIntervalMs) return
        if (_uiState.value.smartFlow.busy) return
        if (LicenseBackendClient.getSavedLicenseKey().isEmpty()) return
        refreshQuotaOnly(showFeedback = false)
    }

    fun refreshQuotaOnly(showFeedback: Boolean = false) {
        val ctx = getApplication<Application>()
        lastSubSyncMs = System.currentTimeMillis()
        SmartConnectCoordinator.refreshQuotaAndSubscription(ctx) { res ->
            res.onSuccess {
                loadConfigsAndSubs()
                if (showFeedback) {
                    _uiState.update { it.copy(toastMessage = ctx.getString(R.string.toast_success)) }
                }
            }.onFailure { err ->
                if (showFeedback) {
                    _uiState.update { it.copy(toastMessage = err.message ?: ctx.getString(R.string.toast_failure)) }
                }
            }
        }
    }

    fun loadConfigsAndSubs() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val selected = MmkvManager.getSelectServer()
            val subs = MmkvManager.decodeSubscriptions()
            val allGuids = MmkvManager.decodeAllServerList()

            val cfCidrs = CdnPingClassifier.getAllCloudflareCidrs(ctx)
            val frontCidrs = ProviderRangeStore.getEntries(ctx, CdnType.CLOUDFRONT)
                .map { it.cidr }
                .ifEmpty { ProviderRangeStore.loadBundledCloudFrontCidrs(ctx) }

            val subCounts = mutableMapOf<String, Int>()
            val pairs = mutableListOf<Pair<String, ProfileItem?>>()
            val rawConfigs = allGuids.mapNotNull { guid ->
                val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                if (!ConfigPipelineClassifier.isSupportedImport(profile)) return@mapNotNull null
                val pipeline = ConfigPipelineClassifier.classify(profile)
                val isCdn = pipeline == ConfigPipeline.CDN
                pairs.add(guid to profile)
                if (isCdn) {
                    OriginDomainStore.rememberIfDomain(guid, profile)
                }
                subCounts[profile.subscriptionId] = (subCounts[profile.subscriptionId] ?: 0) + 1
                val aff = MmkvManager.decodeServerAffiliationInfo(guid)
                val cdnHint = if (isCdn) {
                    val originDomain = OriginDomainStore.getOriginDomain(guid, profile) ?: profile.server.orEmpty()
                    val cachedCdn = CdnPingClassifier.CdnDetectionStore.getSavedCdnType(guid)
                    cachedCdn ?: if (
                        CloudflareConfigEnricher.isAmazonOrCloudFront(profile, originDomain, frontCidrs, cachedCdn)
                    ) {
                        CdnType.CLOUDFRONT
                    } else {
                        CdnPingClassifier.inferQuickCdnType(profile, originDomain, frontCidrs, cfCidrs, cachedCdn)
                    }
                } else {
                    CdnType.UNKNOWN
                }
                ConfigUiRow(
                    guid = guid,
                    subscriptionId = profile.subscriptionId,
                    remarks = profile.remarks.ifBlank { ctx.getString(R.string.novapulse_default_server_name) },
                    delayMillis = aff?.testDelayMillis ?: -1L,
                    cdnHint = cdnHint,
                    eligibleForScan = isCdn,
                    pipeline = pipeline,
                    transportBadge = ConfigPipelineClassifier.safeTransportBadge(profile),
                    isManual = ConfigPipelineClassifier.isManualConfig(profile),
                )
            }

            val sessionActive = LicenseBackendClient.isLicenseSessionActive()
            val licensed = sessionActive && (rawConfigs.isNotEmpty() || subs.isNotEmpty() || LicenseBackendClient.getSavedLicenseKey().isNotEmpty())
            val cachedCdnMap = CdnPingClassifier.CdnDetectionStore.getAllSaved(allGuids)
            val isExplicit = SmartConnectCoordinator.isExplicitServerSelected()

            val effectiveSelected = if (isExplicit && !selected.isNullOrBlank() && pairs.any { it.first == selected }) {
                selected
            } else {
                val cdnPick = AutoConfigFilter.pickBestConnectGuid(
                    selectedGuid = selected,
                    profilesInOrder = pairs,
                    honorExplicitSelection = isExplicit,
                    cloudFrontCidrs = frontCidrs,
                    cachedCdns = cachedCdnMap,
                )
                val chosen = cdnPick ?: ConfigPipelineClassifier.selectBestStandardGuid(
                    profilesInOrder = pairs.mapNotNull { (g, p) -> p?.let { g to it } },
                    delaysByGuid = rawConfigs.associate { it.guid to it.delayMillis },
                )
                if (!chosen.isNullOrBlank() && chosen != selected) {
                    MmkvManager.setSelectServer(chosen)
                }
                chosen
            }

            // Pin active selected server cleanly at the top while preserving original order
            val sortedPinned = rawConfigs.sortedByDescending { it.guid == effectiveSelected }

            val subRows = subs.map { cache ->
                SubscriptionUiRow(
                    guid = cache.guid,
                    remarks = cache.subscription.remarks.ifBlank { ctx.getString(R.string.novapulse_sub_default_title) },
                    enabled = cache.subscription.enabled,
                    lastUpdated = cache.subscription.lastUpdated,
                    configCount = subCounts[cache.guid] ?: 0,
                    quota = SubscriptionQuotaStore.get(cache.guid),
                )
            }

            _uiState.update {
                it.copy(
                    hasActiveLicense = licensed,
                    selectedGuid = effectiveSelected,
                    subscriptions = subRows,
                    configs = sortedPinned,
                    isExplicitSelection = isExplicit,
                    failoverBanner = SmartFailoverStore.readLastSwitchBanner(),
                )
            }

            if (rawConfigs.any { it.pipeline == ConfigPipeline.CDN && it.cdnHint == CdnType.UNKNOWN } && !backgroundClassifyLaunched) {
                backgroundClassifyLaunched = true
                val updated = CdnPingClassifier.classifyAllProfilesIfNeeded(ctx, forceRefresh = false)
                if (updated > 0) {
                    CloudflareConfigEnricher.enrichAllStoredCloudflareProfiles(ctx)
                    loadConfigsAndSubs()
                }
            }
        }
    }

    private fun scheduleAutoCountryPing() {
        if (autoCountryProbeDoneForSession) return
        autoCountryProbeDoneForSession = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(1400L)
            if (_uiState.value.coreRunning && _uiState.value.connectedCountry.isBlank()) {
                dataSource.testCurrentServerRealPing()
            }
        }
    }

    fun detectCdnForSelected() {
        viewModelScope.launch(Dispatchers.IO) {
            val guid = MmkvManager.getSelectServer() ?: return@launch
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@launch
            _uiState.update { it.copy(detectingCdn = true) }
            val result = CdnPingClassifier.detectForProfile(getApplication(), guid, profile)
            _uiState.update { it.copy(detectingCdn = false, lastCdnDetection = result) }
        }
    }

    fun testConnectedLatency() {
        dataSource.testCurrentServerRealPing()
    }

    fun redeemActivationInput(overrideInput: String? = null) {
        val input = (overrideInput ?: _uiState.value.activationInput).trim()
        val ctx = getApplication<Application>()
        if (input.isEmpty()) {
            _uiState.update { it.copy(toastMessage = ctx.getString(R.string.novapulse_license_enter_prompt)) }
            return
        }
        _uiState.update {
            it.copy(
                isRedeemingLicense = true,
                activationProgressPercent = 8,
                activationStageKey = "challenge",
            )
        }
        SmartConnectCoordinator.redeemInputOnly(
            context = ctx,
            rawInput = input,
            onProgress = { pct, stage ->
                _uiState.update {
                    it.copy(
                        activationProgressPercent = pct,
                        activationStageKey = stage,
                    )
                }
            }
        ) { res ->
            res.onSuccess { count ->
                _uiState.update {
                    it.copy(
                        isRedeemingLicense = false,
                        activationProgressPercent = 100,
                        activationStageKey = "ready",
                        hasActiveLicense = true,
                        activeTab = Qs3dTab.Connect,
                        activationInput = "",
                        showAboutPromo = false,
                        showSubscriptionDetails = false,
                        showRangeExplorer = false,
                        showLogsScreen = false,
                        toastMessage = ctx.getString(R.string.novapulse_license_activated_fmt, count)
                    )
                }
                autoInitialPingTriggered.set(false)
                clearCampaignThrottleForLicenseChange()
                loadConfigsAndSubs()
                detectCdnForSelected()
                autoTestUntestedDelaysIfNeeded(force = true)
                checkForAppUpdates(silent = true, openDialogIfUpdate = true)
            }.onFailure { err ->
                val cleanErr = LicenseBackendClient.sanitizeForDisplay(err.message)
                    .ifBlank { ctx.getString(R.string.novapulse_license_failed) }
                val showModal = NovaPulseNoticeParser.shouldShowAsRichDialog(cleanErr)
                _uiState.update {
                    it.copy(
                        isRedeemingLicense = false,
                        activationProgressPercent = 0,
                        activationStageKey = "",
                        toastMessage = if (showModal) it.toastMessage else cleanErr,
                        activeNoticeDialog = if (showModal) cleanErr else it.activeNoticeDialog,
                    )
                }
            }
        }
    }

    private fun applyPendingDaemonNoticeIfAny() {
        val pending = LicenseSessionStore.consumePendingNoticeMessage()
        if (pending.isBlank()) return
        val showModal = NovaPulseNoticeParser.shouldShowAsRichDialog(pending)
        val active = LicenseBackendClient.isLicenseSessionActive()
        _uiState.update {
            it.copy(
                hasActiveLicense = active,
                toastMessage = if (showModal) it.toastMessage else pending,
                activeNoticeDialog = if (showModal) pending else it.activeNoticeDialog,
            )
        }
        if (!active) {
            loadConfigsAndSubs()
        }
    }

    fun verifyLicenseSessionNow(force: Boolean = false) {
        applyPendingDaemonNoticeIfAny()
        val now = System.currentTimeMillis()
        if (!force && (now - lastLicenseVerifyMs) < 5_000L) return
        if (!verifyInFlight.compareAndSet(false, true)) return

        val ctx = getApplication<Application>()
        val snap = LicenseSessionStore.getSnapshot()
        val savedKey = LicenseBackendClient.getSavedLicenseKey()
        if (!snap.active && savedKey.isBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (!LicenseBackendClient.flushPendingLogoutAndReport() &&
                        LicenseSessionStore.getPendingLogout() != null
                    ) {
                        PendingLogoutWorker.schedule(ctx)
                    }
                } finally {
                    verifyInFlight.set(false)
                }
            }
            return
        }
        lastLicenseVerifyMs = now
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!LicenseBackendClient.flushPendingLogoutAndReport() &&
                    LicenseSessionStore.getPendingLogout() != null
                ) {
                    PendingLogoutWorker.schedule(ctx)
                }
                val deviceId = runCatching { DeviceIdentityManager.getDeviceId(ctx) }.getOrDefault("")
                if (deviceId.isBlank()) return@launch
                val res = LicenseBackendClient.refreshStatus(
                    licenseKey = savedKey,
                    deviceId = deviceId,
                )
                when (res) {
                    is LicenseResolutionResult.Failure -> {
                        if (res.isDefinitiveRejection) {
                            val msg = res.reason.ifBlank {
                                ctx.getString(R.string.novapulse_license_force_revoked)
                            }
                            forceDisconnectRevokedLicense(msg)
                        }
                    }
                    is LicenseResolutionResult.ReadySubscription -> {
                        res.quota?.let { q ->
                            val activeSubId = LicenseBackendClient.getActiveLicenseSubId()
                            if (activeSubId.isNotBlank()) {
                                SubscriptionQuotaStore.save(activeSubId, q)
                            }
                        }
                    }
                    else -> Unit
                }
            } finally {
                verifyInFlight.set(false)
            }
        }
    }

    private fun startLicenseWatchdog() {
        if (licenseWatchdogJob?.isActive == true) return
        licenseWatchdogJob = viewModelScope.launch(Dispatchers.IO) {
            delay(5_000L)
            while (isActive) {
                verifyLicenseSessionNow(force = false)
                delay(20_000L)
            }
        }
    }

    private suspend fun forceDisconnectRevokedLicense(message: String) {
        if (forceRevokingLicense) return
        forceRevokingLicense = true
        try {
            licenseWatchdogJob?.cancel()
            licenseWatchdogJob = null
            stopAndWipeLicenseSession(
                ctx = getApplication(),
                resultMessage = message,
                abortIfStopFails = false,
                syncServerLogout = false,
            )
        } finally {
            forceRevokingLicense = false
        }
    }

    fun logoutLicense() {
        if (_uiState.value.isLoggingOut) return
        val ctx = getApplication<Application>()
        _uiState.update { it.copy(isLoggingOut = true) }

        viewModelScope.launch(Dispatchers.IO) {
            stopAndWipeLicenseSession(
                ctx = ctx,
                resultMessage = ctx.getString(R.string.novapulse_logout_success),
                abortIfStopFails = true,
                syncServerLogout = true,
            )
        }
    }

    private suspend fun stopAndWipeLicenseSession(
        ctx: Application,
        resultMessage: String,
        abortIfStopFails: Boolean,
        syncServerLogout: Boolean = false,
    ): Boolean {
        val stopAttempt = AtomicInteger(0)
        val stopSucceeded = LicenseLogoutOrchestrator.stopRuntimeAndConfirm(
            isCoreRunning = { _uiState.value.coreRunning || isSystemVpnInterfaceActive(ctx) },
            cancelBackgroundWork = {
                SmartConnectCoordinator.cancelActiveFlow()
                PostConnectDiscoveryRunner.cancel()
                CloudflareScanner.cancel()
                CloudFrontScanner.cancel()
                SmartOptimizeWorker.cancel(ctx)
                dataSource.cancelAllPing()
            },
            requestStopVpn = {
                val attempt = stopAttempt.incrementAndGet()
                LauncherManager.stopService(ctx)
                if (attempt > 1) {
                    runCatching { ctx.stopService(Intent(ctx, CoreVpnService::class.java)) }
                    runCatching { ctx.stopService(Intent(ctx, CoreProxyOnlyService::class.java)) }
                    runCatching { ctx.stopService(Intent(ctx, CoreRootService::class.java)) }
                }
            },
            awaitStopEvent = { timeoutMs ->
                withTimeoutOrNull(timeoutMs) {
                    dataSource.mainServiceEvent.first {
                        it is MainServiceEvent.StateStopSuccess || it is MainServiceEvent.StateNotRunning
                    }
                } != null
            }
        )

        if (abortIfStopFails && !stopSucceeded) {
            _uiState.update {
                it.copy(
                    isLoggingOut = false,
                    toastMessage = ctx.getString(R.string.novapulse_logout_stop_failed)
                )
            }
            return false
        }

        if (!stopSucceeded) {
            runCatching { LauncherManager.stopService(ctx) }
            runCatching { ctx.stopService(Intent(ctx, CoreVpnService::class.java)) }
            runCatching { ctx.stopService(Intent(ctx, CoreProxyOnlyService::class.java)) }
            runCatching { ctx.stopService(Intent(ctx, CoreRootService::class.java)) }
        }

        var finalMessage = resultMessage
        if (syncServerLogout) {
            val snap = LicenseSessionStore.getSnapshot()
            val devId = runCatching { DeviceIdentityManager.getDeviceId(ctx) }.getOrDefault("")
            when (val outcome = LicenseBackendClient.logoutFromServerWithRetry()) {
                is LogoutOutcome.Success, is LogoutOutcome.AlreadyInvalid -> {
                    LicenseSessionStore.clearPendingLogout()
                }
                is LogoutOutcome.NetworkFailure -> {
                    if (snap.active && snap.sessionToken.isNotBlank() && devId.isNotBlank()) {
                        LicenseSessionStore.enqueuePendingLogout(snap.sessionToken, devId)
                        PendingLogoutWorker.schedule(ctx)
                    }
                    finalMessage = "خروج محلی انجام شد؛ اما به دلیل نبود ارتباط با سرور (${outcome.message})، آزادسازی ظرفیت به محض اتصال اینترنت ارسال خواهد شد."
                }
            }
        }

        LicenseSessionCleaner.wipeBoundLicenseData(ctx)

        autoCountryProbeDoneForSession = false
        val showModal = NovaPulseNoticeParser.shouldShowAsRichDialog(finalMessage)
        _uiState.update {
            it.copy(
                activeTab = Qs3dTab.Connect,
                isLoggingOut = false,
                hasActiveLicense = false,
                coreRunning = false,
                configs = emptyList(),
                subscriptions = emptyList(),
                selectedGuid = null,
                bulkSelectedGuids = emptySet(),
                activationInput = "",
                connectedLatencyMs = null,
                connectedCountry = "",
                connectedTestMessage = null,
                liveDownloadBps = 0L,
                liveUploadBps = 0L,
                lastCdnDetection = null,
                showSubscriptionDetails = false,
                showRangeExplorer = false,
                showAboutPromo = false,
                toastMessage = if (showModal) it.toastMessage else finalMessage,
                activeNoticeDialog = if (showModal) finalMessage else it.activeNoticeDialog,
            )
        }
        return true
    }

    private fun isSystemVpnInterfaceActive(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val activeNet = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(false)

    // ---------- Config Management (Privacy-Hardened) ----------

    fun selectConfig(guid: String) {
        val profile = MmkvManager.decodeServerConfig(guid)
        SmartConnectCoordinator.markExplicitServerSelected(true)
        SmartFailoverStore.setSmartFailoverArmed(false)
        SmartFailoverStore.clearSwitchEvent()
        MmkvManager.setSelectServer(guid)
        _uiState.update { st ->
            val repinned = st.configs.sortedByDescending { it.guid == guid }
            st.copy(
                selectedGuid = guid,
                configs = repinned,
                isExplicitSelection = true,
                failoverBanner = null,
            )
        }
        if (ConfigPipelineClassifier.isCdn(profile)) {
            detectCdnForSelected()
        } else {
            _uiState.update { it.copy(detectingCdn = false, lastCdnDetection = null) }
        }
    }

    fun switchToSmartAutoMode(reconnectIfRunning: Boolean = false) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            SmartConnectCoordinator.markExplicitServerSelected(false)
            SmartFailoverStore.clearSwitchEvent()
            SmartFailoverStore.setSmartFailoverArmed(SmartFailoverStore.isAutoFailoverEnabled())
            val allGuids = MmkvManager.decodeAllServerList()
            val frontCidrs = ProviderRangeStore.getEntries(ctx, CdnType.CLOUDFRONT)
                .map { it.cidr }
                .ifEmpty { ProviderRangeStore.loadBundledCloudFrontCidrs(ctx) }
            val pairs = allGuids.map { it to MmkvManager.decodeServerConfig(it) }
            val cachedCdns = CdnPingClassifier.CdnDetectionStore.getAllSaved(allGuids)
            val chosen = AutoConfigFilter.pickBestConnectGuid(
                selectedGuid = MmkvManager.getSelectServer(),
                profilesInOrder = pairs,
                honorExplicitSelection = false,
                cloudFrontCidrs = frontCidrs,
                cachedCdns = cachedCdns,
            ) ?: ConfigPipelineClassifier.selectBestStandardGuid(
                pairs.mapNotNull { (g, p) -> p?.let { g to it } }
            )
            if (!chosen.isNullOrBlank()) {
                val chosenProfile = MmkvManager.decodeServerConfig(chosen)
                if (chosenProfile != null &&
                    ConfigPipelineClassifier.isCdn(chosenProfile) &&
                    !SmartConnectCoordinator.hasSavedEdgeIp(chosen, chosenProfile)
                ) {
                    CloudflareConfigEnricher.prepareFirstConnectWithTlsSni(
                        guid = chosen,
                        profile = chosenProfile,
                        cloudFrontCidrs = frontCidrs,
                    )
                }
                MmkvManager.setSelectServer(chosen)
            }
            _uiState.update { it.copy(isExplicitSelection = false, failoverBanner = null) }
            loadConfigsAndSubs()
            if (reconnectIfRunning && !chosen.isNullOrBlank()) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    LauncherManager.restartService(ctx)
                }
            }
        }
    }

    fun setConfigBucket(bucket: ConfigListBucket) {
        _uiState.update { it.copy(configBucket = bucket) }
    }

    fun importManualConfigs(rawText: String, onSuccess: () -> Unit = {}) {
        val ctx = getApplication<Application>()
        if (!LicenseBackendClient.isLicenseSessionActive()) {
            _uiState.update { it.copy(toastMessage = ctx.getString(R.string.novapulse_license_enter_prompt)) }
            return
        }
        val payload = ConfigPipelineClassifier.extractManualImportPayload(rawText)
        if (payload == null) {
            _uiState.update { it.copy(toastMessage = ctx.getString(R.string.novapulse_add_config_invalid)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val (count, _) = AngConfigManager.importBatchConfig(payload, AppConfig.DEFAULT_SUBSCRIPTION_ID, true)
            CloudflareConfigEnricher.enrichAllStoredCloudflareProfiles(ctx)
            if (count > 0) {
                loadConfigsAndSubs()
                _uiState.update {
                    it.copy(toastMessage = ctx.getString(R.string.novapulse_add_config_success_fmt, count))
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } else {
                _uiState.update { it.copy(toastMessage = ctx.getString(R.string.novapulse_add_config_invalid)) }
            }
        }
    }

    fun setConfigSearch(query: String) {
        _uiState.update { it.copy(configSearchQuery = query) }
    }

    fun toggleBulkSelect(guid: String) {
        _uiState.update { st ->
            val next = if (guid in st.bulkSelectedGuids) st.bulkSelectedGuids - guid
            else st.bulkSelectedGuids + guid
            st.copy(bulkSelectedGuids = next)
        }
    }

    fun clearBulkSelection() {
        _uiState.update { it.copy(bulkSelectedGuids = emptySet()) }
    }

    fun deleteBulkSelected() {
        val guids = _uiState.value.bulkSelectedGuids.toList()
        if (guids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val active = MmkvManager.getSelectServer()
            guids.filter { it != active }.forEach { MmkvManager.removeServer(it) }
            _uiState.update { it.copy(bulkSelectedGuids = emptySet()) }
            loadConfigsAndSubs()
        }
    }

    fun autoTestUntestedDelaysIfNeeded(force: Boolean = false) {
        if (_uiState.value.isDelayTestingAll || _uiState.value.smartFlow.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            delay(350L)
            val rows = _uiState.value.configs
            if (rows.isEmpty()) return@launch
            val targets = if (force) {
                rows.map { it.guid }
            } else {
                rows.filter { it.delayMillis <= 0L }.map { it.guid }
            }
            if (targets.isEmpty()) return@launch
            if (!force && !autoInitialPingTriggered.compareAndSet(false, true)) return@launch
            MmkvManager.clearAllTestDelayResults(targets)
            _uiState.update { it.copy(isDelayTestingAll = true, delayTestProgress = "0/${targets.size}") }
            dataSource.sendMsg2TestService(
                TestServiceMessage(
                    key = AppConfig.MSG_MEASURE_CONFIG_START,
                    subscriptionId = "",
                    serverGuids = targets,
                    onlyTcp = false
                )
            )
        }
    }

    fun testAllDelays() {
        if (_uiState.value.isDelayTestingAll) {
            dataSource.cancelAllPing()
            _uiState.update { it.copy(isDelayTestingAll = false, delayTestProgress = "") }
            return
        }
        val guids = filteredConfigs().map { it.guid }
        if (guids.isEmpty()) return
        MmkvManager.clearAllTestDelayResults(guids)
        _uiState.update { it.copy(isDelayTestingAll = true, delayTestProgress = "0/${guids.size}") }
        dataSource.sendMsg2TestService(
            TestServiceMessage(
                key = AppConfig.MSG_MEASURE_CONFIG_START,
                subscriptionId = "",
                serverGuids = guids,
                onlyTcp = false
            )
        )
    }

    fun sortConfigsByDelay() {
        viewModelScope.launch(Dispatchers.IO) {
            val activeGuid = MmkvManager.getSelectServer()
            val sorted = _uiState.value.configs.sortedWith(
                compareBy<ConfigUiRow>(
                    { it.guid != activeGuid },
                    { if (it.delayMillis > 0L) 0 else 1 },
                    { if (it.delayMillis > 0L) it.delayMillis else Long.MAX_VALUE },
                )
            )
            AngConfigManager.sortByTestResultsForSub("")
            _uiState.update { it.copy(configs = sorted) }
        }
    }

    fun filteredConfigs(): List<ConfigUiRow> {
        val st = _uiState.value
        val activeGuid = st.selectedGuid
        val q = st.configSearchQuery.trim().lowercase()
        val filtered = st.configs.filter { row ->
            val matchesBucket = when (st.configBucket) {
                ConfigListBucket.ALL -> true
                ConfigListBucket.CDN -> row.pipeline == ConfigPipeline.CDN
                ConfigListBucket.STANDARD -> row.pipeline == ConfigPipeline.STANDARD
            }
            val matchesQuery = q.isEmpty() ||
                row.remarks.lowercase().contains(q) ||
                row.transportBadge.lowercase().contains(q)
            matchesBucket && matchesQuery
        }
        // Always pin connected/active server at TOP regardless of sort
        return filtered.sortedByDescending { it.guid == activeGuid }
    }

    // ---------- Range & Discovery Management (Cloudflare vs CloudFront strict separation) ----------

    fun selectRangeProvider(provider: CdnType) {
        _uiState.update { it.copy(activeRangeProvider = provider, rangeSearchQuery = "") }
        loadRanges(provider)
    }

    fun setRangeSearchQuery(query: String) {
        _uiState.update { it.copy(rangeSearchQuery = query) }
    }

    fun loadRanges(provider: CdnType) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val entries = ProviderRangeStore.getEntries(ctx, provider)
            val syncedAt = ProviderRangeStore.getLastSyncedAt(ctx, CdnType.CLOUDFRONT)
            _uiState.update {
                if (it.activeRangeProvider == provider) {
                    it.copy(rangeEntries = entries, cloudFrontLastSyncedAt = syncedAt)
                } else it
            }
        }
    }

    fun copyVisibleRangesToClipboard() {
        val q = _uiState.value.rangeSearchQuery.trim().lowercase()
        val matching = _uiState.value.rangeEntries.filter {
            q.isEmpty() || it.cidr.lowercase().contains(q)
        }
        val text = matching.joinToString("\n") { it.cidr }
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("CIDR Ranges", text))
        val ctx = getApplication<Application>()
        _uiState.update { it.copy(toastMessage = ctx.getString(R.string.novapulse_copied_ranges_fmt, matching.size)) }
    }

    fun syncCloudFrontOfficialRanges() {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isSyncingAws = true) }
            val result = ProviderRangeStore.syncCloudFrontFromAws(ctx)
            result.onSuccess { cidrs ->
                _uiState.update {
                    it.copy(
                        isSyncingAws = false,
                        toastMessage = ctx.getString(R.string.novapulse_aws_synced_fmt, cidrs.size)
                    )
                }
                loadRanges(CdnType.CLOUDFRONT)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSyncingAws = false,
                        toastMessage = err.message ?: ctx.getString(R.string.novapulse_aws_sync_failed)
                    )
                }
            }
        }
    }

    fun startProviderDiscovery(provider: CdnType) {
        val ctx = getApplication<Application>()
        val guid = MmkvManager.getSelectServer() ?: _uiState.value.configs.firstOrNull()?.guid
        if (guid.isNullOrBlank()) {
            _uiState.update { it.copy(toastMessage = ctx.getString(R.string.novapulse_need_config_first)) }
            return
        }
        _uiState.update {
            it.copy(
                isManualDiscoveryRunning = true,
                manualDiscoveryDone = 0,
                manualDiscoveryTotal = 0,
                manualDiscoveryCidr = ""
            )
        }
        PostConnectDiscoveryRunner.startSequentialDiscovery(
            context = ctx,
            guid = guid,
            providers = listOf(provider),
            force = true
        )
    }

    fun stopManualDiscovery() {
        CloudflareScanner.cancel()
        CloudFrontScanner.cancel()
        PostConnectDiscoveryRunner.cancel()
        _uiState.update { it.copy(isManualDiscoveryRunning = false) }
    }

    // ---------- Settings & Language ----------

    private fun refreshSmartSettings() {
        val lang = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE) ?: "fa"
        _uiState.update {
            it.copy(
                autoPostConnectDiscovery = PostConnectDiscoveryRunner.isAutoDiscoveryEnabled(),
                auto2hOptimize = SmartOptimizeWorker.isEnabled(),
                autoSmartFailover = SmartFailoverStore.isAutoFailoverEnabled(),
                speedDisplayEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED, true),
                lastOptimizeAt = MmkvManager.decodeSettingsLong(SmartOptimizeWorker.PREF_LAST_OPTIMIZE_AT, 0L),
                lastOptimizeIp = MmkvManager.decodeSettingsString(SmartOptimizeWorker.PREF_LAST_OPTIMIZE_IP, "").orEmpty(),
                lastOptimizeUploadKBps = MmkvManager.decodeSettingsLong(SmartOptimizeWorker.PREF_LAST_OPTIMIZE_UPLOAD, 0L),
                currentLanguageCode = lang,
            )
        }
    }

    fun setAutoPostConnectDiscovery(enabled: Boolean) {
        PostConnectDiscoveryRunner.setAutoDiscoveryEnabled(enabled)
        _uiState.update { it.copy(autoPostConnectDiscovery = enabled) }
    }

    fun setAuto2hOptimize(enabled: Boolean) {
        MmkvManager.encodeSettings(PREF_OPTIMIZE_CONSENT_ASKED, true)
        SmartOptimizeWorker.setEnabled(getApplication(), enabled)
        _uiState.update { it.copy(auto2hOptimize = enabled) }
    }

    fun setAutoSmartFailover(enabled: Boolean) {
        SmartFailoverStore.setAutoFailoverEnabled(enabled)
        if (!enabled) {
            SmartFailoverStore.setSmartFailoverArmed(false)
        }
        _uiState.update { it.copy(autoSmartFailover = enabled) }
    }

    fun setSpeedDisplayEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_SPEED_ENABLED, enabled)
        _uiState.update { it.copy(speedDisplayEnabled = enabled) }
    }

    fun setLanguage(code: String) {
        AppLocaleManager.setApplicationLanguage(code)
        _uiState.update { it.copy(currentLanguageCode = code) }
    }

    // ---------- Self-Update Checker & Installer ----------

    fun checkForAppUpdatesIfStale(minIntervalMs: Long = 60_000L) {
        val now = System.currentTimeMillis()
        if ((now - lastUpdateCheckMs) < minIntervalMs) return
        val shouldRemindGently = _uiState.value.let { st ->
            st.availableUpdate?.hasUpdate == true &&
                st.updateDownloadProgress == null &&
                !st.showUpdateDialog &&
                lastUpdateDialogDismissMs > 0L &&
                (now - lastUpdateDialogDismissMs) >= 600_000L
        }
        if (shouldRemindGently) {
            lastUpdateDialogDismissMs = now
        }
        checkForAppUpdates(silent = true, openDialogIfUpdate = shouldRemindGently)
    }

    fun checkForAppUpdates(silent: Boolean = false, openDialogIfUpdate: Boolean = true) {
        if (_uiState.value.isCheckingUpdate) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isCheckingUpdate = true) }
            try {
                lastUpdateCheckMs = System.currentTimeMillis()
                val res = UpdateCheckerManager.checkForUpdate(includePreRelease = false, context = app)
                if (res.hasUpdate) {
                    _uiState.update { cur ->
                        val versionChanged = cur.availableUpdate?.latestVersion != res.latestVersion ||
                            cur.availableUpdate?.latestVersionCode != res.latestVersionCode
                        cur.copy(
                            isCheckingUpdate = false,
                            availableUpdate = res,
                            readyUpdateApkPath = if (versionChanged) null else cur.readyUpdateApkPath,
                            updateDownloadProgress = if (versionChanged) null else cur.updateDownloadProgress,
                            showUpdateDialog = cur.showUpdateDialog || openDialogIfUpdate,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            availableUpdate = null,
                            readyUpdateApkPath = null,
                            showUpdateDialog = false,
                        )
                    }
                    if (!silent) {
                        val ver = BuildConfig.VERSION_NAME
                        _uiState.update {
                            it.copy(toastMessage = app.getString(R.string.novapulse_update_up_to_date, ver))
                        }
                    }
                }
                applyCampaignNotice(res.campaignNotice)
            } catch (_: Exception) {
                _uiState.update { it.copy(isCheckingUpdate = false) }
                if (!silent) {
                    _uiState.update { it.copy(toastMessage = app.getString(R.string.toast_failure)) }
                }
            }
        }
    }

    fun openUpdateDialog(open: Boolean) {
        if (!open) {
            lastUpdateDialogDismissMs = System.currentTimeMillis()
        }
        _uiState.update { it.copy(showUpdateDialog = open) }
        if (open) {
            // Re-verify live so stale banner immediately refreshes if a newer release shipped meanwhile.
            checkForAppUpdates(silent = true, openDialogIfUpdate = true)
        }
    }

    fun downloadAndInstallAppUpdate(option: ApkAssetOption) {
        if (_uiState.value.updateDownloadProgress != null) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isCheckingUpdate = true,
                    updateDownloadProgress = 0,
                    readyUpdateApkPath = null,
                    updateNeedsInstallPerm = false,
                )
            }
            var targetOption = option
            val prevUpdate = _uiState.value.availableUpdate
            val fresh = runCatching {
                UpdateCheckerManager.checkForUpdate(includePreRelease = false, context = app)
            }.getOrNull()
            lastUpdateCheckMs = System.currentTimeMillis()
            if (fresh != null && fresh.hasUpdate) {
                val newerTag = !fresh.latestVersion.isNullOrBlank() && (
                    prevUpdate?.latestVersion.isNullOrBlank() ||
                        UpdateCheckerManager.compareVersions(fresh.latestVersion, prevUpdate?.latestVersion.orEmpty()) > 0
                    )
                val newerCode = fresh.latestVersionCode > (prevUpdate?.latestVersionCode ?: 0)
                if (newerTag || newerCode || prevUpdate == null) {
                    val matchedFresh = fresh.availableAssets.firstOrNull {
                        it.abi.equals(option.abi, ignoreCase = true)
                    } ?: fresh.availableAssets.firstOrNull { it.isDeviceMatch }
                        ?: fresh.availableAssets.firstOrNull { it.isUniversal }
                        ?: fresh.availableAssets.firstOrNull()
                    if (matchedFresh != null) {
                        targetOption = matchedFresh
                    }
                    _uiState.update {
                        it.copy(
                            availableUpdate = fresh,
                            toastMessage = if ((newerTag || newerCode) && !fresh.latestVersion.isNullOrBlank()) {
                                app.getString(R.string.novapulse_update_available_banner_fmt, fresh.latestVersion)
                            } else {
                                it.toastMessage
                            }
                        )
                    }
                }
            }
            _uiState.update { it.copy(isCheckingUpdate = false) }
            try {
                val apkFile = NovaPulseApkDownloader.downloadApk(
                    context = app,
                    downloadUrl = targetOption.downloadUrl,
                    fileName = targetOption.fileName,
                    expectedSha256 = targetOption.sha256,
                ) { pct ->
                    _uiState.update { it.copy(updateDownloadProgress = pct) }
                }
                _uiState.update {
                    it.copy(
                        updateDownloadProgress = null,
                        readyUpdateApkPath = apkFile.absolutePath,
                    )
                }
                when (NovaPulseApkDownloader.launchApkInstaller(app, apkFile)) {
                    is ApkInstallResult.NeedsUnknownSourcesPermission -> {
                        _uiState.update {
                            it.copy(
                                updateNeedsInstallPerm = true,
                                toastMessage = app.getString(R.string.novapulse_update_perm_needed)
                            )
                        }
                        app.startActivity(NovaPulseApkDownloader.buildUnknownSourcesSettingsIntent(app))
                    }
                    is ApkInstallResult.Failed -> {
                        _uiState.update {
                            it.copy(toastMessage = app.getString(R.string.toast_failure))
                        }
                    }
                    ApkInstallResult.LaunchedInstaller -> Unit
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        updateDownloadProgress = null,
                        toastMessage = app.getString(R.string.novapulse_update_failed_fmt, e.message ?: "error")
                    )
                }
            }
        }
    }

    fun installReadyUpdateApk() {
        val path = _uiState.value.readyUpdateApkPath ?: return
        val app = getApplication<Application>()
        val file = File(path)
        when (NovaPulseApkDownloader.launchApkInstaller(app, file)) {
            is ApkInstallResult.NeedsUnknownSourcesPermission -> {
                _uiState.update {
                    it.copy(
                        updateNeedsInstallPerm = true,
                        toastMessage = app.getString(R.string.novapulse_update_perm_needed)
                    )
                }
                app.startActivity(NovaPulseApkDownloader.buildUnknownSourcesSettingsIntent(app))
            }
            is ApkInstallResult.Failed -> {
                _uiState.update { it.copy(toastMessage = app.getString(R.string.toast_failure)) }
            }
            ApkInstallResult.LaunchedInstaller -> {
                _uiState.update { it.copy(updateNeedsInstallPerm = false) }
            }
        }
    }

    override fun onCleared() {
        dataSource.close()
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repo = MainRepository(application as AngApplication)
            return Qs3dViewModel(application, repo) as T
        }
    }
}

internal interface LicenseLogoutStorage {
    fun getActiveLicenseSubId(): String
    fun getAllSubscriptionIds(): List<String>
    fun removeSubscription(subId: String)
    fun removeServersWithoutSubIfNeeded(boundSubIds: Set<String>)
    fun clearQuota(subId: String)
    fun clearSelectedServer()
    fun clearExplicitSelection()
    fun clearActiveLicenseSession()
}

internal object LicenseLogoutOrchestrator {
    suspend fun stopRuntimeAndConfirm(
        isCoreRunning: () -> Boolean,
        cancelBackgroundWork: () -> Unit,
        requestStopVpn: () -> Unit,
        awaitStopEvent: suspend (Long) -> Boolean,
        pollIntervalMs: Long = 150L,
    ): Boolean {
        cancelBackgroundWork()
        if (!isCoreRunning()) {
            return true
        }

        requestStopVpn()
        awaitStopEvent(4500L)

        var elapsed = 0L
        while (isCoreRunning() && elapsed < 1800L) {
            delay(pollIntervalMs)
            elapsed += pollIntervalMs
        }

        if (isCoreRunning()) {
            // Second stop attempt in case first broadcast hit daemon warmup
            requestStopVpn()
            awaitStopEvent(1500L)
            var extraElapsed = 0L
            while (isCoreRunning() && extraElapsed < 1000L) {
                delay(pollIntervalMs)
                extraElapsed += pollIntervalMs
            }
        }

        return !isCoreRunning()
    }

    fun wipeBoundLicenseData(storage: LicenseLogoutStorage) {
        val activeSubId = storage.getActiveLicenseSubId()
        val allSubIds = storage.getAllSubscriptionIds()
        val targetSubIds = if (activeSubId.isNotBlank()) {
            setOf(activeSubId)
        } else {
            allSubIds.toSet()
        }

        for (subId in targetSubIds) {
            storage.clearQuota(subId)
            storage.removeSubscription(subId)
        }
        storage.removeServersWithoutSubIfNeeded(targetSubIds)
        storage.clearSelectedServer()
        storage.clearExplicitSelection()
        storage.clearActiveLicenseSession()
    }
}
