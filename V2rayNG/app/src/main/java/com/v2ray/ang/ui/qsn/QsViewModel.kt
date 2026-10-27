package com.v2ray.ang.ui.qsn

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.ui.connect.AutoConfigFilter
import com.v2ray.ang.ui.connect.AutoConnectResult
import com.v2ray.ang.ui.connect.AutoConnectState
import com.v2ray.ang.ui.connect.ConnectionOrchestrator
import com.v2ray.ang.ui.connect.Ineligibility
import com.v2ray.ang.ui.main.MainDataSource
import com.v2ray.ang.ui.main.MainServiceEvent
import com.v2ray.ang.ui.qsn.data.QsCrashRecorder
import com.v2ray.ang.ui.qsn.data.QsPrefs
import com.v2ray.ang.ui.qsn.data.QualifiedRangeStore
import com.v2ray.ang.ui.qsn.domain.ConfigFilter
import com.v2ray.ang.ui.qsn.domain.ConfigFilterEngine
import com.v2ray.ang.ui.qsn.domain.ConfigRow
import com.v2ray.ang.ui.qsn.domain.ConfigSelection
import com.v2ray.ang.ui.qsn.domain.ConfigSource
import com.v2ray.ang.ui.qsn.domain.CrashReport
import com.v2ray.ang.ui.qsn.domain.LanguageToggle
import com.v2ray.ang.ui.qsn.domain.ManualConfigRules
import com.v2ray.ang.ui.qsn.domain.NotificationDefaults
import com.v2ray.ang.ui.qsn.domain.SubscriptionPanelRules
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
    /** Profiles stored by the parser but rejected by the shared eligibility rule. */
    val rejected: Int = 0,
    /** First rejection reason, so the user is told why and not only that. */
    val rejectReason: Ineligibility? = null,
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

    /** Configuration the standalone scan measured, so a confirmed card can be applied. */
    private var lastScanTarget: Pair<String, ProfileItem>? = null
    private var updateJob: Job? = null

    /** Last URL persisted to MMKV, used to detect unsaved edits before connecting. */
    private var storedUrl: String = ""

    init {
        // Fresh installs show Receive/Send in the notification. An existing
        // value is never overwritten: the key is only written when it has
        // never been set.
        ensureNotificationTrafficDefault()
        // A process that died without a message is explained on the next
        // launch instead of being silently forgotten.
        _uiState.update {
            it.copy(
                languageCode = currentLanguageCode(),
                lastCrash = CrashReport.summary(
                    QsCrashRecorder.readLast(getApplication<Application>()),
                ),
            )
        }
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event -> onServiceEvent(event) }
        }
        // Stage 1 publishes through the scanner itself, so the IP Ranges screen
        // shows the foreground run and the background pass from one source,
        // and a recreated activity reads the current state instead of starting
        // another run.
        viewModelScope.launch {
            CloudflareScanner.discovery.collect { status ->
                val previous = _uiState.value.discovery
                val qualified = if (
                    status.qualified != previous.qualified || status.isTerminal
                ) {
                    withContext(Dispatchers.IO) { QualifiedRangeStore.snapshot() }
                } else {
                    _uiState.value.qualifiedRanges
                }
                _uiState.update { it.copy(discovery = status, qualifiedRanges = qualified) }
            }
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
     * Persists the URL in the text field as the active subscription and, by
     * default, starts its update.
     *
     * Saving a URL without fetching it left the panel closed and the list
     * empty, which looked like a failed save. The write is still the only
     * success condition for closing the panel; the download that follows is
     * the same one the Update button runs, so it reports its own progress and
     * its own error.
     *
     * @param startUpdate false when the caller is the connect flow, which
     *   refreshes the subscription itself as its second step.
     * @return true when a valid URL is stored.
     */
    fun saveSubscription(raw: String, startUpdate: Boolean = true): Boolean {
        val url = QsUrlValidator.normalize(raw)
        if (url == null) {
            // A rejected URL must not close the form: the user has to see the
            // error next to the field they have to fix.
            val expanded = SubscriptionPanelRules.expandedAfterSave(false)
            QsPrefs.setSubPanelExpanded(expanded)
            _uiState.update {
                it.copy(
                    inputError = getString(R.string.qs_error_bad_url),
                    subscriptionPanelExpanded = expanded,
                )
            }
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
        // The write above is the real success condition: only now does the
        // panel collapse, and the collapsed state is persisted so it survives
        // a tab switch or a cold start.
        val expanded = SubscriptionPanelRules.expandedAfterSave(true)
        QsPrefs.setSubPanelExpanded(expanded)
        _uiState.update {
            it.copy(
                subscriptionUrl = url,
                hasSubscription = true,
                inputError = null,
                message = null,
                subscriptionPanelExpanded = expanded,
            )
        }
        refreshLocalSnapshot()
        // The same Save action also refreshes the subscription. runUpdate is
        // single-flight, so a second tap cannot start a second download.
        if (startUpdate) runUpdate(listOf(SubscriptionCache(guid, item)))
        return true
    }

    /** Opens or closes the subscription panel on the Connection screen. */
    fun toggleSubscriptionPanel() {
        val expanded = !_uiState.value.subscriptionPanelExpanded
        QsPrefs.setSubPanelExpanded(expanded)
        _uiState.update { it.copy(subscriptionPanelExpanded = expanded) }
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
            // The flow refreshes the subscription as its own step, so saving
            // here must not start a second download.
            if (!saveSubscription(state.subscriptionUrl, startUpdate = false)) return
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
            // Never race the download a Save started: the flow refreshes the
            // same subscription itself, and two writers on one subscription
            // could interleave their writes.
            updateJob?.join()
            _uiState.update {
                it.copy(
                    status = QsStatus.Connecting,
                    step = QsStep.Preparing,
                    busy = true,
                    message = null,
                    inputError = null,
                    scanDone = 0,
                    scanTotal = 0,
                    configChoices = emptyList(),
                    chosenConfigGuid = null,
                    scanCandidates = emptyList(),
                    suggestedIp = null,
                    pendingIp = null,
                )
            }
            val result = orchestrator.autoConnect(getApplication()) { orchestratorState ->
                applyOrchestratorState(orchestratorState)
            }
            applyConnectResult(result)
            refreshLocalSnapshot()
        }
    }

    /**
     * Connects one configuration from the Configurations screen, exactly as
     * stored.
     *
     * This is deliberately not [connect]: no subscription refresh, no Cloudflare
     * eligibility filter, no discovery, no candidate scan and no edge rewrite.
     * A manual configuration on a port Cloudflare never serves still connects,
     * because the user asked for that configuration, not for optimisation.
     *
     * @return true when a direct attempt was actually started.
     */
    fun connectConfig(guid: String): Boolean {
        if (flowJob?.isActive == true) return false
        if (MmkvManager.decodeServerConfig(guid) == null) {
            _uiState.update { it.copy(message = getString(R.string.qs_error_config_missing)) }
            return false
        }
        QsPrefs.setPinnedGuid(guid)
        MmkvManager.setSelectServer(guid)
        refreshLocalSnapshot()
        flowJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = QsStatus.Connecting,
                    step = QsStep.Preparing,
                    busy = true,
                    message = null,
                    inputError = null,
                    configChoices = emptyList(),
                    chosenConfigGuid = null,
                    scanCandidates = emptyList(),
                    suggestedIp = null,
                    pendingIp = null,
                )
            }
            val result = orchestrator.directConnect(getApplication(), guid) { state ->
                applyOrchestratorState(state)
            }
            applyConnectResult(result)
            refreshLocalSnapshot()
        }
        return true
    }

    /**
     * The single action behind a configuration row.
     *
     * While a connection attempt is running the row does nothing, so a second
     * tap cannot start a second connection. When the tunnel is already up on
     * that same configuration the row disconnects instead of reconnecting.
     *
     * @return true when the caller should show the connection progress.
     */
    fun configAction(guid: String): Boolean {
        val state = _uiState.value
        return when (
            ConfigActionRules.decide(
                status = state.status,
                coreRunning = state.coreRunning,
                connectedGuid = state.connectedGuid,
                guid = guid,
            )
        ) {
            ConfigAction.Ignore -> false
            ConfigAction.Disconnect -> {
                disconnect()
                false
            }

            ConfigAction.Connect -> connectConfig(guid)
        }
    }

    /**
     * Confirms which eligible configuration the scan should use.
     *
     * The orchestrator validates the GUID against the list it offered, so a
     * stale click cannot make it scan a config from another subscription.
     */
    fun chooseConfig(guid: String) {
        _uiState.update { it.copy(chosenConfigGuid = guid) }
        orchestrator.chooseConfig(guid)
    }

    /** Highlights a candidate in the results list without applying it yet. */
    fun selectPendingIp(ip: String) {
        _uiState.update { it.copy(pendingIp = ip) }
    }

    /**
     * Confirms the highlighted IP. Only after this does the flow write the edge
     * to the configurations and start the tunnel.
     */
    fun confirmIp() {
        val ip = _uiState.value.pendingIp ?: return
        orchestrator.chooseIp(ip)
    }

    /**
     * Confirms a candidate while the scan is still running.
     *
     * Inside a connect flow the choice ends the scan and the flow continues with
     * it, so no later candidate can replace it. In a standalone scan the
     * selection and the edge are written and the tunnel is left untouched: an
     * IP choice is not a connection request.
     */
    fun useCandidateNow(ip: String) {
        val candidate = _uiState.value.scanCandidates.firstOrNull { it.ip == ip && it.answered } ?: return
        _uiState.update { it.copy(pendingIp = ip, suggestedIp = ip) }

        if (flowJob?.isActive == true) {
            orchestrator.chooseIpNow(ip)
            return
        }

        val target = lastScanTarget ?: connectTarget()
        if (target == null) {
            _uiState.update { it.copy(rangeError = getString(R.string.qs_range_scan_needs_config)) }
            return
        }

        // Stop the standalone scan first: nothing may keep measuring or writing
        // after the user has chosen.
        scanJob?.cancel()
        scanJob = null

        viewModelScope.launch {
            val applied = withContext(Dispatchers.IO) {
                orchestrator.applyCandidateNow(target.first, candidate.ip)
            }
            _uiState.update {
                it.copy(
                    scanningNow = false,
                    message = if (applied) {
                        getString(R.string.qs_scan_applied, candidate.ip)
                    } else {
                        getString(R.string.qs_error_generic)
                    },
                )
            }
            refreshLocalSnapshot()
        }
    }

    /**
     * Maps one orchestrator state onto the UI state.
     *
     * The callback runs inside the orchestrator's own flow. A defect in the
     * mapping must never be able to escape from here: it would be rethrown from
     * the orchestrator's failure path and kill the process. It becomes a
     * recoverable error instead, with the exception class only.
     */
    private fun applyOrchestratorState(orchestratorState: AutoConnectState) {
        try {
            _uiState.update { current ->
                val mapped = current.withConnectState(orchestratorState, flowText(), guardStaleScan = true)
                // The orchestrator reports machine reasons; the UI never shows
                // them raw, so a Persian session stays Persian.
                if (orchestratorState is AutoConnectState.Failed) {
                    mapped.copy(message = localizeFailure(orchestratorState.reason))
                } else {
                    mapped
                }
            }
        } catch (t: Throwable) {
            LogUtil.e(TAG, "connect state mapping failed: " + t.javaClass.name)
            _uiState.update {
                it.copy(
                    status = QsStatus.Error,
                    busy = false,
                    message = getString(R.string.qs_error_generic),
                )
            }
        }
    }

    /** Applies the final orchestrator result. Connected is never claimed early. */
    private fun applyConnectResult(result: AutoConnectResult) {
        _uiState.update { current ->
            when (result) {
                is AutoConnectResult.Success -> current.copy(
                    status = QsStatus.Connected,
                    step = QsStep.Connected,
                    busy = false,
                    coreRunning = true,
                    connectedGuid = result.guid,
                    message = null,
                )

                is AutoConnectResult.Failure -> current.copy(
                    // Never claim Connected unless the core really is up, and
                    // never leave a stale step label (Discovering/Scanning)
                    // under an error card.
                    status = if (current.coreRunning) QsStatus.Connected else QsStatus.Error,
                    step = if (current.coreRunning) QsStep.Connected else QsStep.Idle,
                    busy = false,
                    message = current.message ?: localizeFailure(result.reason),
                )

                AutoConnectResult.Cancelled -> current.copy(
                    status = if (current.coreRunning) QsStatus.Connected else QsStatus.Disconnected,
                    step = QsStep.Idle,
                    busy = false,
                    message = getString(R.string.qs_cancelled),
                )
            }
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

    /** Forgets the recorded crash after the user has read or copied it. */
    fun dismissCrash() {
        QsCrashRecorder.clear(getApplication<Application>())
        _uiState.update { it.copy(lastCrash = null) }
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

    /** Collapses or expands one subscription section on Configurations. */
    fun toggleConfigGroup(key: String) {
        _uiState.update {
            val collapsed = it.collapsedGroups
            it.copy(
                collapsedGroups = if (collapsed.contains(key)) collapsed - key else collapsed + key,
            )
        }
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

    // ---------- Real delay ----------

    /**
     * Measures the real outbound delay of every selectable configuration.
     *
     * This is the real delay, not a socket connect: it delegates to the same
     * CoreTestService worker the full UI uses, which asks the Xray core for an
     * outbound delay through the configured server and stores the result per
     * guid in [MmkvManager.encodeServerTestDelayMillis]. The per-guid result is
     * the one [refreshConfigRows] reads back, so a measurement can never be
     * shown against the wrong row.
     */
    fun measureRealDelay() {
        val state = _uiState.value
        if (state.testingDelay) return
        val guids = ConfigSelection.selectable(state.configRows).map { it.guid }
        if (guids.isEmpty()) return
        dataSource.cancelAllPing()
        dataSource.clearAllTestDelayResults(guids)
        dataSource.sendMsg2TestService(
            TestServiceMessage(
                key = AppConfig.MSG_MEASURE_CONFIG_START,
                serverGuids = guids,
                onlyTcp = false,
            ),
        )
        _uiState.update { it.copy(testingDelay = true) }
    }

    // ---------- Multi-select + bulk delete ----------

    fun toggleConfigSelection(guid: String) {
        _uiState.update {
            val selected = it.selectedGuids
            it.copy(selectedGuids = if (selected.contains(guid)) selected - guid else selected + guid)
        }
    }

    /** Selects exactly the rows the list currently renders. */
    fun selectAllConfigs() {
        val all = ConfigSelection.allVisibleGuids(
            _uiState.value.configRows,
            _uiState.value.filters,
        )
        _uiState.update { it.copy(selectedGuids = all) }
    }

    fun deselectAllConfigs() {
        _uiState.update { it.copy(selectedGuids = emptySet()) }
    }

    /** Opens the bulk-delete confirmation, but only when something is selected. */
    fun requestBulkDelete() {
        if (_uiState.value.selectedGuids.isEmpty()) return
        _uiState.update { it.copy(bulkDeleteOpen = true) }
    }

    fun dismissBulkDelete() {
        _uiState.update { it.copy(bulkDeleteOpen = false) }
    }

    /**
     * Deletes every selected configuration that may be deleted.
     *
     * [ConfigSelection.deletable] protects the active and the connected guid, so
     * a running or selected configuration can never be swept away by "select
     * all" + delete.
     */
    fun confirmBulkDelete() {
        val state = _uiState.value
        val deletable = ConfigSelection.deletable(
            state.selectedGuids,
            MmkvManager.getSelectServer(),
            state.connectedGuid,
        )
        viewModelScope.launch {
            var failed = false
            withContext(Dispatchers.IO) {
                deletable.forEach { guid ->
                    try {
                        MmkvManager.removeServer(guid)
                    } catch (e: Exception) {
                        LogUtil.e(TAG, "bulk delete failed for one guid: " + e.javaClass.simpleName)
                        failed = true
                    }
                }
            }
            if (QsPrefs.pinnedGuid() in deletable) QsPrefs.setPinnedGuid(null)
            _uiState.update {
                it.copy(
                    selectedGuids = emptySet(),
                    bulkDeleteOpen = false,
                    message = if (failed) getString(R.string.qs_error_delete_failed) else null,
                )
            }
            refreshLocalSnapshot()
        }
    }

    /**
     * Copies the configuration's share link to the system clipboard.
     *
     * This is the Copy action: it uses the same canonical serialisation the app
     * shares and exports with, writes nothing to storage, creates no GUID and
     * leaves the selection, the pin and the subscription untouched. The text
     * can be pasted into the Add/Import dialog.
     */
    fun copyConfigToClipboard(guid: String) {
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) {
                try {
                    AngConfigManager.share2Clipboard(getApplication(), guid) == 0
                } catch (e: Exception) {
                    LogUtil.e(TAG, "copyConfigToClipboard failed: " + e.javaClass.simpleName)
                    false
                }
            }
            _uiState.update {
                it.copy(
                    message = if (copied) {
                        getString(R.string.qs_copied_clipboard)
                    } else {
                        getString(R.string.qs_error_copy_failed)
                    },
                )
            }
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

                // Nothing was accepted: say why, and keep the dialog open so
                // the text can be corrected instead of retyped.
                outcome.added <= 0 && outcome.rejected > 0 -> _uiState.update {
                    it.copy(
                        importError = getString(
                            R.string.qs_import_rejected,
                            outcome.rejected,
                            rejectionText(outcome.rejectReason),
                        ),
                    )
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
                        message = when {
                            outcome.rejected > 0 -> getString(
                                R.string.qs_import_partial_rejected,
                                outcome.added,
                                outcome.rejected,
                            )

                            outcome.duplicates > 0 -> getString(
                                R.string.qs_import_partial,
                                outcome.added,
                                outcome.duplicates,
                            )

                            else -> getString(R.string.qs_import_done, outcome.added)
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
        // Editing the range set mid-scan would change what the running scanner
        // was told to probe while it is probing it.
        if (_uiState.value.scanningNow) {
            _uiState.update { it.copy(rangeError = getString(R.string.qs_range_locked)) }
            return
        }
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
        if (_uiState.value.scanningNow) {
            _uiState.update { it.copy(rangeError = getString(R.string.qs_range_locked)) }
            return
        }
        val next = _uiState.value.ranges.filterNot { it.cidr == cidr }
        QsPrefs.saveRanges(next)
        QsPrefs.clearSelection()
        _uiState.update { it.copy(ranges = next, rangeError = null) }
    }

    fun toggleRange(cidr: String) {
        if (_uiState.value.scanningNow) {
            _uiState.update { it.copy(rangeError = getString(R.string.qs_range_locked)) }
            return
        }
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
        // A deliberate rescan also refreshes the range database: otherwise the
        // next attempt would scan the same qualified ranges again.
        QsPrefs.requestRangeRefresh()
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
        lastScanTarget = target
        scanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    scanningNow = true,
                    scanDone = 0,
                    scanTotal = 0,
                    rangeError = null,
                    scanCandidates = emptyList(),
                    pendingIp = null,
                    suggestedIp = null,
                )
            }
            val result = orchestrator.scanOnly(getApplication(), target.first, target.second) { state ->
                when (state) {
                    is AutoConnectState.ScanProgress -> _uiState.update {
                        it.copy(scanningNow = true, scanDone = state.done, scanTotal = state.total)
                    }

                    // Live delivery: a candidate is tappable as soon as it has
                    // a measurement, not only when the whole scan finishes.
                    is AutoConnectState.ScanCandidateFound -> _uiState.update { current ->
                        current.copy(
                            scanningNow = true,
                            scanCandidates = rankCandidates(
                                current.scanCandidates.filterNot { it.ip == state.candidate.ip } +
                                    state.candidate,
                            ),
                        )
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

    /**
     * Runs one subscription refresh; blocking network work stays off the main
     * thread.
     *
     * Single-flight: the guard lives here, not only in the callers, so the Save
     * button, the Update button and a rapid double tap can never start two
     * downloads that would interleave their writes to the same subscription.
     */
    private fun runUpdate(targets: List<SubscriptionCache>) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
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
        var rejected = 0
        var rejectReason: Ineligibility? = null
        added.forEach { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@forEach
            // The same rule the connect flow applies, so a configuration can
            // never be stored as "connectable" here and refused there. The
            // parser already ran; this only decides whether the result may
            // join the Cloudflare flow.
            val ineligibility = AutoConfigFilter.ineligibility(profile)
            if (ineligibility != Ineligibility.Eligible) {
                rejected++
                if (rejectReason == null) rejectReason = ineligibility
                try {
                    MmkvManager.removeServer(guid)
                } catch (e: Exception) {
                    LogUtil.e(TAG, "ineligible cleanup failed: " + e.javaClass.simpleName)
                }
                return@forEach
            }
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
        return ImportOutcome(
            added = kept,
            duplicates = duplicates,
            rejected = rejected,
            rejectReason = rejectReason,
        )
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

            // A real-delay measurement finished for one guid. The test service
            // has already persisted the result, so re-reading the rows is all
            // that is needed to keep the badge current.
            MainServiceEvent.MeasureConfigSuccess -> refreshConfigRows()

            // Progress text from the test worker; the list itself is the
            // progress indicator here, so nothing to show per row.
            MainServiceEvent.MeasureConfigNotify -> Unit

            // The whole measurement pass is done (or was cancelled).
            MainServiceEvent.MeasureConfigFinish -> {
                _uiState.update { it.copy(testingDelay = false) }
                refreshConfigRows()
            }

            else -> Unit
        }
    }

    private fun onCoreRunning(running: Boolean) {
        if (!running) stopWatchJob?.cancel()
        if (running) refreshExitIp()
        _uiState.update { state ->
            if (running) {
                val keepConnecting = state.status == QsStatus.Connecting
                state.copy(
                    coreRunning = true,
                    status = if (keepConnecting) QsStatus.Connecting else QsStatus.Connected,
                    busy = if (keepConnecting) state.busy else false,
                    // A daemon started outside this screen still runs a known
                    // configuration: the selected one.
                    connectedGuid = state.connectedGuid ?: MmkvManager.getSelectServer(),
                    message = if (keepConnecting) state.message else null,
                )
            } else {
                val keepConnecting = state.status == QsStatus.Connecting
                state.copy(
                    coreRunning = false,
                    status = if (keepConnecting) QsStatus.Connecting else QsStatus.Disconnected,
                    busy = if (keepConnecting) state.busy else false,
                    connectedGuid = null,
                    // A stale exit IP must never survive a disconnect.
                    exitIp = null,
                )
            }
        }
    }

    /**
     * Asks an external service, through the tunnel, which IP the traffic is
     * really exiting from. Runs off the main thread: it performs HTTP.
     *
     * The result is only ever the real, observed exit IP. When the lookup
     * fails the field stays null and the UI shows the unavailable fallback
     * instead of inventing the origin server or the Cloudflare edge.
     */
    private fun refreshExitIp() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = try {
                SpeedtestManager.getRemoteIPInfo()?.ipAddress
            } catch (e: Exception) {
                LogUtil.e(TAG, "exit IP lookup failed: " + e.javaClass.simpleName)
                null
            } ?.trim()?.takeIf { it.isNotEmpty() }
            _uiState.update { it.copy(exitIp = ip) }
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
                subscriptionPanelExpanded = QsPrefs.subPanelExpanded(),
                languageCode = currentLanguageCode(),
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
                // Same rule the orchestrator applies, so the badge and the
                // automatic flow can never disagree.
                eligible = AutoConfigFilter.isEligible(profile),
                // Real outbound delay, stored per guid by the test service.
                testDelayMillis = MmkvManager.decodeServerAffiliationInfo(guid)
                    ?.testDelayMillis ?: 0L,
            )
        }
        _uiState.update { it.copy(configRows = rows) }
    }

    private fun refreshRanges() {
        _uiState.update {
            it.copy(
                ranges = QsPrefs.loadRanges(),
                qualifiedRanges = QualifiedRangeStore.snapshot(),
            )
        }
    }

    private fun refreshSelectedProfile() {
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        _uiState.update {
            it.copy(
                selectedRemarks = profile?.remarks?.takeIf { remarks -> remarks.isNotBlank() },
                // The origin server from the profile, never the Cloudflare
                // edge: the edge has its own row.
                selectedServer = profile?.server?.takeIf { server -> server.isNotBlank() },
                selectedEdge = profile?.qsEdgeIp?.takeIf { edge -> edge.isNotBlank() },
            )
        }
    }

    // ---------- Language ----------

    /** Applies EN/FA through the existing per-app locale machinery. */
    fun setLanguage(code: String) {
        AppLocaleManager.setApplicationLanguage(code)
        _uiState.update { it.copy(languageCode = code) }
    }

    /** Flips between English and Persian. */
    fun toggleLanguage() {
        setLanguage(LanguageToggle.next(currentLanguageCode()))
    }

    private fun currentLanguageCode(): String = LanguageToggle.normalize(
        // "auto" (or nothing) resolves to the system language; the control
        // still shows a concrete state.
        MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE),
        java.util.Locale.getDefault().language,
    )

    /**
     * Turns Receive/Send in the notification on for a fresh install only.
     *
     * The MMKV key is written only when it has never been set, so an existing
     * choice — including a deliberate "off" — is preserved across upgrades.
     */
    private fun ensureNotificationTrafficDefault() {
        val exists = MmkvManager.containsSettings(AppConfig.PREF_SPEED_ENABLED)
        if (!NotificationDefaults.enableTrafficWhenUnset(exists)) return
        MmkvManager.encodeSettings(AppConfig.PREF_SPEED_ENABLED, true)
    }

    /**
     * Localized explanation for a rejected configuration.
     *
     * The wording names the rule that failed, never the configuration content,
     * so no server, credential or token can leak through an error message.
     */
    private fun rejectionText(reason: Ineligibility?): String = when (reason) {
        Ineligibility.UnsupportedProtocol -> getString(R.string.qs_reject_protocol)
        Ineligibility.UnsupportedTransport -> getString(R.string.qs_reject_transport)
        Ineligibility.NotTls -> getString(R.string.qs_reject_tls)
        Ineligibility.PortNotAllowed -> getString(R.string.qs_reject_port)
        Ineligibility.MissingServer -> getString(R.string.qs_reject_server)
        Ineligibility.MissingCredential -> getString(R.string.qs_reject_credential)
        Ineligibility.Eligible, null -> getString(R.string.qs_error_generic)
    }

    /** Localized text for a failure kind; the raw reason is never shown. */
    private fun localizeFailure(reason: String): String =
        when (FlowFailureRules.classify(reason)) {
            FlowFailure.VpnPermission -> getString(R.string.qs_error_vpn_denied)
            FlowFailure.NotEligible -> getString(R.string.qs_error_no_eligible)
            FlowFailure.ScanFoundNothing -> getString(R.string.qs_error_scan_none)
            FlowFailure.Subscription -> getString(R.string.qs_error_update_failed)
            FlowFailure.ApplyFailed -> getString(R.string.qs_error_apply_failed)
            FlowFailure.CoreStart -> getString(R.string.qs_error_core_start)
            FlowFailure.Busy -> getString(R.string.qs_error_busy)
            FlowFailure.Generic -> getString(R.string.qs_error_generic)
        }

    /**
     * Texts the flow itself produces.
     *
     * `qs_using_saved_ip` and `qs_reconnecting` are **templates** with one
     * placeholder each, so they are read with [templateString]: the value is
     * supplied later by [QsFlowText.savedEdge] / [QsFlowText.reconnectAttempt].
     * Reading them with the formatting overload and no argument is exactly the
     * bug that crashed Connect with `MissingFormatArgumentException: '%s'`.
     */
    private fun flowText(): QsFlowText = QsFlowText(
        noSubscription = getString(R.string.qs_error_no_subscription),
        usingSavedIp = templateString(R.string.qs_using_saved_ip),
        reconnecting = templateString(R.string.qs_reconnecting),
        cancelled = getString(R.string.qs_cancelled),
    )

    /** A resource read as-is, placeholders included; never formatted here. */
    private fun templateString(resId: Int): String =
        getApplication<Application>().getString(resId)

    /**
     * Reads a string resource with its arguments.
     *
     * The no-argument overload of `Context.getString` never formats; the
     * vararg one always does, and with an empty argument list it throws
     * `MissingFormatArgumentException` for any resource that contains a
     * placeholder. Branching here makes that class of crash impossible for
     * every caller of this helper.
     */
    private fun getString(resId: Int, vararg args: Any): String {
        val app = getApplication<Application>()
        return if (args.isEmpty()) app.getString(resId) else app.getString(resId, *args)
    }

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
