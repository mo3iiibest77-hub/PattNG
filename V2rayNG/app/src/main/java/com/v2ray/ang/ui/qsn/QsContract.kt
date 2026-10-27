package com.v2ray.ang.ui.qsn

import com.v2ray.ang.ui.connect.AutoConnectState

/**
 * Connection state shown by the minimal Quiet Storm NG home screen.
 *
 * [Connected] is only ever produced from a real daemon signal (MSG_STATE_START_SUCCESS /
 * MSG_STATE_RUNNING) or from [com.v2ray.ang.ui.connect.AutoConnectResult.Success], which is
 * itself emitted only after the daemon confirmed running. Selecting a config, finishing a scan
 * or calling startService never sets it.
 */
enum class QsStatus { Disconnected, Connecting, Connected, Disconnecting, Error }

/** Ordered steps of the automatic connection flow. */
enum class QsStep {
    Idle,
    Preparing,
    FetchingSubscription,
    CheckingConfigs,
    ScanningIps,
    SelectingIp,
    StartingVpn,
    Connected;

    companion object {
        /** Steps rendered in the progress list, in order. */
        val progressSteps: List<QsStep> = listOf(
            Preparing,
            FetchingSubscription,
            CheckingConfigs,
            ScanningIps,
            SelectingIp,
            StartingVpn,
            Connected,
        )
    }
}

/**
 * Immutable UI state of the connect screen.
 *
 * Every field is either derived from MMKV or from a real daemon/broadcast signal. There is no
 * fabricated value: [selectedRemarks]/[selectedServer] stay null until a profile is actually
 * selected, and no speed or duration is shown anywhere because the core does not expose traffic
 * statistics to the UI process.
 */
data class QsUiState(
    val status: QsStatus = QsStatus.Disconnected,
    val step: QsStep = QsStep.Idle,
    val message: String? = null,
    val busy: Boolean = false,
    val coreRunning: Boolean = false,
    val subscriptionUrl: String = "",
    val hasSubscription: Boolean = false,
    val configCount: Int = 0,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    val selectedRemarks: String? = null,
    val selectedServer: String? = null,
    val lastUpdated: Long = -1L,
    val refreshing: Boolean = false,
    val inputError: String? = null,
) {
    /** True while a connect attempt is in flight; the button becomes Cancel. */
    val connecting: Boolean get() = status == QsStatus.Connecting

    /** Progress of the Cloudflare scan in 0..1, or null when not scanning. */
    val scanFraction: Float?
        get() = if (step == QsStep.ScanningIps && scanTotal > 0) {
            (scanDone.toFloat() / scanTotal.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}

/**
 * Folds one orchestrator state into the UI state.
 *
 * Pure and side-effect free so it can be unit tested against the real [AutoConnectState] type
 * without Android or a running core.
 */
internal fun QsUiState.withConnectState(state: AutoConnectState): QsUiState = when (state) {
    AutoConnectState.WaitingForSubscription -> copy(
        status = if (coreRunning) QsStatus.Connected else QsStatus.Error,
        step = QsStep.Idle,
        busy = false,
        message = "No subscription configured",
    )

    AutoConnectState.FetchingSubscription -> copy(
        status = QsStatus.Connecting,
        step = QsStep.FetchingSubscription,
        busy = true,
        message = null,
    )

    AutoConnectState.ParsingConfigurations,
    AutoConnectState.FilteringConfigurations,
    AutoConnectState.ApplyingConnectionSettings -> copy(
        status = QsStatus.Connecting,
        step = QsStep.CheckingConfigs,
        busy = true,
        message = null,
    )

    AutoConnectState.ScanningCloudflareIps -> copy(
        status = QsStatus.Connecting,
        step = QsStep.ScanningIps,
        busy = true,
        message = null,
        scanDone = 0,
        scanTotal = 0,
    )

    is AutoConnectState.ScanProgress -> copy(
        status = QsStatus.Connecting,
        step = QsStep.ScanningIps,
        busy = true,
        scanDone = state.done,
        scanTotal = state.total,
    )

    is AutoConnectState.SelectingBestIp -> copy(
        status = QsStatus.Connecting,
        step = QsStep.SelectingIp,
        busy = true,
    )

    AutoConnectState.StartingVpn -> copy(
        status = QsStatus.Connecting,
        step = QsStep.StartingVpn,
        busy = true,
    )

    is AutoConnectState.Connected -> copy(
        status = QsStatus.Connected,
        step = QsStep.Connected,
        busy = false,
        coreRunning = true,
        message = null,
    )

    is AutoConnectState.Reconnecting -> copy(
        status = QsStatus.Connecting,
        step = QsStep.StartingVpn,
        busy = true,
        message = "Reconnecting (attempt " + state.attempt + ")",
    )

    is AutoConnectState.Failed -> copy(
        // A failure after the tunnel came up must not report a false disconnect.
        status = if (coreRunning) QsStatus.Connected else QsStatus.Error,
        busy = false,
        message = state.reason,
    )

    AutoConnectState.Stopping -> copy(
        status = if (coreRunning) QsStatus.Connected else QsStatus.Disconnected,
        step = QsStep.Idle,
        busy = false,
        message = "Cancelled",
    )

    AutoConnectState.Disconnected -> copy(
        status = if (coreRunning) QsStatus.Connected else QsStatus.Disconnected,
        step = QsStep.Idle,
        busy = false,
    )
}

/**
 * Validation for the subscription URL field.
 *
 * This is a UI pre-check only: the authoritative validation stays in
 * [com.v2ray.ang.handler.AngConfigManager.updateConfigViaSub]. It is deliberately implemented
 * with java.net only so it can run in plain JVM unit tests.
 */
internal object QsUrlValidator {

    private val lenient = Regex("^https?://[^\\s/]+", RegexOption.IGNORE_CASE)

    /**
     * @return the trimmed URL when it looks like an http(s) subscription URL, null otherwise.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isWhitespace() }) return null
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return try {
            val uri = java.net.URI(trimmed)
            if (uri.host.isNullOrBlank()) null else trimmed
        } catch (_: Exception) {
            // Non-ASCII hosts make URI throw; fall back to a shape check.
            if (lenient.containsMatchIn(trimmed)) trimmed else null
        }
    }
}
