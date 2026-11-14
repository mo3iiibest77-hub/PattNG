package com.v2ray.ang.ui.qsn3d

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.senpai.AutoConfigFilter
import com.v2ray.ang.senpai.CdnDetectionResult
import com.v2ray.ang.senpai.CdnPingClassifier
import com.v2ray.ang.senpai.CdnType
import com.v2ray.ang.senpai.CloudFrontScanner
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.CloudflareSeedRanges
import com.v2ray.ang.senpai.LicenseBackendClient
import com.v2ray.ang.senpai.OriginDomainStore
import com.v2ray.ang.senpai.PostConnectDiscoveryRunner
import com.v2ray.ang.senpai.PostConnectDiscoveryState
import com.v2ray.ang.senpai.ProviderRangeEntry
import com.v2ray.ang.senpai.ProviderRangeStore
import com.v2ray.ang.senpai.SmartConnectCoordinator
import com.v2ray.ang.senpai.SmartFlowState
import com.v2ray.ang.senpai.SmartOptimizeWorker
import com.v2ray.ang.senpai.SubscriptionQuotaInfo
import com.v2ray.ang.senpai.SubscriptionQuotaStore
import com.v2ray.ang.ui.main.MainDataSource
import com.v2ray.ang.ui.main.MainRepository
import com.v2ray.ang.ui.main.MainServiceEvent
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Qs3dTab { Connect, Configs, Ranges, Apps }

enum class ConfigFilterChip { TLS, XHTTP, WS, CLOUDFLARE, CLOUDFRONT, TESTED }

data class SubscriptionUiRow(
    val guid: String,
    val remarks: String,
    val url: String,
    val enabled: Boolean,
    val lastUpdated: Long,
    val configCount: Int,
    val quota: SubscriptionQuotaInfo?,
)

data class ConfigUiRow(
    val guid: String,
    val subscriptionId: String,
    val remarks: String,
    val protocol: String,
    val network: String,
    val security: String,
    val originDomain: String,
    val server: String,
    val port: String,
    val delayMillis: Long,
    val cdnHint: CdnType,
    val eligibleForScan: Boolean,
)

data class Qs3dUiState(
    val activeTab: Qs3dTab = Qs3dTab.Connect,
    val coreRunning: Boolean = false,
    val selectedGuid: String? = null,
    val connectedLatencyMs: Long? = null,
    val connectedTestMessage: String? = null,
    val activationInput: String = "",
    val toastMessage: String? = null,
    val smartFlow: SmartFlowState = SmartFlowState(),
    val discoveryState: PostConnectDiscoveryState = PostConnectDiscoveryState(),
    val lastCdnDetection: CdnDetectionResult? = null,
    val forcedCdn: CdnType? = null,
    val detectingCdn: Boolean = false,
    // Configs & subscriptions
    val subscriptions: List<SubscriptionUiRow> = emptyList(),
    val configs: List<ConfigUiRow> = emptyList(),
    val activeFilters: Set<ConfigFilterChip> = emptySet(),
    val configSearchQuery: String = "",
    val bulkSelectedGuids: Set<String> = emptySet(),
    val isDelayTestingAll: Boolean = false,
    val delayTestProgress: String = "",
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
    // Settings / Smart automation
    val autoPostConnectDiscovery: Boolean = true,
    val auto2hOptimize: Boolean = true,
    val lastOptimizeAt: Long = 0L,
    val lastOptimizeIp: String = "",
    val lastOptimizeUploadKBps: Long = 0L,
    val backendBaseUrl: String = "",
    val currentLanguageCode: String = "auto",
)

class Qs3dViewModel(
    application: Application,
    private val dataSource: MainDataSource,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(Qs3dUiState())
    val uiState: StateFlow<Qs3dUiState> = _uiState.asStateFlow()

    init {
        collectServiceEvents()
        collectCoordinatorFlows()
        refreshAllData()
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                when (event) {
                    MainServiceEvent.StateRunning -> {
                        _uiState.update { it.copy(coreRunning = true) }
                        SmartConnectCoordinator.notifyVpnConnected(getApplication(), MmkvManager.getSelectServer())
                    }

                    MainServiceEvent.StateStartSuccess -> {
                        _uiState.update { it.copy(coreRunning = true) }
                        SmartConnectCoordinator.notifyVpnConnected(getApplication(), MmkvManager.getSelectServer())
                        refreshAllData()
                    }

                    MainServiceEvent.StateNotRunning,
                    MainServiceEvent.StateStopSuccess -> {
                        _uiState.update {
                            it.copy(
                                coreRunning = false,
                                connectedLatencyMs = null,
                                connectedTestMessage = null
                            )
                        }
                    }

                    MainServiceEvent.StateStartFailure -> {
                        _uiState.update {
                            it.copy(
                                coreRunning = false,
                                toastMessage = "VPN core failed to start"
                            )
                        }
                    }

                    is MainServiceEvent.MeasureDelayResult -> {
                        val ms = event.result.delayMillis
                        _uiState.update {
                            it.copy(
                                connectedLatencyMs = if (ms >= 0) ms else null,
                                connectedTestMessage = if (ms >= 0) "${ms} ms (${event.result.country.orEmpty()})"
                                else event.result.errorMessage.ifBlank { "Timeout" }
                            )
                        }
                    }

                    MainServiceEvent.MeasureConfigSuccess -> {
                        loadConfigsAndSubs()
                    }

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
                _uiState.update { it.copy(discoveryState = discState) }
                if (!discState.running) {
                    loadRanges(_uiState.value.activeRangeProvider)
                }
            }
        }
    }

    fun selectTab(tab: Qs3dTab) {
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == Qs3dTab.Ranges) loadRanges(_uiState.value.activeRangeProvider)
        if (tab == Qs3dTab.Configs || tab == Qs3dTab.Connect) loadConfigsAndSubs()
    }

    fun consumeToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun setActivationInput(text: String) {
        _uiState.update { it.copy(activationInput = text) }
    }

    fun setForcedCdn(cdnType: CdnType?) {
        _uiState.update { it.copy(forcedCdn = cdnType) }
    }

    fun refreshAllData() {
        loadConfigsAndSubs()
        loadRanges(_uiState.value.activeRangeProvider)
        refreshSmartSettings()
    }

    fun loadConfigsAndSubs() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val selected = MmkvManager.getSelectServer()
            val subs = MmkvManager.decodeSubscriptions()
            val allGuids = MmkvManager.decodeAllServerList()

            val subCounts = mutableMapOf<String, Int>()
            val frontCidrs = ProviderRangeStore.getEntries(ctx, CdnType.CLOUDFRONT).map { it.cidr }
            val cfCidrs = ProviderRangeStore.getEntries(ctx, CdnType.CLOUDFLARE).map { it.cidr }

            val configRows = allGuids.mapNotNull { guid ->
                val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                OriginDomainStore.rememberIfDomain(guid, profile)
                subCounts[profile.subscriptionId] = (subCounts[profile.subscriptionId] ?: 0) + 1
                val aff = MmkvManager.decodeServerAffiliationInfo(guid)
                val originDomain = OriginDomainStore.getOriginDomain(guid, profile) ?: profile.server.orEmpty()
                val cdnHint = inferCdnHint(profile, originDomain, frontCidrs, cfCidrs)
                ConfigUiRow(
                    guid = guid,
                    subscriptionId = profile.subscriptionId,
                    remarks = profile.remarks.ifBlank { originDomain.ifBlank { "Unnamed" } },
                    protocol = profile.configType.name,
                    network = profile.network.orEmpty(),
                    security = profile.security.orEmpty(),
                    originDomain = originDomain,
                    server = profile.server.orEmpty(),
                    port = profile.serverPort.orEmpty(),
                    delayMillis = aff?.testDelayMillis ?: -1L,
                    cdnHint = cdnHint,
                    eligibleForScan = AutoConfigFilter.isCdnScanEligible(profile)
                )
            }

            val subRows = subs.map { cache ->
                SubscriptionUiRow(
                    guid = cache.guid,
                    remarks = cache.subscription.remarks.ifBlank { "Subscription" },
                    url = cache.subscription.url,
                    enabled = cache.subscription.enabled,
                    lastUpdated = cache.subscription.lastUpdated,
                    configCount = subCounts[cache.guid] ?: 0,
                    quota = SubscriptionQuotaStore.get(cache.guid)
                )
            }

            _uiState.update {
                it.copy(
                    selectedGuid = selected,
                    subscriptions = subRows,
                    configs = configRows
                )
            }
        }
    }

    private fun inferCdnHint(
        profile: ProfileItem,
        originDomain: String,
        frontCidrs: List<String>,
        cfCidrs: List<String>,
    ): CdnType {
        val currentServer = profile.server.orEmpty().trim()
        if (CloudflareSeedRanges.ipv4ToLong(currentServer) != null) {
            if (CloudflareSeedRanges.matchesAnyCidr(currentServer, frontCidrs)) return CdnType.CLOUDFRONT
            if (CloudflareSeedRanges.matchesAnyCidr(currentServer, cfCidrs)) return CdnType.CLOUDFLARE
        }
        val lower = originDomain.lowercase()
        if ("cloudfront" in lower) return CdnType.CLOUDFRONT
        return CdnType.UNKNOWN
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

    fun redeemActivationInput() {
        val input = _uiState.value.activationInput.trim()
        if (input.isEmpty()) {
            _uiState.update { it.copy(toastMessage = "Enter a subscription URL or license code") }
            return
        }
        SmartConnectCoordinator.redeemInputOnly(getApplication(), input) { res ->
            res.onSuccess { count ->
                _uiState.update {
                    it.copy(
                        activationInput = "",
                        toastMessage = "Imported $count configs cleanly"
                    )
                }
                loadConfigsAndSubs()
                detectCdnForSelected()
            }.onFailure { err ->
                _uiState.update { it.copy(toastMessage = err.message ?: "Activation failed") }
            }
        }
    }

    // ---------- Config & Subscription Actions ----------

    fun selectConfig(guid: String) {
        MmkvManager.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
        detectCdnForSelected()
    }

    fun addOrUpdateSubscription(remarks: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val guid = Utils.getUuid()
            val item = SubscriptionItem(
                remarks = remarks.trim().ifBlank { "QuietStorm" },
                url = cleanUrl,
                enabled = true
            )
            MmkvManager.encodeSubscription(guid, item)
            val cache = com.v2ray.ang.dto.entities.SubscriptionCache(guid, item)
            val res = AngConfigManager.updateConfigViaSub(cache)
            SubscriptionQuotaStore.fetchAndSaveHeader(guid, item.url, item.userAgent)
            _uiState.update {
                it.copy(toastMessage = "Subscription added (${res.configCount} configs)")
            }
            loadConfigsAndSubs()
        }
    }

    fun toggleSubscriptionEnabled(guid: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = MmkvManager.decodeSubscription(guid) ?: return@launch
            item.enabled = enabled
            MmkvManager.encodeSubscription(guid, item)
            loadConfigsAndSubs()
        }
    }

    fun renameSubscription(guid: String, newRemarks: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = MmkvManager.decodeSubscription(guid) ?: return@launch
            item.remarks = newRemarks.trim().ifBlank { item.remarks }
            MmkvManager.encodeSubscription(guid, item)
            loadConfigsAndSubs()
        }
    }

    fun deleteSubscription(guid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MmkvManager.removeSubscription(guid)
            loadConfigsAndSubs()
        }
    }

    fun refreshAllSubscriptions() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = SmartConnectCoordinator.refreshAllSubscriptionsWithQuota(getApplication())
            _uiState.update { it.copy(toastMessage = "Updated $count configs") }
            loadConfigsAndSubs()
        }
    }

    fun importClipboardConfigs() {
        val ctx = getApplication<Application>()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            _uiState.update { it.copy(toastMessage = "Clipboard is empty") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val (count, subCount) = AngConfigManager.importBatchConfig(text, "", false)
            _uiState.update {
                it.copy(toastMessage = "Imported $count configs ($subCount subscriptions)")
            }
            loadConfigsAndSubs()
        }
    }

    fun toggleFilter(chip: ConfigFilterChip) {
        _uiState.update { st ->
            val next = if (chip in st.activeFilters) st.activeFilters - chip else st.activeFilters + chip
            st.copy(activeFilters = next)
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

    fun deleteSingleConfig(guid: String) {
        if (guid == MmkvManager.getSelectServer()) {
            _uiState.update { it.copy(toastMessage = "Cannot delete the active configuration") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            MmkvManager.removeServer(guid)
            loadConfigsAndSubs()
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
            AngConfigManager.sortByTestResultsForSub("")
            loadConfigsAndSubs()
        }
    }

    fun removeInvalidConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = MmkvManager.removeInvalidServer("")
            _uiState.update { it.copy(toastMessage = "Removed $removed timed-out configs") }
            loadConfigsAndSubs()
        }
    }

    fun shareQrBitmap(guid: String): Bitmap? = AngConfigManager.share2QRCode(guid)

    fun copyConfigToClipboard(guid: String) {
        val res = AngConfigManager.share2Clipboard(getApplication(), guid)
        if (res == 0) {
            _uiState.update { it.copy(toastMessage = "Config copied to clipboard") }
        }
    }

    fun filteredConfigs(): List<ConfigUiRow> {
        val st = _uiState.value
        val q = st.configSearchQuery.trim().lowercase()
        val f = st.activeFilters
        return st.configs.filter { row ->
            val matchQuery = q.isEmpty() ||
                row.remarks.lowercase().contains(q) ||
                row.originDomain.lowercase().contains(q) ||
                row.server.lowercase().contains(q)
            val matchTls = ConfigFilterChip.TLS !in f || row.security.equals("tls", ignoreCase = true)
            val matchXhttp = ConfigFilterChip.XHTTP !in f || row.network.equals("xhttp", ignoreCase = true)
            val matchWs = ConfigFilterChip.WS !in f || row.network.equals("ws", ignoreCase = true)
            val matchCf = ConfigFilterChip.CLOUDFLARE !in f || row.cdnHint == CdnType.CLOUDFLARE
            val matchFront = ConfigFilterChip.CLOUDFRONT !in f || row.cdnHint == CdnType.CLOUDFRONT
            val matchTested = ConfigFilterChip.TESTED !in f || row.delayMillis > 0L
            matchQuery && matchTls && matchXhttp && matchWs && matchCf && matchFront && matchTested
        }
    }

    // ---------- CDN Range & Discovery Management ----------

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

    fun addManualCidrs(rawInput: String, provider: CdnType = _uiState.value.activeRangeProvider) {
        val tokens = rawInput.split('\n', ',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        var added = 0
        for (token in tokens) {
            if (ProviderRangeStore.addManualCidr(getApplication(), provider, token)) {
                added++
            }
        }
        _uiState.update { it.copy(toastMessage = "Added $added CIDR ranges to ${provider.name}") }
        loadRanges(provider)
    }

    fun toggleCidrEnabled(entry: ProviderRangeEntry) {
        ProviderRangeStore.setCidrEnabled(getApplication(), entry.provider, entry.cidr, !entry.enabled)
        loadRanges(entry.provider)
    }

    fun removeCidr(entry: ProviderRangeEntry) {
        ProviderRangeStore.removeCidr(getApplication(), entry.provider, entry.cidr)
        loadRanges(entry.provider)
    }

    fun resetCloudflareSeeds() {
        ProviderRangeStore.resetCloudflareSeeds(getApplication())
        _uiState.update { it.copy(toastMessage = "Restored all 46 Cloudflare seed ranges") }
        loadRanges(CdnType.CLOUDFLARE)
    }

    fun clearDiscoveredRanges(provider: CdnType) {
        ProviderRangeStore.clearDiscovered(getApplication(), provider)
        loadRanges(provider)
    }

    fun copyVisibleRangesToClipboard() {
        val q = _uiState.value.rangeSearchQuery.trim().lowercase()
        val matching = _uiState.value.rangeEntries.filter {
            q.isEmpty() || it.cidr.lowercase().contains(q)
        }
        val text = matching.joinToString("\n") { it.cidr }
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("CIDR Ranges", text))
        _uiState.update { it.copy(toastMessage = "Copied ${matching.size} CIDRs") }
    }

    fun syncCloudFrontOfficialRanges() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isSyncingAws = true) }
            val result = ProviderRangeStore.syncCloudFrontFromAws(getApplication())
            result.onSuccess { cidrs ->
                _uiState.update {
                    it.copy(
                        isSyncingAws = false,
                        toastMessage = "Synced ${cidrs.size} official AWS CloudFront ranges"
                    )
                }
                loadRanges(CdnType.CLOUDFRONT)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSyncingAws = false,
                        toastMessage = "AWS sync failed: ${err.message}"
                    )
                }
            }
        }
    }

    fun startProviderDiscovery(provider: CdnType) {
        val guid = MmkvManager.getSelectServer() ?: _uiState.value.configs.firstOrNull()?.guid
        if (guid.isNullOrBlank()) {
            _uiState.update { it.copy(toastMessage = "Add or select a config first for Discovery") }
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
        if (provider == CdnType.CLOUDFRONT) {
            CloudFrontScanner.discoverGoodCidrs(
                context = getApplication(),
                guid = guid,
                onProgress = { done, total, cidr, _ ->
                    _uiState.update {
                        it.copy(
                            manualDiscoveryDone = done,
                            manualDiscoveryTotal = total,
                            manualDiscoveryCidr = cidr
                        )
                    }
                },
                onFinish = { found ->
                    _uiState.update {
                        it.copy(
                            isManualDiscoveryRunning = false,
                            toastMessage = "CloudFront Discovery found ${found.size} good ranges"
                        )
                    }
                    loadRanges(CdnType.CLOUDFRONT)
                }
            )
        } else {
            val ispName = com.v2ray.ang.senpai.IspManager.detectIspName(getApplication())
            CloudflareScanner.discoverGoodCidrs(
                context = getApplication(),
                ispName = ispName,
                guid = guid,
                onProgress = { done, total, cidr, _ ->
                    _uiState.update {
                        it.copy(
                            manualDiscoveryDone = done,
                            manualDiscoveryTotal = total,
                            manualDiscoveryCidr = cidr
                        )
                    }
                },
                onFinish = { found ->
                    _uiState.update {
                        it.copy(
                            isManualDiscoveryRunning = false,
                            toastMessage = "Cloudflare Discovery found ${found.size} good ranges"
                        )
                    }
                    loadRanges(CdnType.CLOUDFLARE)
                }
            )
        }
    }

    fun stopManualDiscovery() {
        CloudflareScanner.cancel()
        CloudFrontScanner.cancel()
        PostConnectDiscoveryRunner.cancel()
        _uiState.update { it.copy(isManualDiscoveryRunning = false) }
    }

    fun runSequentialDiscoveryNow() {
        val guid = MmkvManager.getSelectServer() ?: _uiState.value.configs.firstOrNull()?.guid
        if (guid.isNullOrBlank()) {
            _uiState.update { it.copy(toastMessage = "Add or select a config first") }
            return
        }
        PostConnectDiscoveryRunner.startSequentialDiscovery(getApplication(), guid, force = true)
    }

    // ---------- Settings & Language ----------

    private fun refreshSmartSettings() {
        val lang = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE) ?: "auto"
        _uiState.update {
            it.copy(
                autoPostConnectDiscovery = PostConnectDiscoveryRunner.isAutoDiscoveryEnabled(),
                auto2hOptimize = SmartOptimizeWorker.isEnabled(),
                lastOptimizeAt = MmkvManager.decodeSettingsLong(SmartOptimizeWorker.PREF_LAST_OPTIMIZE_AT, 0L),
                lastOptimizeIp = MmkvManager.decodeSettingsString(SmartOptimizeWorker.PREF_LAST_OPTIMIZE_IP, "").orEmpty(),
                lastOptimizeUploadKBps = MmkvManager.decodeSettingsLong(SmartOptimizeWorker.PREF_LAST_OPTIMIZE_UPLOAD, 0L),
                backendBaseUrl = LicenseBackendClient.getBackendBaseUrl(),
                currentLanguageCode = lang
            )
        }
    }

    fun setAutoPostConnectDiscovery(enabled: Boolean) {
        PostConnectDiscoveryRunner.setAutoDiscoveryEnabled(enabled)
        _uiState.update { it.copy(autoPostConnectDiscovery = enabled) }
    }

    fun setAuto2hOptimize(enabled: Boolean) {
        SmartOptimizeWorker.setEnabled(getApplication(), enabled)
        _uiState.update { it.copy(auto2hOptimize = enabled) }
    }

    fun saveBackendBaseUrl(url: String) {
        LicenseBackendClient.setBackendBaseUrl(url)
        _uiState.update {
            it.copy(
                backendBaseUrl = LicenseBackendClient.getBackendBaseUrl(),
                toastMessage = "Backend URL saved"
            )
        }
    }

    fun setLanguage(code: String) {
        AppLocaleManager.setApplicationLanguage(code)
        _uiState.update { it.copy(currentLanguageCode = code) }
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
