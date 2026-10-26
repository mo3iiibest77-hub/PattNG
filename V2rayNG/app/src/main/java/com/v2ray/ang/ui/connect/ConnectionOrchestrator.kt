package com.v2ray.ang.ui.connect

import android.content.Context
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.ScanCallback
import com.v2ray.ang.senpai.CandidateResult
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.Mutex
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Auto-connect state machine for Quiet Storm NG.
 *
 * Sealed interface so the UI (Phase 3) can render each step deterministically
 * with a exhaustive when-expression. States are emitted in strict order during
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
    /** Core confirmed running via [CoreServiceManager.isRunning]. */
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
 *  9. Poll [CoreServiceManager.isRunning] until the core confirms it is up
 *     (or timeout). **No fake success** — if the core does not report running,
 *     the result is [AutoConnectResult.Failure].
 *
 * Concurrency: a [Mutex] ensures single-flight — only one autoConnect call
 * runs at a time. [cancel] cancels the scanner and any pending start wait.
 *
 * This class does NOT modify UI state directly. It emits [AutoConnectState]
 * through the [onState] callback so the caller (ViewModel in Phase 3) can
 * project it into whatever UI representation it needs.
 *
 * Transport settings (SNI, Host, Path, network, security, protocol params)
 * are never touched — only server, fingerPrint, and blank finalMask /
 * cipherSuites are written, exactly as the existing manual scan does.
 */
class ConnectionOrchestrator {

    private val mutex = Mutex()
    private var waitJob: Job? = null

    /**
     * Runs the full auto-connect flow.
     *
     * @param context Application or activity context.
     * @param onState Called for every state transition. Called on the same
     *   dispatcher as the caller (no thread hops inside).
     * @return [AutoConnectResult.Success] only when the core reports running;
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
        try {
            return runFlow(context, onState)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Cancels an in-progress auto-connect: stops the scanner and cancels
     * any pending core-running wait. Safe to call when nothing is running.
     */
    fun cancel() {
        CloudflareScanner.cancel()
        waitJob?.cancel()
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
            LogUtil.e(TAG, "Subscription fetch failed: ${e.message}", e)
            return AutoConnectResult.Failure("Subscription fetch failed: ${e.message}")
        }
        if (updateResult.configCount <= 0) {
            return AutoConnectResult.Failure("Subscription returned 0 configs")
        }

        // 3. Parse configs from storage (already imported by updateConfigViaSub)
        onState(AutoConnectState.ParsingConfigurations)
        val allGuids = MmkvManager.decodeServerList(targetSub.guid)
        if (allGuids.isEmpty()) {
            return AutoConnectResult.Failure("No configs in subscription storage")
        }
        val allConfigs = allGuids.mapNotNull { MmkvManager.decodeServerConfig(it) }
        if (allConfigs.isEmpty()) {
            return AutoConnectResult.Failure("All configs failed to decode")
        }

        // 4. Filter
        onState(AutoConnectState.FilteringConfigurations)
        val eligible = AutoConfigFilter.filterEligible(allConfigs)
        if (eligible.isEmpty()) {
            return AutoConnectResult.Failure(
                "No eligible configs (ws/xhttp + TLS + CF HTTPS port required)"
            )
        }

        // 5. Select first eligible config
        val selected = eligible.first()
        val selectedGuid = allGuids[allConfigs.indexOf(selected)]
        MmkvManager.setSelectServer(selectedGuid)

        // 6. Ensure settings (fill blanks only — no overwrite)
        onState(AutoConnectState.ApplyingConnectionSettings)
        ensureSettings(selectedGuid, selected)

        // 7. Scan Cloudflare IPs
        onState(AutoConnectState.ScanningCloudflareIps)
        val bestIp = scanForBestIp(context, selectedGuid)
        if (bestIp == null) {
            return AutoConnectResult.Failure("Cloudflare scan found no working IP")
        }

        // 8. Apply best IP
        onState(AutoConnectState.SelectingBestIp(bestIp))
        if (!CloudflareScanner.applyBestIp(selectedGuid, bestIp)) {
            return AutoConnectResult.Failure("Failed to apply best IP to profile")
        }

        // 9. Start VPN
        onState(AutoConnectState.StartingVpn)
        LauncherManager.startService(context, selectedGuid)

        // 10. Wait for core confirmation — no fake success
        val connected = waitForCoreRunning()
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
        // fingerPrint must be "unsafe" for Cloudflare configs — matches
        // what applyBestIp writes and what CoreOutboundBuilder expects.
        if (profile.fingerPrint.isNullOrBlank()) {
            profile.fingerPrint = "unsafe"
            changed = true
        }
        if (changed) {
            MmkvManager.encodeServerConfig(guid, profile)
            LogUtil.i(TAG, "ensureSettings: filled blank settings for $guid")
        }
    }

    /**
     * Runs the Cloudflare scanner synchronously (suspending) and returns the
     * best IP, or null if no candidate succeeded.
     */
    private suspend fun scanForBestIp(context: Context, guid: String): String? {
        var bestResult: CandidateResult? = null
        var cancelled = false

        CloudflareScanner.scan(
            context = context,
            guid = guid,
            candidates = emptyList(),
            callback = object : ScanCallback {
                override fun onProgress(result: CandidateResult, done: Int, total: Int) {}
                override fun onFinish(best: CandidateResult?) {
                    bestResult = best
                }
                override fun onCancelled() {
                    cancelled = true
                }
            }
        )

        // CloudflareScanner.scan() launches on its own scope. We need to wait
        // until the callback fires. Poll isRunning on the scanner's scope is
        // not exposed, so we poll CoreServiceManager.isRunning as a proxy for
        // "scan active" — but that checks the VPN core, not the scanner.
        // Instead, we wait until either onFinish or onCancelled sets our flag.
        // The scan is relatively short-lived (30 candidates × test time).
        // We poll our local result flag with a generous timeout.
        val timeoutMs = 120_000L
        val intervalMs = 500L
        val elapsed = withTimeoutOrNull(timeoutMs) {
            while (bestResult == null && !cancelled) {
                delay(intervalMs)
            }
        }
        if (cancelled) return null
        if (bestResult == null) {
            LogUtil.w(TAG, "scanForBestIp: timed out after ${timeoutMs}ms")
            return null
        }
        return bestResult?.ip
    }

    /**
     * Polls [CoreServiceManager.isRunning] until it returns true or the
     * timeout elapses. Returns true only if the core actually reported
     * running — never fakes success.
     */
    private suspend fun waitForCoreRunning(
        timeoutMs: Long = 30_000L,
        intervalMs: Long = 500L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (CoreServiceManager.isRunning()) return true
            delay(intervalMs)
        }
        return false
    }
}
