package com.v2ray.ang.ui.qsn

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.connect.AutoConfigFilter
import com.v2ray.ang.ui.connect.AutoConnectResult
import com.v2ray.ang.ui.connect.AutoConnectState
import com.v2ray.ang.ui.connect.ConnectionOrchestrator
import com.v2ray.ang.ui.main.MainDataSource
import com.v2ray.ang.ui.main.MainServiceEvent
import com.v2ray.ang.ui.qsn.data.QsPrefs
import com.v2ray.ang.ui.qsn.domain.ConfigFilter
import com.v2ray.ang.ui.qsn.domain.ConfigFilterEngine
import com.v2ray.ang.ui.qsn.domain.ConfigRow
import com.v2ray.ang.ui.qsn.domain.ConfigSource
import com.v2ray.ang.ui.qsn.domain.ManualConfigRules
import com.v2ray.ang.ui.qsn.domain.RangeError
import com.v2ray.ang.ui.qsn.domain.RangeParseResult
import com.v2ray.ang.ui.qsn.domain.ScanRange
import com.v2ray.ang.ui.qsn.domain.ScanRangeParser
import com.v2ray.ang.ui.qsn.domain.SubscriptionRow
import com.v2ray.ang.ui.qsn.domain.SubscriptionRules
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "QsViewModel"

/** How long to wait for MSG_STATE_STOP_SUCCESS before probing the daemon. */
private const val STOP_CONFIRM_TIMEOUT_MS = 8_000L

/** Grace period for the MSG_REGISTER_CLIENT reply after a stop timeout. */
private const val PROBE_GRACE_MS = 1_500L

/** Remarks used when a subscription is created from the URL field. */
private const val DEFAULT_SUBSCRIPTION_REMARKS = "Quiet Storm"

/** Outcome of a manual import, kept small so it can be logged safely. */
private data class ImportOutcome(
    val added: Int = 0,
    val duplicates: Int = 0,
    val failed: Boolean = false,
)

/**
 * State holder for Quiet Storm NG.
 *
 * It owns four things and nothing else:
 *  - the connection state machine, driven by [ConnectionOrchestrator] and by the real daemon
 *    broadcasts that [MainDataSource] forwards,
 *  - the stored configurations, subscriptions and scan ranges, always re-read from MMKV,
 *  - the user facing error text,
 *  - the transient dialog state of the configuration screens.
 *
 * All blocking work (asset init, subscription fetch, imports, the whole auto-connect flow) runs
 * off the main thread. The orchestrator dispatches internally as well, so a caller cannot cause
 * an ANR.
 */
class QsViewModel(
    application: Application,
    private val dataSource: MainDataSource,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(QsUiState())
    val uiState: StateFlow<QsUiState> = _uiState.asStateFlow()

    private val orchestrator = ConnectionOrchestrator()
    private var flowJob: Job? = null
    private var stopWatchJob: Job? = null
    private var scanJob: Job? = null

    /** Last URL persisted to MMKV, used to detect unsaved edits before connecting. */
    private var storedUrl: String = ""

    init {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event -> onServiceEvent(event) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dataSource.initAssets()
            } catch (e: Exception) {
                LogUtil.e(TAG, "initAssets failed: " + e.javaClass.simpleName)
            }
            try {
                dataSource.syncSubscriptions()
            } catch (e: Exception) {
                LogUtil.e(TAG, "syncSubscriptions failed: " + e.javaClass.simpleName)
            }
        }
        refreshLocalSnapshot()
        // MainRepository probes the daemon in its constructor, which can be
        // before this collector exists, so the reply may be dropped. Probe
        // again once the collector is attached to restore the real state of a
        // daemon that survived an app restart.
        viewModelScope.launch {
            delay(400)
            dataSource.sendMsg2Service(AppConfig.MSG_REGISTER_CLIENT, "")
        }
    }

    // ---------- Connection ----------

    fun onSubscriptionInputChange(value: String) {
        _uiState.update { it.copy(subscriptionUrl = value, inputError = null) }
    }

    /**
     * Persists the URL in the text field as the active subscription.
     *
     * @return true when a valid URL is stored.
     */
    fun saveSubscription(raw: String): Boolean {
        val url = QsUrlValidator.normalize(raw)
        if (url == null) {
            _uiState.update { it.copy(inputError = getString(R.string.qs_error_bad_url)) }
            return false
        }
        val subs = MmkvManager.decodeSubscriptions()
        val existing = SubscriptionRules.chooseActive(subs, QsPrefs.activeSubscriptionGuid())
        val item = existing?.subscription?.copy(url = url)
            ?: SubscriptionItem(remarks = DEFAULT_SUBSCRIPTION_REMARKS, url = url, enabled = true)
        if (item.remarks.isBlank()) item.remarks = DEFAULT_SUBSCRIPTION_REMARKS
        val guid = existing?.guid ?: Utils.getUuid()
        MmkvManager.encodeSubscription(guid, item)
        QsPrefs.setActiveSubscriptionGuid(guid)
        storedUrl = url
        _uiState.update {
            it.copy(
                subscriptionUrl = url,
                hasSubscription = true,
                inputError = null,
                message = null,
            )
        }
        refreshLocalSnapshot()
        return true
    }

    /** Downloads the active subscription again and reports how many configs it produced. */
    fun refreshSubscription() {
        if (_uiState.value.refreshing) return
        val target = SubscriptionRules.chooseActive(
            MmkvManager.decodeSubscriptions(),
            QsPrefs.activeSubscriptionGuid(),
        )
        if (target == null) {
            _uiState.update { it.copy(inputError = getString(R.string.qs_error_no_subscription)) }
            return
        }
        runUpdate(listOf(target))
    }

    /** Starts the full automatic flow. Ignored while another attempt is running. */
    fun connect() {
        if (flowJob?.isActive == true) return
        val state = _uiState.value
        if (!state.hasAnyConfig && state.subscriptionUrl.isBlank()) {
            _uiState.update { it.copy(inputError = getString(R.string.qs_error_no_config)) }
            return
        }
        if (state.subscriptionUrl.isNotBlank() && state.subscriptionUrl.trim() != storedUrl) {
            if (!saveSubscription(state.subscriptionUrl)) return
        }
        // Manual-only setups have no subscription to refresh, so the connect
        // flow needs an explicit target. Pin the first eligible configuration.
        if (QsPrefs.pinnedGuid() == null && state.subscriptions.none { it.enabled }) {
            val auto = state.configRows.firstOrNull { it.eligible }
            if (auto != null) {
                QsPrefs.setPinnedGuid(auto.guid)
                MmkvManager.setSelectServer(auto.guid)
            }
        }

        flowJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = QsStatus.Connecting,
                    step = QsStep.Preparing,
                    busy = true,
                    message = null,
                    inputError = null,
                    scanDone = 0,
                    scanTotal = 0,
                )
            }
            val result = orchestrator.autoConnect(getApplication()) { orchestratorState ->
                _uiState.update { current -> current.withConnectState(orchestratorState) }
            }
            _uiState.update { current ->
                when (result) {
                    is AutoConnectResult.Success -> current.copy(
                        status = QsStatus.Connected,
                        step = QsStep.Connected,
                        busy = false,
                        coreRunning = true,
                        message = null,
                    )

                    is AutoConnectResult.Failure -> current.copy(
                        // Never claim Connected unless the core really is up.
                        status = if (current.coreRunning) QsStatus.Connected else QsStatus.Error,
                        busy = false,
                        message = current.message ?: result.reason,
                    )

                    AutoConnectResult.Cancelled -> current.copy(
                        status = if (current.coreRunning) QsStatus.Connected else QsStatus.Disconnected,
                        step = QsStep.Idle,
                        busy = false,
                        message = getString(R.string.qs_cancelled),
                    )
                }
            }
            refreshLocalSnapshot()
        }
    }

    /** Cancels the running auto-connect attempt; the flow reports Cancelled itself. */
    fun cancel() {
        if (flowJob?.isActive != true) return
        _uiState.update { it.copy(message = getString(R.string.qs_cancelling)) }
        orchestrator.cancel()
    }

    /** Stops the tunnel and waits for the daemon to confirm. */
    fun disconnect() {
        if (_uiState.value.status == QsStatus.Disconnecting) return
        stopWatchJob?.cancel()
        _uiState.update {
            it.copy(status = QsStatus.Disconnecting, busy = true, step = QsStep.Idle, message = null)
        }
        LauncherManager.stopService(getApplication())
        stopWatchJob = viewModelScope.launch {
            delay(STOP_CONFIRM_TIMEOUT_MS)
            if (_uiState.value.status != QsStatus.Disconnecting) return@launch
            // No STOP_SUCCESS yet: ask the daemon for its real state instead of
            // guessing. A RUNNING reply flips the UI back to Connected.
            dataSource.sendMsg2Service(AppConfig.MSG_REGISTER_CLIENT, "")
            delay(PROBE_GRACE_MS)
            _uiState.update {
                if (it.status != QsStatus.Disconnecting) {
                    it
                } else {
                    it.copy(
                        status = QsStatus.Disconnected,
                        busy = false,
                        coreRunning = false,
                        message = getString(R.string.qs_stop_unconfirmed),
                    )
                }
            }
        }
    }

    /** Clears the error/info card without changing the connection state. */
    fun dismissMessage() {
        _uiState.update { it.copy(message = null, inputError = null) }
    }

    /** Called by the activity when the user dismisses the system VPN consent dialog. */
    fun onVpnPermissionDenied() {
        _uiState.update {
            it.copy(
                status = QsStatus.Error,
                step = QsStep.Idle,
                busy = false,
                message = getString(R.string.qs_error_vpn_denied),
            )
        }
    }

    /**
     * Re-reads the stored lists without touching the subscription URL field.
     *
     * Used when returning to the screen: refreshing the URL field there would
     * discard text the user typed but has not saved yet.
     */
    fun reload() {
        refreshSubscriptions()
        refreshConfigRows()
        refreshRanges()
        refreshSelectedProfile()
        _uiState.update { it.copy(lastScanAt = QsPrefs.lastScanAt()) }
    }

    // ---------- Configurations ----------

    fun toggleFilter(filter: ConfigFilter) {
        _uiState.update { it.copy(filters = ConfigFilterEngine.toggle(it.filters, filter)) }
    }

    fun clearFilters() {
        _uiState.update { it.copy(filters = emptySet()) }
    }

    /**
     * Pins a configuration so the next connect uses it verbatim, without
     * refreshing any subscription first. This is what makes a manually
     * imported configuration usable.
     */
    fun selectConfig(guid: String) {
        if (MmkvManager.decodeServerConfig(guid) == null) return
        QsPrefs.setPinnedGuid(guid)
        MmkvManager.setSelectServer(guid)
        _uiState.update { it.copy(message = null, inputError = null) }
        refreshLocalSnapshot()
    }

    fun requestDeleteConfig(guid: String) {
        _uiState.update { it.copy(pendingDeleteGuid = guid) }
    }

    fun dismissDeleteConfig() {
        _uiState.update { it.copy(pendingDeleteGuid = null) }
    }

    fun deleteConfig(guid: String) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                try {
                    MmkvManager.removeServer(guid)
                    true
                } catch (e: Exception) {
                    LogUtil.e(TAG, "deleteConfig failed: " + e.javaClass.simpleName)
                    false
                }
            }
            if (QsPrefs.pinnedGuid() == guid) QsPrefs.setPinnedGuid(null)
            _uiState.update {
                it.copy(
                    pendingDeleteGuid = null,
                    message = if (removed) null else getString(R.string.qs_error_delete_failed),
                )
            }
            refreshLocalSnapshot()
        }
    }

    /**
     * Copies a configuration into the manual group.
     *
     * Subscription configurations are replaced on every refresh, so editing one
     * in place would be lost; a copy in the manual group survives.
     */
    fun duplicateConfigToManual(guid: String) {
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) {
                try {
                    val profile = MmkvManager.decodeServerConfig(guid) ?: return@withContext false
                    val copy = profile.copy(
                        subscriptionId = AppConfig.DEFAULT_SUBSCRIPTION_ID,
                        addedTime = System.currentTimeMillis(),
                        remarks = profile.remarks + " " + getString(R.string.qs_copy_suffix),
                    )
                    MmkvManager.encodeServerConfig(Utils.getUuid(), copy)
                    true
                } catch (e: Exception) {
                    LogUtil.e(TAG, "duplicateConfigToManual failed: " + e.javaClass.simpleName)
                    false
                }
            }
            _uiState.update {
                it.copy(
                    message = if (created) {
                        getString(R.string.qs_copied_to_manual)
                    } else {
                        getString(R.string.qs_error_copy_failed)
                    },
                )
            }
            refreshLocalSnapshot()
        }
    }

    fun openImport() {
        _uiState.update { it.copy(importOpen = true, importError = null) }
    }

    fun closeImport() {
        _uiState.update { it.copy(importOpen = false, importError = null) }
    }

    /**
     * Imports one or more share links into the manual group.
     *
     * Duplicates are detected by hashing the connection identity of every
     * profile before and after the import, and the freshly imported copies of
     * an already stored configuration are removed again. Nothing about the
     * link (which contains the credential) is ever logged.
     */
    fun importManualConfig(text: String) {
        if (text.isBlank()) {
            _uiState.update { it.copy(importError = getString(R.string.qs_error_import_empty)) }
            return
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runImport(text) }
            when {
                outcome.failed -> _uiState.update {
                    it.copy(importError = getString(R.string.qs_error_import_invalid))
                }

                outcome.added <= 0 && outcome.duplicates > 0 -> _uiState.update {
                    it.copy(
                        importOpen = false,
                        importError = null,
                        message = getString(R.string.qs_import_duplicate_only),
                    )
                }

                outcome.added <= 0 -> _uiState.update {
                    it.copy(importError = getString(R.string.qs_error_import_invalid))
                }

                else -> _uiState.update {
                    it.copy(
                        importOpen = false,
                        importError = null,
                        message = if (outcome.duplicates > 0) {
                            getString(R.string.qs_import_partial, outcome.added, outcome.duplicates)
                        } else {
                            getString(R.string.qs_import_done, outcome.added)
                        },
                    )
                }
            }
            refreshLocalSnapshot()
        }
    }

    // ---------- Subscriptions ----------

    fun toggleSubscriptionsExpanded() {
        _uiState.update { it.copy(subscriptionsExpanded = !it.subscriptionsExpanded) }
    }

    /**
     * Adds a subscription.
     *
     * @return true when it was stored.
     */
    fun addSubscription(remarks: String, url: String): Boolean {
        val normalized = QsUrlValidator.normalize(url)
        if (normalized == null) {
            _uiState.update { it.copy(inputError = getString(R.string.qs_error_bad_url)) }
            return false
        }
        val name = SubscriptionRules.normalizeRemarks(remarks)
            ?: if (remarks.isBlank()) {
                SubscriptionRules.hostOf(normalized)
            } else {
                _uiState.update { it.copy(inputError = getString(R.string.qs_error_bad_name)) }
                return false
            }
        val item = SubscriptionItem(remarks = name, url = normalized, enabled = true)
        val guid = Utils.getUuid()
        MmkvManager.encodeSubscription(guid, item)
        if (QsPrefs.activeSubscriptionGuid() == null) QsPrefs.setActiveSubscriptionGuid(guid)
        _uiState.update { it.copy(inputError = null, subscriptionsExpanded = true) }
        refreshLocalSnapshot()
        return true
    }

    fun renameSubscription(guid: String, remarks: String) {
        val name = SubscriptionRules.normalizeRemarks(remarks)
        if (name == null) {
            _uiState.update { it.copy(inputError = getString(R.string.qs_error_bad_name)) }
            return
        }
        val item = MmkvManager.decodeSubscription(guid) ?: return
        item.remarks = name
        MmkvManager.encodeSubscription(guid, item)
        _uiState.update { it.copy(inputError = null) }
        refreshLocalSnapshot()
    }

    fun setSubscriptionEnabled(guid: String, enabled: Boolean) {
        val item = MmkvManager.decodeSubscription(guid) ?: return
        if (item.enabled == enabled) return
        item.enabled = enabled
        MmkvManager.encodeSubscription(guid, item)
        // A disabled subscription must not stay the active one.
        if (!enabled && QsPrefs.activeSubscriptionGuid() == guid) {
            QsPrefs.setActiveSubscriptionGuid(null)
        }
        refreshLocalSnapshot()
    }

    fun setActiveSubscription(guid: String) {
        val item = MmkvManager.decodeSubscription(guid) ?: return
        if (!item.enabled) return
        QsPrefs.setActiveSubscriptionGuid(guid)
        // Selecting a subscription means the subscription path, not a pin.
        QsPrefs.setPinnedGuid(null)
        refreshLocalSnapshot()
    }

    fun updateSubscription(guid: String) {
        if (_uiState.value.refreshing) return
        val cache = MmkvManager.decodeSubscriptions().firstOrNull { it.guid == guid } ?: return
        runUpdate(listOf(cache))
    }

    fun updateAllSubscriptions() {
        if (_uiState.value.refreshing) return
        val caches = MmkvManager.decodeSubscriptions().filter { it.subscription.enabled }
        if (caches.isEmpty()) {
            _uiState.update { it.copy(inputError = getString(R.string.qs_error_no_subscription)) }
            return
        }
        runUpdate(caches)
    }

    fun requestDeleteSubscription(guid: String) {
        _uiState.update { it.copy(pendingDeleteSubscription = guid) }
    }

    fun dismissDeleteSubscription() {
        _uiState.update { it.copy(pendingDeleteSubscription = null) }
    }

    fun deleteSubscription(guid: String) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                try {
                    MmkvManager.removeSubscription(guid)
                    true
                } catch (e: Exception) {
                    LogUtil.e(TAG, "deleteSubscription failed: " + e.javaClass.simpleName)
                    false
                }
            }
            if (QsPrefs.activeSubscriptionGuid() == guid) QsPrefs.setActiveSubscriptionGuid(null)
            _uiState.update {
                it.copy(
                    pendingDeleteSubscription = null,
                    message = if (removed) null else getString(R.string.qs_error_delete_failed),
                )
            }
            refreshLocalSnapshot()
        }
    }

    // ---------- IP ranges ----------

    fun addRange(input: String) {
        when (val parsed = ScanRangeParser.parse(input)) {
            is RangeParseResult.Invalid -> _uiState.update {
                it.copy(rangeError = rangeMessage(parsed.reason))
            }

            is RangeParseResult.Valid -> {
                val current = _uiState.value.ranges
                if (current.any { it.cidr == parsed.range.cidr }) {
                    _uiState.update { it.copy(rangeError = getString(R.string.qs_range_duplicate)) }
                    return
                }
                val next = current + parsed.range
                QsPrefs.saveRanges(next)
                // The cached edge belongs to the previous range set.
                QsPrefs.clearSelection()
                _uiState.update { it.copy(ranges = next, rangeError = null) }
            }
        }
    }

    fun removeRange(cidr: String) {
        val next = _uiState.value.ranges.filterNot { it.cidr == cidr }
        QsPrefs.saveRanges(next)
        QsPrefs.clearSelection()
        _uiState.update { it.copy(ranges = next, rangeError = null) }
    }

    fun toggleRange(cidr: String) {
        val next = _uiState.value.ranges.map {
            if (it.cidr == cidr) it.copy(enabled = !it.enabled) else it
        }
        QsPrefs.saveRanges(next)
        QsPrefs.clearSelection()
        _uiState.update { it.copy(ranges = next) }
    }

    /** Forgets the saved edge so the next connect scans again. */
    fun rescanOnNextConnect() {
        QsPrefs.clearSelection()
        _uiState.update { it.copy(message = getString(R.string.qs_range_rescan_queued)) }
    }

    /**
     * Runs a standalone scan with the current ranges and stores the winner.
     *
     * It does not start or stop the tunnel: the connection state is untouched,
     * only the scan progress fields are updated.
     */
    fun scanNow() {
        if (scanJob?.isActive == true) return
        val target = connectTarget()
        if (target == null) {
            _uiState.update { it.copy(rangeError = getString(R.string.qs_range_scan_needs_config)) }
            return
        }
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(scanningNow = true, scanDone = 0, scanTotal = 0, rangeError = null) }
            val result = orchestrator.scanOnly(getApplication(), target.first, target.second) { state ->
                when (state) {
                    is AutoConnectState.ScanProgress -> _uiState.update {
                        it.copy(scanningNow = true, scanDone = state.done, scanTotal = state.total)
                    }

                    AutoConnectState.ScanningCloudflareIps -> _uiState.update {
                        it.copy(scanningNow = true, scanDone = 0, scanTotal = 0)
                    }

                    is AutoConnectState.Failed -> _uiState.update {
                        it.copy(scanningNow = false, rangeError = state.reason)
                    }

                    else -> Unit
                }
            }
            _uiState.update {
                if (result == null) {
                    it.copy(scanningNow = false, rangeError = getString(R.string.qs_range_scan_none))
                } else {
                    it.copy(
                        scanningNow = false,
                        rangeError = null,
                        lastScanAt = System.currentTimeMillis(),
                        message = getString(
                            R.string.qs_range_scan_best,
                            result.ip,
                            result.latencyMs,
                            result.score.toInt(),
                        ),
                    )
                }
            }
        }
    }

    // ---------- Internal ----------

    /** Runs one subscription refresh; blocking network work stays off the main thread. */
    private fun runUpdate(targets: List<SubscriptionCache>) {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, message = null, inputError = null) }
            val results = withContext(Dispatchers.IO) {
                targets.map { cache ->
                    try {
                        dataSource.updateConfigViaSub(cache).configCount
                    } catch (e: Exception) {
                        LogUtil.e(TAG, "subscription update failed: " + e.javaClass.simpleName)
                        0
                    }
                }
            }
            val updated = results.count { it > 0 }
            val failed = results.count { it <= 0 }
            _uiState.update {
                it.copy(
                    refreshing = false,
                    message = when {
                        updated == 0 -> getString(R.string.qs_error_update_failed)
                        failed == 0 -> getString(R.string.qs_update_done, updated)
                        else -> getString(R.string.qs_update_partial, updated, failed)
                    },
                )
            }
            refreshLocalSnapshot()
        }
    }

    /** Imports [text] and removes the copies that duplicate an existing profile. */
    private suspend fun runImport(text: String): ImportOutcome {
        val before = MmkvManager.decodeAllServerList().toSet()
        val beforeKeys = before.mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { ManualConfigRules.keyOf(it) }
        }.toSet()
        val (configs, subscriptions) = try {
            dataSource.importBatchConfig(text, AppConfig.DEFAULT_SUBSCRIPTION_ID, true)
        } catch (e: Exception) {
            LogUtil.e(TAG, "import failed: " + e.javaClass.simpleName)
            return ImportOutcome(failed = true)
        }
        if (configs <= 0 && subscriptions <= 0) return ImportOutcome(failed = true)

        val added = MmkvManager.decodeAllServerList().filterNot { before.contains(it) }
        var duplicates = 0
        var kept = 0
        added.forEach { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@forEach
            val key = ManualConfigRules.keyOf(profile)
            if (beforeKeys.contains(key)) {
                duplicates++
                try {
                    MmkvManager.removeServer(guid)
                } catch (e: Exception) {
                    LogUtil.e(TAG, "duplicate cleanup failed: " + e.javaClass.simpleName)
                }
            } else {
                kept++
            }
        }
        return ImportOutcome(added = kept, duplicates = duplicates)
    }

    /**
     * The configuration the standalone scan should use: the pinned one, else
     * the selected one, else the first eligible row.
     */
    private fun connectTarget(): Pair<String, ProfileItem>? {
        val pinned = QsPrefs.pinnedGuid()
            ?.let { guid -> MmkvManager.decodeServerConfig(guid)?.let { guid to it } }
        if (pinned != null && AutoConfigFilter.isEligible(pinned.second)) return pinned
        val selected = MmkvManager.getSelectServer()
            ?.let { guid -> MmkvManager.decodeServerConfig(guid)?.let { guid to it } }
        if (selected != null && AutoConfigFilter.isEligible(selected.second)) return selected
        return _uiState.value.configRows.firstOrNull { it.eligible }?.let { row ->
            MmkvManager.decodeServerConfig(row.guid)?.let { row.guid to it }
        }
    }

    private fun rangeMessage(reason: RangeError): String = when (reason) {
        RangeError.EMPTY -> getString(R.string.qs_range_error_empty)
        RangeError.BAD_FORMAT -> getString(R.string.qs_range_error_format)
        RangeError.BAD_IP -> getString(R.string.qs_range_error_ip)
        RangeError.BAD_PREFIX -> getString(R.string.qs_range_error_prefix)
        RangeError.TOO_LARGE -> getString(R.string.qs_range_error_large, ScanRangeParser.MIN_PREFIX)
    }

    private fun onServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning,
            MainServiceEvent.StateStartSuccess -> onCoreRunning(true)

            MainServiceEvent.StateNotRunning,
            MainServiceEvent.StateStopSuccess -> onCoreRunning(false)

            MainServiceEvent.StateStartFailure -> {
                _uiState.update {
                    if (it.status == QsStatus.Connecting) {
                        // The flow reports the failure with a precise reason.
                        it.copy(coreRunning = false)
                    } else {
                        it.copy(
                            coreRunning = false,
                            status = QsStatus.Error,
                            busy = false,
                            message = getString(R.string.qs_error_core_start),
                        )
                    }
                }
            }

            else -> Unit
        }
    }

    private fun onCoreRunning(running: Boolean) {
        if (!running) stopWatchJob?.cancel()
        _uiState.update { state ->
            if (running) {
                val keepConnecting = state.status == QsStatus.Connecting
                state.copy(
                    coreRunning = true,
                    status = if (keepConnecting) QsStatus.Connecting else QsStatus.Connected,
                    busy = if (keepConnecting) state.busy else false,
                    message = if (keepConnecting) state.message else null,
                )
            } else {
                val keepConnecting = state.status == QsStatus.Connecting
                state.copy(
                    coreRunning = false,
                    status = if (keepConnecting) QsStatus.Connecting else QsStatus.Disconnected,
                    busy = if (keepConnecting) state.busy else false,
                )
            }
        }
    }

    /** Re-reads every list and the subscription URL from MMKV. */
    private fun refreshLocalSnapshot() {
        val caches = MmkvManager.decodeSubscriptions()
        val activeGuid = QsPrefs.activeSubscriptionGuid()
        val target = SubscriptionRules.chooseActive(caches, activeGuid) ?: caches.firstOrNull()
        val url = target?.subscription?.url.orEmpty()
        storedUrl = url
        _uiState.update {
            it.copy(
                subscriptionUrl = url,
                hasSubscription = target != null && url.isNotBlank(),
                configCount = target?.let { sub -> MmkvManager.decodeServerList(sub.guid).size } ?: 0,
                lastUpdated = target?.subscription?.lastUpdated ?: -1L,
                lastScanAt = QsPrefs.lastScanAt(),
            )
        }
        refreshSubscriptions(caches, activeGuid)
        refreshConfigRows()
        refreshRanges()
        refreshSelectedProfile()
    }

    private fun refreshSubscriptions(
        caches: List<SubscriptionCache> = MmkvManager.decodeSubscriptions(),
        activeGuid: String? = QsPrefs.activeSubscriptionGuid(),
    ) {
        val effectiveActive = SubscriptionRules.chooseActive(caches, activeGuid)?.guid
        val rows = caches.map { cache ->
            SubscriptionRules.fromCache(
                cache = cache,
                configCount = MmkvManager.decodeServerList(cache.guid).size,
                active = cache.guid == effectiveActive,
            )
        }
        val sorted = SubscriptionRules.sortForDisplay(rows)
        _uiState.update { it.copy(subscriptions = sorted) }
    }

    private fun refreshConfigRows() {
        val selectedGuid = MmkvManager.getSelectServer()
        val rows = MmkvManager.decodeAllServerList().distinct().mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            val manual = profile.subscriptionId.isBlank() ||
                profile.subscriptionId == AppConfig.DEFAULT_SUBSCRIPTION_ID
            ConfigRow.from(
                guid = guid,
                profile = profile,
                source = if (manual) ConfigSource.Manual else ConfigSource.Subscription,
                active = guid == selectedGuid,
            )
        }
        _uiState.update { it.copy(configRows = rows) }
    }

    private fun refreshRanges() {
        _uiState.update { it.copy(ranges = QsPrefs.loadRanges()) }
    }

    private fun refreshSelectedProfile() {
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        _uiState.update {
            it.copy(
                selectedRemarks = profile?.remarks?.takeIf { remarks -> remarks.isNotBlank() },
                selectedServer = profile?.server?.takeIf { server -> server.isNotBlank() },
            )
        }
    }

    private fun getString(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    override fun onCleared() {
        super.onCleared()
        orchestrator.cancel()
        stopWatchJob?.cancel()
        scanJob?.cancel()
        try {
            dataSource.close()
        } catch (e: Exception) {
            LogUtil.e(TAG, "dataSource.close failed: " + e.javaClass.simpleName)
        }
    }

    class Factory(
        private val application: Application,
        private val dataSource: MainDataSource,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(QsViewModel::class.java)) {
                return QsViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: " + modelClass.name)
        }
    }
}
