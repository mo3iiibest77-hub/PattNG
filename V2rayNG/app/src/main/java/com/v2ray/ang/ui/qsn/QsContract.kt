package com.v2ray.ang.ui.qsn

import com.v2ray.ang.senpai.IspProfile
import com.v2ray.ang.ui.connect.AutoConnectState
import com.v2ray.ang.ui.qsn.domain.ConfigChoice
import com.v2ray.ang.ui.qsn.domain.ConfigFilter
import com.v2ray.ang.ui.qsn.domain.ConfigFilterEngine
import com.v2ray.ang.ui.qsn.domain.ConfigRow
import com.v2ray.ang.ui.qsn.domain.DiscoveryStatus
import com.v2ray.ang.ui.qsn.domain.QualifiedRange
import com.v2ray.ang.ui.qsn.domain.ScanCandidate
import com.v2ray.ang.ui.qsn.domain.ScanRange
import com.v2ray.ang.ui.qsn.domain.SubscriptionRow

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
    ChoosingConfig,
    Discovering,
    ScanningIps,
    ChoosingIp,
    SelectingIp,
    ApplyingEdge,
    StartingVpn,
    Connected;

    companion object {
        /**
         * Steps rendered in the progress list, in order.
         *
         * They mirror the real flow one to one: each row is reached only when
         * the corresponding phase of [com.v2ray.ang.ui.connect.ConnectionOrchestrator]
         * actually starts.
         */
        val progressSteps: List<QsStep> = listOf(
            Preparing,
            FetchingSubscription,
            CheckingConfigs,
            ChoosingConfig,
            Discovering,
            ScanningIps,
            ChoosingIp,
            SelectingIp,
            ApplyingEdge,
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
    /** Origin server from the profile; never the Cloudflare edge. */
    val selectedServer: String? = null,
    /** Cloudflare edge the core is dialing, when one is selected. */
    val selectedEdge: String? = null,
    val lastUpdated: Long = -1L,
    val refreshing: Boolean = false,
    val inputError: String? = null,
    /** Every stored configuration, already mapped to a credential-free row. */
    val configRows: List<ConfigRow> = emptyList(),
    /** Selected filter chips; empty means [ConfigFilter.All]. */
    val filters: Set<ConfigFilter> = emptySet(),
    /** All subscriptions, active first. */
    val subscriptions: List<SubscriptionRow> = emptyList(),
    val subscriptionsExpanded: Boolean = false,
    /** Configured CIDR ranges, in insertion order. */
    val ranges: List<ScanRange> = emptyList(),
    /**
     * Ranges a real measurement qualified, read from the canonical store.
     *
     * Kept apart from [ranges]: those are the user's input, these are results.
     */
    val qualifiedRanges: List<QualifiedRange> = emptyList(),
    /** Live Stage 1 state, published by the scanner for both runs. */
    val discovery: DiscoveryStatus = DiscoveryStatus(),
    /** Every ISP with persisted discovery results, newest first. */
    val ispProfiles: List<IspProfile> = emptyList(),
    /** ISP the discovery controls act on. */
    val selectedIsp: String = "default",
    /** Manual ranges stored for [selectedIsp]; never mixed with another ISP. */
    val manualRanges: List<String> = emptyList(),
    /** Ranges the user picked for Stage 2; empty means "all good ranges". */
    val selectedRanges: Set<String> = emptySet(),
    /** True while the full-screen Discovery view is open. */
    val discoveryOpen: Boolean = false,
    /** Text explaining why a manual CIDR was rejected. */
    val manualRangeError: String? = null,
    /** Persian/English text explaining why the last range was rejected. */
    val rangeError: String? = null,
    /** Text explaining why the last manual import was rejected. */
    val importError: String? = null,
    val importOpen: Boolean = false,
    /** Configuration awaiting delete confirmation. */
    val pendingDeleteGuid: String? = null,
    /** Guids ticked in the multi-select bulk delete. */
    val selectedGuids: Set<String> = emptySet(),
    /** True while the bulk-delete confirmation dialog is open. */
    val bulkDeleteOpen: Boolean = false,
    /** True while a real-delay measurement is in flight. */
    val testingDelay: Boolean = false,
    /** Subscription awaiting delete confirmation. */
    val pendingDeleteSubscription: String? = null,
    /** True while a standalone scan started from the IP Ranges screen runs. */
    val scanningNow: Boolean = false,
    /** Timestamp of the last successful scan, 0 when none ever ran. */
    val lastScanAt: Long = 0L,
    /** True while the subscription panel on the Connection screen is open. */
    val subscriptionPanelExpanded: Boolean = true,
    /** Eligible configs offered before the scan starts. */
    val configChoices: List<ConfigChoice> = emptyList(),
    /** Config the user highlighted in the picker (pre-selected). */
    val chosenConfigGuid: String? = null,
    /** Live scan results, newest measurement per IP. */
    val scanCandidates: List<ScanCandidate> = emptyList(),
    /** The ranker's suggestion; never applied without confirmation. */
    val suggestedIp: String? = null,
    /** IP the user highlighted in the results list. */
    val pendingIp: String? = null,
    /**
     * Configuration the running core is actually using, as confirmed by the
     * daemon. Null when nothing runs. Kept apart from the *selected* row so
     * selecting another configuration cannot make its button claim a
     * connection it does not have.
     */
    val connectedGuid: String? = null,
    /**
     * Actual exit-node IP as observed by an external service through the
     * tunnel. Null until measured, or when the exit IP cannot be determined;
     * never fabricated into the origin server or the Cloudflare edge.
     */
    val exitIp: String? = null,
    /** Current app language code ("en", "fa", ...). */
    val languageCode: String = "en",
    /** Configurations sections the user collapsed, by stable group key. */
    val collapsedGroups: Set<String> = emptySet(),
    /**
     * Cause of the previous process death, when one was recorded. Shown so a
     * crash is never again an unexplained close.
     */
    val lastCrash: String? = null,
) {
    /** True while a connect attempt is in flight; the button becomes Cancel. */
    val connecting: Boolean get() = status == QsStatus.Connecting

    /** Rows that pass every selected filter, in storage order. */
    val filteredConfigs: List<ConfigRow>
        get() = ConfigFilterEngine.apply(filters, configRows)

    /** How many stored configurations the automatic flow could actually use. */
    val eligibleCount: Int
        get() = configRows.count { it.eligible }

    /** True when there is anything to connect with. */
    val hasAnyConfig: Boolean
        get() = configRows.isNotEmpty()

    /** True while the flow is parked on a question the user must answer. */
    val awaitingChoice: Boolean
        get() = step == QsStep.ChoosingConfig || step == QsStep.ChoosingIp

    /** Rows of the chosen subscription that the automatic flow can use. */
    val eligibleChoices: List<ConfigChoice>
        get() = configChoices

    /** Progress of the Cloudflare scan in 0..1, or null when not scanning. */
    val scanFraction: Float?
        get() = if (step == QsStep.ScanningIps && scanTotal > 0) {
            (scanDone.toFloat() / scanTotal.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}

/**
 * Localized texts the flow itself produces.
 *
 * The defaults are English so the pure state mapper stays testable without
 * Android resources; the ViewModel passes a resource-backed instance so the
 * same code path renders Persian when Persian is selected.
 */
data class QsFlowText(
    val noSubscription: String = "No subscription configured",
    /** Template with one `%s`; rendered only through [savedEdge]. */
    val usingSavedIp: String = "Using saved IP %s",
    /** Template with one `%d`; rendered only through [reconnectAttempt]. */
    val reconnecting: String = "Reconnecting (attempt %d)",
    val cancelled: String = "Cancelled",
) {
    /**
     * The saved-edge message for the IP that was really reused.
     *
     * The template and its value are bound here on purpose: the previous
     * version handed the raw template to the state mapper, and a caller that
     * formatted it with no argument killed the app with
     * `MissingFormatArgumentException: Format specifier '%s'`.
     *
     * @return null when there is no real IP, so the caller shows no message
     *   at all instead of inventing one.
     */
    fun savedEdge(ip: String?): String? =
        ip?.trim()?.takeIf { it.isNotEmpty() }?.let { usingSavedIp.format(it) }

    /** The reconnect message for the real attempt number. */
    fun reconnectAttempt(attempt: Int): String = reconnecting.format(attempt)
}

/** Why a connection attempt failed, expressed so the UI can localize it. */
enum class FlowFailure {
    VpnPermission,
    NotEligible,
    ScanFoundNothing,
    Subscription,
    ApplyFailed,
    CoreStart,
    Busy,
    Generic,
}

/**
 * Maps the orchestrator's machine reason to a stable failure kind.
 *
 * Pure and tested, so the user-visible text is chosen by a rule instead of by
 * string matching scattered through the ViewModel, and the raw reason (which
 * may contain a subscription host) is never shown.
 */
object FlowFailureRules {
    fun classify(reason: String): FlowFailure = when {
        reason.contains("permission", ignoreCase = true) -> FlowFailure.VpnPermission
        reason.contains("already running", ignoreCase = true) -> FlowFailure.Busy
        reason.contains("cannot be used", ignoreCase = true) ||
            reason.contains("eligible", ignoreCase = true) -> FlowFailure.NotEligible
        reason.contains("scan", ignoreCase = true) -> FlowFailure.ScanFoundNothing
        reason.contains("subscription", ignoreCase = true) -> FlowFailure.Subscription
        reason.contains("apply", ignoreCase = true) -> FlowFailure.ApplyFailed
        reason.contains("running", ignoreCase = true) ||
            reason.contains("core", ignoreCase = true) -> FlowFailure.CoreStart
        else -> FlowFailure.Generic
    }
}

/**
 * Ordering shared by the live scan list and the final choice list.
 *
 * Score first, then latency, then address: deterministic, so the "suggested"
 * row cannot move between two identical renders.
 */
internal fun rankCandidates(candidates: List<ScanCandidate>): List<ScanCandidate> =
    candidates.sortedWith(
        compareByDescending<ScanCandidate> { it.score }
            .thenBy { it.pingMs ?: Long.MAX_VALUE }
            .thenBy { it.ip },
    )

/**
 * Folds one orchestrator state into the UI state.
 *
 * Pure and side-effect free so it can be unit tested against the real [AutoConnectState] type
 * without Android or a running core.
 */
internal fun QsUiState.withConnectState(
    state: AutoConnectState,
    text: QsFlowText = QsFlowText(),
    guardStaleScan: Boolean = false,
): QsUiState {
    // A scanner callback that arrives after a connect flow already moved on
    // must not pull the screen backwards. This is what keeps a late progress
    // event from replacing a choice the user confirmed mid-scan. A standalone
    // scan (IP Ranges while connected) does not pass this flag: it legitimately
    // starts from a screen that is already showing Connected.
    if (guardStaleScan && isStaleScanEvent(state, step)) return copy()
    return foldConnectState(state, text)
}

/** True when [state] is scanner traffic that [step] has already moved past. */
internal fun isStaleScanEvent(state: AutoConnectState, step: QsStep): Boolean {
    val settled = step == QsStep.ChoosingIp ||
        step == QsStep.SelectingIp ||
        step == QsStep.ApplyingEdge ||
        step == QsStep.StartingVpn ||
        step == QsStep.Connected
    val starting = step == QsStep.ApplyingEdge ||
        step == QsStep.StartingVpn ||
        step == QsStep.Connected
    return when (state) {
        is AutoConnectState.ScanProgress,
        is AutoConnectState.ScanCandidateFound,
        -> settled
        AutoConnectState.ScanningCloudflareIps -> starting
        else -> false
    }
}

private fun QsUiState.foldConnectState(
    state: AutoConnectState,
    text: QsFlowText,
): QsUiState = when (state) {
    AutoConnectState.WaitingForSubscription -> copy(
        status = if (coreRunning) QsStatus.Connected else QsStatus.Error,
        step = QsStep.Idle,
        busy = false,
        message = text.noSubscription,
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

    AutoConnectState.DiscoveringRanges -> copy(
        status = QsStatus.Connecting,
        step = QsStep.Discovering,
        busy = true,
        message = null,
        scanDone = 0,
        scanTotal = 0,
    )

    is AutoConnectState.DiscoveryProgress -> copy(
        status = QsStatus.Connecting,
        step = QsStep.Discovering,
        busy = true,
        scanDone = state.done,
        scanTotal = state.total,
    )

    AutoConnectState.ScanningCloudflareIps -> copy(
        status = QsStatus.Connecting,
        step = QsStep.ScanningIps,
        busy = true,
        message = null,
        scanDone = 0,
        scanTotal = 0,
        scanCandidates = emptyList(),
        suggestedIp = null,
        pendingIp = null,
    )

    is AutoConnectState.ScanProgress -> copy(
        status = QsStatus.Connecting,
        step = QsStep.ScanningIps,
        busy = true,
        scanDone = state.done,
        scanTotal = state.total,
    )

    is AutoConnectState.ScanCandidateFound -> copy(
        status = QsStatus.Connecting,
        step = QsStep.ScanningIps,
        busy = true,
        scanDone = state.done,
        scanTotal = state.total,
        scanCandidates = rankCandidates(
            scanCandidates.filterNot { it.ip == state.candidate.ip } + state.candidate,
        ),
    )

    is AutoConnectState.AwaitingConfigChoice -> copy(
        status = QsStatus.Connecting,
        step = QsStep.ChoosingConfig,
        busy = true,
        message = null,
        configChoices = state.choices,
        chosenConfigGuid = state.selected,
        scanCandidates = emptyList(),
        suggestedIp = null,
        pendingIp = null,
    )

    is AutoConnectState.AwaitingIpChoice -> copy(
        status = QsStatus.Connecting,
        step = QsStep.ChoosingIp,
        busy = true,
        message = null,
        scanCandidates = state.candidates,
        suggestedIp = state.suggestedIp,
        pendingIp = pendingIp ?: state.suggestedIp,
    )

    is AutoConnectState.ApplyingEdge -> copy(
        status = QsStatus.Connecting,
        step = QsStep.ApplyingEdge,
        busy = true,
        message = null,
    )

    is AutoConnectState.ReusingSavedIp -> copy(
        status = QsStatus.Connecting,
        step = QsStep.SelectingIp,
        busy = true,
        // Null when the flow has no real IP: no message is better than a
        // placeholder or a fabricated address.
        message = text.savedEdge(state.ip),
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
        connectedGuid = state.guid,
        message = null,
    )

    is AutoConnectState.Reconnecting -> copy(
        status = QsStatus.Connecting,
        step = QsStep.StartingVpn,
        busy = true,
        message = text.reconnectAttempt(state.attempt),
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
        connectedGuid = if (coreRunning) connectedGuid else null,
        message = text.cancelled,
    )

    AutoConnectState.Disconnected -> copy(
        status = if (coreRunning) QsStatus.Connected else QsStatus.Disconnected,
        step = QsStep.Idle,
        busy = false,
        connectedGuid = if (coreRunning) connectedGuid else null,
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
        val hasHost = try {
            val uri = java.net.URI(trimmed)
            // java.net.URI does not decode IDN: for a non-ASCII authority it
            // returns a null host instead of throwing, so fall back to the
            // raw authority before giving up.
            val host = uri.host ?: uri.authority
                ?.substringAfter("@")
                ?.substringBefore(":")
            !host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
        return if (hasHost || lenient.containsMatchIn(trimmed)) trimmed else null
    }
}

/** What a configuration row's single action must do right now. */
enum class ConfigAction { Ignore, Connect, Disconnect }

/**
 * Decides the action of one configuration row.
 *
 * Pure, so the rule that a second connection can never be started is tested
 * directly: while an attempt runs the row does nothing, and a row whose
 * configuration is already the one the core runs disconnects instead of
 * reconnecting.
 */
object ConfigActionRules {
    fun decide(
        status: QsStatus,
        coreRunning: Boolean,
        connectedGuid: String?,
        guid: String,
    ): ConfigAction = when {
        status == QsStatus.Connecting || status == QsStatus.Disconnecting -> ConfigAction.Ignore
        coreRunning && connectedGuid == guid -> ConfigAction.Disconnect
        else -> ConfigAction.Connect
    }
}

/** Localised timestamp used by every Quiet Storm NG list. */
internal fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "\u2014"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
}
