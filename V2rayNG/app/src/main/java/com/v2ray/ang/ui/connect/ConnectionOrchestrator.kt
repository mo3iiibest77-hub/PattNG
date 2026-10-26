package com.v2ray.ang.ui.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.senpai.CandidateResult
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.ScanCallback
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Auto-connect state machine for Quiet Storm NG.
 *
 * Sealed interface so the UI (Phase 3) can render each step deterministically
 * with an exhaustive when-expression. States are emitted in strict order during
 * a single [ConnectionOrchestrator.autoConnect] call.
 */
sealed interface AutoConnectState {
    /** No subscription URL configured yet. */
    data object WaitingForSubscription : AutoConnectState
    /** Downloading subscription content from the remote URL. */
    data object FetchingSubscription : AutoConnectState
    /** Parsing downloaded config lines into [ProfileItem]s. */
    data object ParsingConfigurations : AutoConnectState
    /** Running [AutoConfigFilter] to narrow configs to eligible ones. */
    data object FilteringConfigurations : AutoConnectState
    /** Filling blank finalMask/cipherSuites on the selected config. */
    data object ApplyingConnectionSettings : AutoConnectState
    /** CloudflareScanner is probing candidate IPs. */
    data object ScanningCloudflareIps : AutoConnectState
    /** Scanner finished; writing best IP to the profile. */
    data class SelectingBestIp(val bestIp: String) : AutoConnectState
    /** VPN service has been asked to start; awaiting core confirmation. */
    data object StartingVpn : AutoConnectState
    /** Core confirmed running via MSG_STATE_RUNNING broadcast. */
    data class Connected(val guid: String) : AutoConnectState
    /** Recoverable failure — retry attempt is about to start. */
    data class Reconnecting(val attempt: Int, val reason: String) : AutoConnectState
    /** Unrecoverable failure. */
    data class Failed(val reason: String) : AutoConnectState
    /** User-initiated cancel completed. */
    data object Stopping : AutoConnectState
    /** Stopped and idle. */
    data object Disconnected : AutoConnectState
}

/** Result of a single auto-connect attempt. */
sealed interface AutoConnectResult {
    data class Success(val guid: String) : AutoConnectResult
    data class Failure(val reason: String) : AutoConnectResult
    data object Cancelled : AutoConnectResult
}

private const val TAG = "ConnectionOrchestrator"

/**
 * Orchestrates the full automatic connection flow of Quiet Storm NG:
 *
 *  1. Ensure at least one subscription exists.
 *  2. Refresh the subscription (download + parse).
 *  3. Filter configs via [AutoConfigFilter].
 *  4. Select the first eligible config as the connection target.
 *  5. Fill blank finalMask / cipherSuites (never overwrite existing values).
 *  6. Run [CloudflareScanner] to find the best Cloudflare edge IP.
 *  7. Apply the best IP via [CloudflareScanner.applyBestIp].
 *  8. Start the VPN service via [LauncherManager.startService].
 *  9. Await the daemon's MSG_STATE_RUNNING broadcast (or MSG_STATE_START_FAILURE).
 *     **No fake success** — the core running signal comes from the daemon
 *     process, not from a direct call that would load native libraries in the
 *     UI process.
 *
 * Concurrency: a [Mutex] ensures single-flight — only one autoConnect call
 * runs at a time. [cancel] cancels the scanner and any pending broadcast wait.
 *
 * Transport settings (SNI, Host, Path, network, security, protocol params)
 * are never touched by this orchestrator — only server, fingerPrint, and
 * blank finalMask / cipherSuites are written, and only through
 * [CloudflareScanner.applyBestIp] and [ensureSettings].
 */
class ConnectionOrchestrator {

    private val mutex = Mutex()

    /** Cancellation flag — set by [cancel], checked between phases.
     *  Volatile because cancel() may be called from a different coroutine. */
    @Volatile
    private var cancelled = false

    /** Deferred for the current scan wait, so [cancel] can complete it. */
    @Volatile
    private var scanDeferred: CompletableDeferred<CandidateResult?>? = null

    /** Deferred for the current core-running wait, so [cancel] can complete it. */
    @Volatile
    private var coreRunningDeferred: CompletableDeferred<Boolean>? = null

    /** Registered broadcast receiver, if any, so [cancel] can unregister it. */
    @Volatile
    private var runningReceiver: BroadcastReceiver? = null

    /**
     * Runs the full auto-connect flow.
     *
     * @param context Application or activity context.
     * @param onState Called for every state transition.
     * @return [AutoConnectResult.Success] only when the daemon reports running;
     *   [AutoConnectResult.Failure] on any unrecoverable error or timeout;
     *   [AutoConnectResult.Cancelled] if [cancel] was invoked mid-flow.
     */
    suspend fun autoConnect(
        context: Context,
        onState: (AutoConnectState) -> Unit = {},
    ): AutoConnectResult {
        // Single-flight: if another autoConnect is running, bail out.
        if (!mutex.tryLock()) {
            onState(AutoConnectState.Failed("Another auto-connect is already running"))
            return AutoConnectResult.Failure("Already running")
        }
        cancelled = false
        try {
            return runFlow(context, onState)
        } finally {
            cleanup(context)
            mutex.unlock()
        }
    }

    /**
     * Cancels an in-progress auto-connect: stops the scanner, completes any
     * pending deferred waits, and unregisters the broadcast receiver.
     * Safe to call when nothing is running.
     */
    fun cancel() {
        cancelled = true
        CloudflareScanner.cancel()
        scanDeferred?.complete(null)
        coreRunningDeferred?.complete(false)
    }

    // ── Internal flow ───────────────────────────────────────────────

    private suspend fun runFlow(
        context: Context,
        onState: (AutoConnectState) -> Unit,
    ): AutoConnectResult {
        // 1. Ensure subscription exists
        val subs = MmkvManager.decodeSubscriptions()
        if (subs.isEmpty()) {
            onState(AutoConnectState.WaitingForSubscription)
            return AutoConnectResult.Failure("No subscription configured")
        }
        val targetSub = subs.first()

        // 2. Refresh subscription
        onState(AutoConnectState.FetchingSubscription)
        val updateResult = try {
            AngConfigManager.updateConfigViaSub(targetSub)
        } catch (e: Exception) {
            // Sanitize: log only the exception type. The message or stack trace
            // of a network/parse exception may embed the subscription URL,
            // auth tokens, or response body — none of which belong in logs.
            LogUtil.e(TAG, "Subscription fetch failed: " + e.javaClass.simpleName)
            return AutoConnectResult.Failure("Subscription fetch failed")
        }
        if (updateResult.configCount <= 0) {
            return AutoConnectResult.Failure("Subscription returned 0 configs")
        }
        if (cancelled) return cancelResult(onState)

        // 3. Parse configs from storage — pair GUID with Profile to avoid
        //    index misalignment from mapNotNull.
        onState(AutoConnectState.ParsingConfigurations)
        val allGuids = MmkvManager.decodeServerList(targetSub.guid)
        if (allGuids.isEmpty()) {
            return AutoConnectResult.Failure("No configs in subscription storage")
        }
        val guidProfilePairs: List<Pair<String, ProfileItem>> = allGuids.mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { guid to it }
        }
        if (guidProfilePairs.isEmpty()) {
            return AutoConnectResult.Failure("All configs failed to decode")
        }

        // 4. Filter — preserve GUID↔Profile pairing
        onState(AutoConnectState.FilteringConfigurations)
        val eligible = guidProfilePairs.filter { AutoConfigFilter.isEligible(it.second) }
        if (eligible.isEmpty()) {
            return AutoConnectResult.Failure(
                "No eligible configs (ws/xhttp + TLS + CF HTTPS port required)"
            )
        }

        // 5. Select first eligible config
        val (selectedGuid, selected) = eligible.first()
        MmkvManager.setSelectServer(selectedGuid)
        if (cancelled) return cancelResult(onState)

        // 6. Ensure settings (fill blanks only — no overwrite)
        onState(AutoConnectState.ApplyingConnectionSettings)
        ensureSettings(selectedGuid, selected)

        // 7. Scan Cloudflare IPs
        onState(AutoConnectState.ScanningCloudflareIps)
        val bestIp = scanForBestIp(context, selectedGuid)
        if (cancelled) return cancelResult(onState)
        if (bestIp == null) {
            return AutoConnectResult.Failure("Cloudflare scan found no working IP")
        }

        // 8. Apply best IP
        onState(AutoConnectState.SelectingBestIp(bestIp))
        if (!CloudflareScanner.applyBestIp(selectedGuid, bestIp)) {
            return AutoConnectResult.Failure("Failed to apply best IP to profile")
        }

        // 9. Register the running-signal listener BEFORE starting the service.
        //    startService is asynchronous, but registering first removes any
        //    window in which a fast MSG_STATE_RUNNING could be missed.
        onState(AutoConnectState.StartingVpn)
        val runningSignal = registerCoreRunningReceiver(context)
        LauncherManager.startService(context, selectedGuid)

        // 10. Await core confirmation via broadcast — no native lib loading
        val connected = awaitCoreRunning(context, runningSignal)
        if (cancelled) return cancelResult(onState)
        if (connected) {
            onState(AutoConnectState.Connected(selectedGuid))
            return AutoConnectResult.Success(selectedGuid)
        }
        onState(AutoConnectState.Failed("Core did not reach running state"))
        return AutoConnectResult.Failure("Core did not confirm running within timeout")
    }

    /**
     * Fills blank finalMask and cipherSuites on the profile using the same
     * production values the scanner uses. Existing non-blank values are
     * preserved — this only fills gaps so the config is connection-ready
     * even if the scanner does not need to run (e.g. manual IP).
     */
    private fun ensureSettings(guid: String, profile: ProfileItem) {
        var changed = false
        if (profile.finalMask.isNullOrBlank()) {
            profile.finalMask = CloudflareScanner.PRODUCTION_FINALMASK_VALUE
            changed = true
        }
        if (profile.cipherSuites.isNullOrBlank()) {
            profile.cipherSuites = CloudflareScanner.PRODUCTION_CIPHERSUITES_VALUE
            changed = true
        }
        // fingerPrint: only fill if blank. CoreOutboundBuilder.populateTlsSettings
        // already hardcodes fingerprint="unsafe" for all TLS configs at build
        // time, so the profile value is not load-bearing — but we do not
        // overwrite a non-blank value to respect user intent.
        if (profile.fingerPrint.isNullOrBlank()) {
            profile.fingerPrint = "unsafe"
            changed = true
        }
        if (changed) {
            MmkvManager.encodeServerConfig(guid, profile)
            LogUtil.i(TAG, "ensureSettings: filled blank settings for guid")
        }
    }

    /**
     * Runs the Cloudflare scanner and awaits its result via a
     * [CompletableDeferred]. Returns the best IP, or null if no candidate
     * succeeded or the scan was cancelled.
     *
     * Three outcomes are distinguished:
     *  - onFinish(best) → best.ip (non-null)
     *  - onFinish(null) → null (no working IP — completes immediately)
     *  - onCancelled → null (cancel — completes immediately)
     *
     * No polling loop — the deferred is completed by the callback itself.
     */
    private suspend fun scanForBestIp(context: Context, guid: String): String? {
        val deferred = CompletableDeferred<CandidateResult?>()
        scanDeferred = deferred

        CloudflareScanner.scan(
            context = context,
            guid = guid,
            candidates = emptyList(),
            callback = object : ScanCallback {
                override fun onProgress(result: CandidateResult, done: Int, total: Int) {}
                override fun onFinish(best: CandidateResult?) {
                    deferred.complete(best)
                }
                override fun onCancelled() {
                    deferred.complete(null)
                }
            }
        )

        // The scanner's own scope handles the work; we just await.
        // Timeout is a safety net — normally onFinish/onCancelled fires first.
        val result = withTimeoutOrNull(180_000L) { deferred.await() }
        scanDeferred = null
        return result?.ip
    }

    /**
     * Registers a [BroadcastReceiver] for the daemon's state broadcasts
     * (MSG_STATE_RUNNING / MSG_STATE_START_FAILURE) and returns the deferred
     * that will be completed when a signal arrives.
     *
     * Registering is deliberately separate from awaiting so the caller can
     * register BEFORE starting the service — closing the window in which a
     * fast broadcast could be missed.
     *
     * This avoids calling [CoreServiceManager.isRunning] directly, which would
     * eagerly initialize the native coreController (and thus load native
     * libraries) in the UI process. The daemon service sends these broadcasts
     * from its own process where the native core is already loaded.
     */
    private fun registerCoreRunningReceiver(context: Context): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        coreRunningDeferred = deferred

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // MessageHelper.messageIntent() puts the message id under the
                // "key" extra (see MessageHelper.messageIntent). This matches
                // MainRepository's receiver exactly.
                val what = intent?.getIntExtra("key", 0) ?: 0
                when (what) {
                    AppConfig.MSG_STATE_RUNNING -> deferred.complete(true)
                    AppConfig.MSG_STATE_START_FAILURE -> deferred.complete(false)
                    AppConfig.MSG_STATE_STOP_SUCCESS -> deferred.complete(false)
                }
            }
        }
        runningReceiver = receiver

        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(context, receiver, filter, Utils.receiverFlags())
        return deferred
    }

    /**
     * Awaits the deferred returned by [registerCoreRunningReceiver], then
     * unregisters the receiver. Returns true only if MSG_STATE_RUNNING was
     * received; false on MSG_STATE_START_FAILURE, stop, cancel, or timeout.
     */
    private suspend fun awaitCoreRunning(
        context: Context,
        deferred: CompletableDeferred<Boolean>,
        timeoutMs: Long = 30_000L,
    ): Boolean {
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        // Unregister here; cleanup() also guards against a second attempt.
        runningReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // Already unregistered or context invalid — safe to ignore.
            }
        }
        runningReceiver = null
        coreRunningDeferred = null
        return result == true
    }

    /**
     * Emits [AutoConnectState.Stopping] and returns [AutoConnectResult.Cancelled].
     * Called at each cancellation checkpoint so the emitted state matches the
     * real outcome (the flow was cancelled, not disconnected normally).
     */
    private fun cancelResult(onState: (AutoConnectState) -> Unit): AutoConnectResult {
        onState(AutoConnectState.Stopping)
        return AutoConnectResult.Cancelled
    }

    /** Cleans up any remaining resources after the flow completes or cancels. */
    private fun cleanup(context: Context) {
        scanDeferred?.let { if (!it.isCompleted) it.complete(null) }
        scanDeferred = null
        coreRunningDeferred?.let { if (!it.isCompleted) it.complete(false) }
        coreRunningDeferred = null
        runningReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // Already unregistered or context invalid.
            }
        }
        runningReceiver = null
    }
}
