package com.v2ray.ang.ui.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.senpai.CandidateResult
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.ScanCallback
import com.v2ray.ang.ui.qsn.data.IpHealthChecker
import com.v2ray.ang.ui.qsn.data.QsPrefs
import com.v2ray.ang.ui.qsn.domain.IpFingerprint
import com.v2ray.ang.ui.qsn.domain.IpSelection
import com.v2ray.ang.ui.qsn.domain.ScanRangePlanner
import com.v2ray.ang.ui.qsn.domain.SubscriptionRules
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Auto-connect state machine for Quiet Storm NG.
 *
 * Sealed interface so the UI (Phase 3) can render each step deterministically
 * with an exhaustive when-expression. States are emitted in strict order during
 * a single [ConnectionOrchestrator.autoConnect] call.
 */
sealed interface AutoConnectState {
    /** No enabled subscription configured yet. */
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

    /** Scanner progress: [done] of [total] candidates probed so far. */
    data class ScanProgress(val done: Int, val total: Int) : AutoConnectState

    /** A previously validated edge is being reused, so no scan runs. */
    data class ReusingSavedIp(val ip: String) : AutoConnectState
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

/** How long to wait for the daemon reply to MSG_REGISTER_CLIENT. */
private const val PROBE_TIMEOUT_MS = 1_500L

/** How long to wait for MSG_STATE_RUNNING after asking the service to start. */
private const val RUNNING_TIMEOUT_MS = 30_000L

/** Safety net for a full Cloudflare scan. */
private const val SCAN_TIMEOUT_MS = 180_000L

/**
 * Pairs each GUID with its decoded [ProfileItem], dropping GUIDs whose profile
 * cannot be decoded.
 *
 * This exists as a standalone function so the GUID/profile mapping — the part
 * that previously misaligned indices — is unit-tested against the real
 * implementation instead of a copy of it.
 *
 * Order is preserved. Duplicate profiles stored under different GUIDs stay as
 * distinct pairs, so a GUID can never be attributed to another config.
 */
internal fun pairGuidsWithProfiles(
    guids: List<String>,
    decode: (String) -> ProfileItem?,
): List<Pair<String, ProfileItem>> =
    guids.mapNotNull { guid -> decode(guid)?.let { profile -> guid to profile } }

/** Signals the daemon emits about core state. */
private enum class CoreSignal { RUNNING, NOT_RUNNING, START_FAILED, STOPPED }

/**
 * Orchestrates the full automatic connection flow of Quiet Storm NG:
 *
 *  1. Pick an enabled subscription and refresh it.
 *  2. Decode stored profiles, keeping GUID/profile pairs.
 *  3. Filter via [AutoConfigFilter].
 *  4. Fill blank finalMask / cipherSuites / fingerPrint (never overwrite).
 *  5. Run [CloudflareScanner] and apply the best IP.
 *  6. Ensure the VPN core is running with the selected config.
 *
 * Core-state detection deliberately avoids CoreServiceManager.isRunning():
 * that object eagerly constructs the native core controller on first touch,
 * which would load native libraries into the UI process. Instead the daemon is
 * probed with MSG_REGISTER_CLIENT and observed through the same MSG_STATE_*
 * broadcasts that MainRepository consumes.
 *
 * Transport settings (SNI, Host, Path, network, security, auth params, xhttp
 * settings) are never modified here. Only server, and blank
 * finalMask/cipherSuites/fingerPrint are written.
 *
 * Concurrency: a [Mutex] guarantees single-flight. [cancel] stops the scanner,
 * closes the signal channel, and unregisters the receiver.
 */
class ConnectionOrchestrator {

    private val mutex = Mutex()

    /** Set by [cancel]; checked between phases. Written from another coroutine. */
    @Volatile
    private var cancelled = false

    /** Deferred for the current scan wait, so [cancel] can complete it. */
    @Volatile
    private var scanDeferred: CompletableDeferred<CandidateResult?>? = null

    /** Signal channel for the current core-state wait, so [cancel] can end it. */
    @Volatile
    private var coreSignals: Channel<CoreSignal>? = null

    /** Registered broadcast receiver, if any, so cleanup can unregister it. */
    @Volatile
    private var runningReceiver: BroadcastReceiver? = null

    /**
     * Runs the full auto-connect flow.
     *
     * @return [AutoConnectResult.Success] only when the daemon reports running
     *   after this call's start/restart request; [AutoConnectResult.Failure] on
     *   any unrecoverable error or timeout; [AutoConnectResult.Cancelled] if
     *   [cancel] was invoked mid-flow.
     */
    suspend fun autoConnect(
        context: Context,
        onState: (AutoConnectState) -> Unit = {},
    ): AutoConnectResult {
        // Single-flight: a second concurrent attempt is rejected, not queued.
        if (!mutex.tryLock()) {
            onState(AutoConnectState.Failed("Another auto-connect is already running"))
            return AutoConnectResult.Failure("Already running")
        }
        cancelled = false
        return try {
            // The subscription fetch is blocking network I/O, so the flow must
            // never run on the caller thread (a caller on Dispatchers.Main
            // would ANR). Dispatching here makes that impossible to get wrong.
            withContext(Dispatchers.IO) { runFlow(context, onState) }
        } catch (e: CancellationException) {
            // The caller went away mid-flow (activity destroyed, scope
            // cancelled). CloudflareScanner runs in its own scope, so it must be
            // cancelled explicitly or it keeps probing and writing temp
            // profiles after nobody is listening.
            cancel()
            throw e
        } catch (e: Exception) {
            // Never leak exception text: it may embed the subscription URL,
            // response body, or credentials.
            LogUtil.e(TAG, "autoConnect failed: " + e.javaClass.simpleName)
            onState(AutoConnectState.Failed("Unexpected error"))
            AutoConnectResult.Failure("Unexpected error")
        } finally {
            cleanup(context)
            mutex.unlock()
        }
    }

    /**
     * Runs only the Cloudflare scan for one configuration, without touching the
     * tunnel. Used by the IP Ranges screen so the user can measure candidates
     * on demand instead of waiting for a connection attempt.
     *
     * The winner is persisted exactly as in a normal connect, so the next
     * connect reuses it instead of scanning again.
     *
     * @return the winning candidate, or null when nothing answered or the
     *   caller cancelled.
     */
    suspend fun scanOnly(
        context: Context,
        guid: String,
        profile: ProfileItem,
        onState: (AutoConnectState) -> Unit = {},
    ): CandidateResult? {
        if (!mutex.tryLock()) {
            onState(AutoConnectState.Failed("Another operation is already running"))
            return null
        }
        cancelled = false
        return try {
            withContext(Dispatchers.IO) {
                val fingerprint = fingerprintFor(guid, profile)
                onState(AutoConnectState.ScanningCloudflareIps)
                scanForBestIp(context, guid, onState, fingerprint)
            }
        } catch (e: CancellationException) {
            cancel()
            throw e
        } catch (e: Exception) {
            LogUtil.e(TAG, "scanOnly failed: " + e.javaClass.simpleName)
            onState(AutoConnectState.Failed("Scan failed"))
            null
        } finally {
            cleanup(context)
            mutex.unlock()
        }
    }

    /**
     * Cancels an in-progress auto-connect. Safe to call when nothing is running.
     */
    fun cancel() {
        cancelled = true
        CloudflareScanner.cancel()
        scanDeferred?.complete(null)
        coreSignals?.close()
    }

    // -- Flow -------------------------------------------------------

    private suspend fun runFlow(
        context: Context,
        onState: (AutoConnectState) -> Unit,
    ): AutoConnectResult {
        // 1. An explicitly selected configuration wins over every subscription.
        //    It may be a manual import, so it has to work with no subscription
        //    at all, and selecting it must not trigger a refresh the user did
        //    not ask for.
        val pinnedGuid = QsPrefs.pinnedGuid()
        if (pinnedGuid != null) {
            val pinned = MmkvManager.decodeServerConfig(pinnedGuid)
            when {
                pinned == null -> {
                    // The configuration was deleted: drop the stale pin and
                    // fall through to the subscription path.
                    QsPrefs.setPinnedGuid(null)
                    LogUtil.i(TAG, "Pinned configuration no longer exists")
                }

                !AutoConfigFilter.isEligible(pinned) -> {
                    onState(
                        AutoConnectState.Failed(
                            "The selected configuration cannot be used for Cloudflare scanning"
                        )
                    )
                    return AutoConnectResult.Failure("Selected configuration is not eligible")
                }

                else -> return connectSelected(context, pinnedGuid, pinned, onState)
            }
        }

        // 2. Pick an enabled subscription.
        val subs = MmkvManager.decodeSubscriptions()
        if (subs.isEmpty()) {
            onState(AutoConnectState.WaitingForSubscription)
            return AutoConnectResult.Failure("No subscription configured")
        }
        // The panel and the flow must agree on which subscription is active,
        // so both use the same pure rule.
        val targetSub = SubscriptionRules.chooseActive(subs, QsPrefs.activeSubscriptionGuid())
            ?: run {
                onState(AutoConnectState.WaitingForSubscription)
                return AutoConnectResult.Failure("No enabled subscription configured")
            }

        // 3. Refresh it.
        onState(AutoConnectState.FetchingSubscription)
        val updateResult = try {
            AngConfigManager.updateConfigViaSub(targetSub)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Subscription fetch failed: " + e.javaClass.simpleName)
            return AutoConnectResult.Failure("Subscription fetch failed")
        }
        if (updateResult.configCount <= 0) {
            return AutoConnectResult.Failure("Subscription returned 0 configs")
        }
        if (cancelled) return cancelResult(onState)

        // 4. Decode stored profiles, keeping GUID/profile pairing intact.
        onState(AutoConnectState.ParsingConfigurations)
        val allGuids = MmkvManager.decodeServerList(targetSub.guid)
        if (allGuids.isEmpty()) {
            return AutoConnectResult.Failure("No configs in subscription storage")
        }
        val pairs = pairGuidsWithProfiles(allGuids) { MmkvManager.decodeServerConfig(it) }
        if (pairs.isEmpty()) {
            return AutoConnectResult.Failure("All configs failed to decode")
        }

        // 5. Filter, preserving pairs.
        onState(AutoConnectState.FilteringConfigurations)
        val eligible = pairs.filter { AutoConfigFilter.isEligible(it.second) }
        if (eligible.isEmpty()) {
            return AutoConnectResult.Failure(
                "No eligible configs (ws/xhttp + TLS + CF HTTPS port required)"
            )
        }

        // 6. Use the first eligible config; the GUID comes from the same pair.
        val (selectedGuid, selected) = eligible.first()
        return connectSelected(context, selectedGuid, selected, onState)
    }

    /**
     * Applies the connection settings, resolves the edge and starts the core
     * for one configuration.
     *
     * Shared by the pinned-configuration path and the subscription path so both
     * behave identically from this point on. The caller owns cancellation and
     * eligibility checks.
     */
    private suspend fun connectSelected(
        context: Context,
        selectedGuid: String,
        selected: ProfileItem,
        onState: (AutoConnectState) -> Unit,
    ): AutoConnectResult {
        MmkvManager.setSelectServer(selectedGuid)
        if (cancelled) return cancelResult(onState)

        // Fill blank connection settings without overwriting existing ones.
        onState(AutoConnectState.ApplyingConnectionSettings)
        ensureSettings(selectedGuid, selected)

        // Resolve the edge IP. A valid saved selection is reused; a scan only
        // runs when there is nothing usable to reuse.
        val fingerprint = fingerprintFor(selectedGuid, selected)
        val bestIp = resolveEdgeIp(context, selectedGuid, selected, fingerprint, onState)
        if (cancelled) return cancelResult(onState)
        if (bestIp == null) {
            return AutoConnectResult.Failure("Cloudflare scan found no working IP")
        }

        // Apply it (only server + blank fields change).
        onState(AutoConnectState.SelectingBestIp(bestIp))
        if (!CloudflareScanner.applyBestIp(selectedGuid, bestIp)) {
            return AutoConnectResult.Failure("Failed to apply best IP to profile")
        }

        // Make the core run this exact config.
        // A missing VPN consent makes CoreVpnService.setupVpnService() return
        // false and stopSelf() WITHOUT broadcasting START_FAILURE, so waiting
        // would burn the full 30 s timeout and then report a misleading core
        // start failure. Fail fast instead.
        if (SettingsManager.isVpnMode() && VpnService.prepare(context) != null) {
            onState(AutoConnectState.Failed("VPN permission required"))
            return AutoConnectResult.Failure("VPN permission required")
        }
        onState(AutoConnectState.StartingVpn)
        val connected = ensureCoreRunning(context, selectedGuid)
        if (cancelled) return cancelResult(onState)
        if (connected) {
            onState(AutoConnectState.Connected(selectedGuid))
            return AutoConnectResult.Success(selectedGuid)
        }
        onState(AutoConnectState.Failed("Core did not reach running state"))
        return AutoConnectResult.Failure("Core did not confirm running within timeout")
    }

    /**
     * Fills blank finalMask, cipherSuites and fingerPrint. Existing values are
     * preserved: CoreOutboundBuilder.populateTlsSettings already forces
     * fingerprint = "unsafe" at build time, so the stored value is not
     * load-bearing and must not be overwritten blindly.
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
        if (profile.fingerPrint.isNullOrBlank()) {
            profile.fingerPrint = "unsafe"
            changed = true
        }
        if (changed) {
            MmkvManager.encodeServerConfig(guid, profile)
            LogUtil.i(TAG, "ensureSettings: filled blank connection settings")
        }
    }

    /**
     * Runs [CloudflareScanner] and awaits its callback.
     *
     * Distinguishes three outcomes without polling:
     *  - onFinish(best)  -> the winning candidate
     *  - onFinish(null)  -> null, immediately (scan finished with no winner)
     *  - onCancelled()   -> null, immediately
     *
     * If the safety timeout fires first, the scan is cancelled so it does not
     * keep running in its own scope after the flow has given up.
     */
    private suspend fun scanForBestIp(
        context: Context,
        guid: String,
        onState: (AutoConnectState) -> Unit,
        fingerprint: String,
    ): CandidateResult? {
        val deferred = CompletableDeferred<CandidateResult?>()
        scanDeferred = deferred

        CloudflareScanner.scan(
            context = context,
            guid = guid,
            // Empty means the scanner falls back to the bundled Cloudflare
            // ranges; a non-empty list means the user configured ranges.
            candidates = ScanRangePlanner.plan(QsPrefs.loadRanges()),
            callback = object : ScanCallback {
                override fun onProgress(result: CandidateResult, done: Int, total: Int) {
                    onState(AutoConnectState.ScanProgress(done, total))
                }
                override fun onFinish(best: CandidateResult?) {
                    deferred.complete(best)
                }
                override fun onCancelled() {
                    deferred.complete(null)
                }
            }
        )

        val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) { deferred.await() }
        if (!deferred.isCompleted) {
            // Timed out: stop the orphaned scan before returning.
            LogUtil.w(TAG, "scanForBestIp: timed out, cancelling scan")
            CloudflareScanner.cancel()
        }
        scanDeferred = null
        val winner = result ?: return null

        // Persist the winner with enough metadata to skip the next scan.
        QsPrefs.saveSelection(
            IpSelection(
                fingerprint = fingerprint,
                ip = winner.ip,
                score = winner.score,
                latencyMs = winner.latencyMs.takeIf { it >= 0 },
                jitterMs = winner.jitterMs,
                downloadKBps = if (winner.hasTraffic) winner.downloadKBps else null,
                uploadKBps = if (winner.hasTraffic) winner.uploadKBps else null,
                confidence = winner.confidence,
                validatedAt = System.currentTimeMillis(),
            )
        )
        QsPrefs.markScanned(System.currentTimeMillis())
        return winner
    }

    /**
     * Reuses the saved edge when it is still valid, otherwise scans.
     *
     * The cache key covers the transport identity of the profile and the
     * enabled range set, so editing the config or changing the ranges
     * invalidates it. A fresh selection is reused with no network round trip,
     * which is what makes a reconnect cheap.
     */
    private suspend fun resolveEdgeIp(
        context: Context,
        guid: String,
        profile: ProfileItem,
        fingerprint: String,
        onState: (AutoConnectState) -> Unit,
    ): String? {
        val port = profile.serverPort?.toIntOrNull() ?: 0
        val saved = QsPrefs.loadSelection()
        val now = System.currentTimeMillis()
        if (saved != null && saved.isUsable(now, fingerprint)) {
            if (saved.isFresh(now)) {
                LogUtil.i(TAG, "Reusing saved edge without a scan")
                onState(AutoConnectState.ReusingSavedIp(saved.ip))
                return saved.ip
            }
            if (IpHealthChecker.isHealthy(saved.ip, port, profile.sni)) {
                LogUtil.i(TAG, "Saved edge passed the bounded health check")
                onState(AutoConnectState.ReusingSavedIp(saved.ip))
                QsPrefs.saveSelection(saved.copy(validatedAt = now))
                return saved.ip
            }
            LogUtil.w(TAG, "Saved edge failed the health check; rescanning")
            QsPrefs.clearSelection()
        }
        onState(AutoConnectState.ScanningCloudflareIps)
        return scanForBestIp(context, guid, onState, fingerprint)?.ip
    }

    /**
     * Builds the cache key. The server field is excluded on purpose: applying
     * the chosen IP rewrites it, so including it would invalidate the entry the
     * moment it is used.
     */
    private fun fingerprintFor(guid: String, profile: ProfileItem): String =
        IpFingerprint.of(
            guid = guid,
            configType = profile.configType.name,
            port = profile.serverPort?.toIntOrNull() ?: 0,
            network = profile.network.orEmpty(),
            security = profile.security.orEmpty(),
            sni = profile.sni,
            host = profile.host,
            path = profile.path,
            xhttpMode = profile.xhttpMode,
            rangeFingerprint = ScanRangePlanner.fingerprint(QsPrefs.loadRanges()),
        )

    /**
     * Ensures the core is running the currently selected config.
     *
     * Flow:
     *  1. Register a receiver for the daemon MSG_STATE_* broadcasts.
     *  2. Probe the daemon with MSG_REGISTER_CLIENT (the running core answers
     *     MSG_STATE_RUNNING or MSG_STATE_NOT_RUNNING).
     *  3. If already running, restart so the newly selected config and best IP
     *     take effect; otherwise start the service.
     *  4. Wait for MSG_STATE_START_SUCCESS (or a RUNNING probe reply); fail
     *     on MSG_STATE_START_FAILURE.
     *
     * MSG_STATE_STOP_SUCCESS is ignored while waiting, because a restart
     * legitimately emits it before the new core comes up.
     *
     * Returns true only when the daemon actually reported running.
     */
    private suspend fun ensureCoreRunning(context: Context, guid: String): Boolean {
        val signals = Channel<CoreSignal>(Channel.UNLIMITED)
        coreSignals = signals

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // MessageHelper.messageIntent() stores the id under "key".
                val signal = when (intent?.getIntExtra("key", 0) ?: 0) {
                    // The daemon announces a successful start with START_SUCCESS.
                    // MSG_STATE_RUNNING is only ever sent as a reply to the
                    // MSG_REGISTER_CLIENT probe, so both mean "core is up".
                    AppConfig.MSG_STATE_START_SUCCESS,
                    AppConfig.MSG_STATE_RUNNING -> CoreSignal.RUNNING
                    AppConfig.MSG_STATE_NOT_RUNNING -> CoreSignal.NOT_RUNNING
                    AppConfig.MSG_STATE_START_FAILURE -> CoreSignal.START_FAILED
                    AppConfig.MSG_STATE_STOP_SUCCESS -> CoreSignal.STOPPED
                    else -> null
                }
                if (signal != null) signals.trySend(signal)
            }
        }
        runningReceiver = receiver

        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
                Utils.receiverFlags(),
            )

            // Probe current state. No reply means no running daemon.
            MessageHelper.sendMsg2Service(context, AppConfig.MSG_REGISTER_CLIENT, "")
            val probe = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                signals.receiveCatching().getOrNull()
            }
            if (cancelled) return false

            if (probe == CoreSignal.RUNNING) {
                // Already up, possibly on the previous config/IP: restart so the
                // selection made above actually takes effect.
                LogUtil.i(TAG, "Core already running; restarting to apply selected config")
                LauncherManager.restartService(context)
            } else {
                LauncherManager.startService(context, guid)
            }

            var connected = false
            withTimeoutOrNull(RUNNING_TIMEOUT_MS) {
                for (signal in signals) {
                    when (signal) {
                        CoreSignal.RUNNING -> {
                            connected = true
                            return@withTimeoutOrNull
                        }
                        CoreSignal.START_FAILED -> return@withTimeoutOrNull
                        // A restart emits STOPPED on the way back up; keep waiting.
                        CoreSignal.NOT_RUNNING, CoreSignal.STOPPED -> Unit
                    }
                }
            }
            return connected
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // Already unregistered or context invalid.
            }
            runningReceiver = null
            coreSignals = null
            signals.close()
        }
    }

    /** Emits [AutoConnectState.Stopping] and returns [AutoConnectResult.Cancelled]. */
    private fun cancelResult(onState: (AutoConnectState) -> Unit): AutoConnectResult {
        onState(AutoConnectState.Stopping)
        return AutoConnectResult.Cancelled
    }

    /** Releases anything still held after the flow ends. */
    private fun cleanup(context: Context) {
        scanDeferred?.let { if (!it.isCompleted) it.complete(null) }
        scanDeferred = null
        coreSignals?.close()
        coreSignals = null
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
