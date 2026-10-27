package com.v2ray.ang.ui.qsn

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.connect.AutoConnectResult
import com.v2ray.ang.ui.connect.ConnectionOrchestrator
import com.v2ray.ang.ui.main.MainDataSource
import com.v2ray.ang.ui.main.MainServiceEvent
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

/**
 * State holder for the minimal Quiet Storm NG home screen.
 *
 * It owns three things and nothing else:
 *  - the connection state machine, driven by [ConnectionOrchestrator] and by the real daemon
 *    broadcasts that [MainDataSource] forwards,
 *  - the subscription URL shown on screen (MMKV is the single source of truth),
 *  - the user facing error text.
 *
 * All blocking work (asset init, subscription fetch, the whole auto-connect flow) runs off the
 * main thread. The orchestrator dispatches internally as well, so a caller cannot cause an ANR.
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

    // ---------- User actions ----------

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
            _uiState.update {
                it.copy(inputError = "Enter a valid http(s) subscription URL")
            }
            return false
        }
        val subs = MmkvManager.decodeSubscriptions()
        val existing = subs.firstOrNull { it.subscription.enabled } ?: subs.firstOrNull()
        val item = existing?.subscription?.copy(url = url)
            ?: SubscriptionItem(remarks = DEFAULT_SUBSCRIPTION_REMARKS, url = url, enabled = true)
        if (item.remarks.isBlank()) item.remarks = DEFAULT_SUBSCRIPTION_REMARKS
        val guid = existing?.guid ?: Utils.getUuid()
        MmkvManager.encodeSubscription(guid, item)
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

    /** Downloads the subscription again and reports how many configs it produced. */
    fun refreshSubscription() {
        if (_uiState.value.refreshing) return
        val target = activeSubscription()
        if (target == null) {
            _uiState.update { it.copy(inputError = "Add a subscription URL first") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                try {
                    dataSource.updateConfigViaSub(target)
                } catch (e: Exception) {
                    LogUtil.e(TAG, "refreshSubscription failed: " + e.javaClass.simpleName)
                    null
                }
            }
            val count = result?.configCount ?: 0
            _uiState.update {
                it.copy(
                    refreshing = false,
                    configCount = if (count > 0) count else it.configCount,
                    lastUpdated = if (count > 0) System.currentTimeMillis() else it.lastUpdated,
                    message = if (count > 0) {
                        null
                    } else {
                        "Update failed: the subscription returned no usable configs"
                    },
                )
            }
        }
    }

    /** Starts the full automatic flow. Ignored while another attempt is running. */
    fun connect() {
        if (flowJob?.isActive == true) return
        val state = _uiState.value
        if (state.subscriptionUrl.isBlank()) {
            _uiState.update { it.copy(inputError = "Add a subscription URL first") }
            return
        }
        if (state.subscriptionUrl.trim() != storedUrl && !saveSubscription(state.subscriptionUrl)) {
            return
        }
        if (!_uiState.value.hasSubscription) {
            _uiState.update { it.copy(inputError = "Add a subscription URL first") }
            return
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
                        message = "Cancelled",
                    )
                }
            }
            refreshSelectedProfile()
            refreshConfigCount()
        }
    }

    /** Cancels the running auto-connect attempt; the flow reports Cancelled itself. */
    fun cancel() {
        if (flowJob?.isActive != true) return
        _uiState.update { it.copy(message = "Cancelling...") }
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
                        message = "Stop was not confirmed by the core",
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
                message = "VPN permission was denied. It is required to connect.",
            )
        }
    }

    // ---------- Internal ----------

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
                            message = "The VPN core failed to start",
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

    private fun activeSubscription() =
        MmkvManager.decodeSubscriptions().let { subs ->
            subs.firstOrNull { it.subscription.enabled } ?: subs.firstOrNull()
        }

    private fun refreshLocalSnapshot() {
        val target = activeSubscription()
        val url = target?.subscription?.url.orEmpty()
        storedUrl = url
        _uiState.update {
            it.copy(
                subscriptionUrl = url,
                hasSubscription = target != null && url.isNotBlank(),
                configCount = target?.let { sub -> MmkvManager.decodeServerList(sub.guid).size } ?: 0,
                lastUpdated = target?.subscription?.lastUpdated ?: -1L,
            )
        }
        refreshSelectedProfile()
    }

    private fun refreshConfigCount() {
        val target = activeSubscription() ?: return
        val count = MmkvManager.decodeServerList(target.guid).size
        _uiState.update { it.copy(configCount = count) }
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

    override fun onCleared() {
        super.onCleared()
        orchestrator.cancel()
        stopWatchJob?.cancel()
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
