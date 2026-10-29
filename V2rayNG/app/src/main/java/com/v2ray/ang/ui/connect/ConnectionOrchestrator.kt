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
import com.v2ray.ang.senpai.DiscoveryOutcome
import com.v2ray.ang.senpai.IspManager
import com.v2ray.ang.senpai.ProbeProfile
import com.v2ray.ang.senpai.ScanCallback
import com.v2ray.ang.ui.qsn.data.IpHealthChecker
import com.v2ray.ang.ui.qsn.data.QualifiedRangeStore
import com.v2ray.ang.ui.qsn.data.QsPrefs
import com.v2ray.ang.ui.qsn.domain.CandidateSelection
import com.v2ray.ang.ui.qsn.domain.ConfigChoice
import com.v2ray.ang.ui.qsn.domain.ConnectDecision
import com.v2ray.ang.ui.qsn.domain.ConnectMode
import com.v2ray.ang.ui.qsn.domain.ConnectPolicy
import com.v2ray.ang.ui.qsn.domain.EdgeApplication
import com.v2ray.ang.ui.qsn.domain.IpAddress
import com.v2ray.ang.ui.qsn.domain.IpFingerprint
import com.v2ray.ang.ui.qsn.domain.IpSelection
import com.v2ray.ang.ui.qsn.domain.QualifiedRange
import com.v2ray.ang.ui.qsn.domain.RangeSet
import com.v2ray.ang.ui.qsn.domain.Stage2Ranges
import com.v2ray.ang.ui.qsn.domain.ScanCandidate
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
    /** Stage 1: qualifying Cloudflare ranges before any candidate is scanned. */
    data object DiscoveringRanges : AutoConnectState

    /** Stage 1 progress: [done] of [total] ranges measured, [qualified] this one. */
    data class DiscoveryProgress(val done: Int, val total: Int, val qualified: Boolean) : AutoConnectState

    /** CloudflareScanner is probing candidate IPs. */
    data object ScanningCloudflareIps : AutoConnectState
    /** Scanner finished; writing best IP to the profile. */
    data class SelectingBestIp(val bestIp: String) : AutoConnectState

    /** Scanner progress: [done] of [total] candidates probed so far. */
    data class ScanProgress(val done: Int, val total: Int) : AutoConnectState

    /**
     * The eligible configurations of the active subscription, waiting for the
     * user to choose which one to scan with. [selected] is the pre-selection
     * (the first eligible config); the flow does not continue until the user
     * confirms, so no scan runs against a config the user did not pick.
     */
    data class AwaitingConfigChoice(
        val choices: List<ConfigChoice>,
        val selected: String?,
    ) : AutoConnectState

    /** One candidate finished probing and is reported while the scan runs. */
    data class ScanCandidateFound(
        val candidate: ScanCandidate,
        val done: Int,
        val total: Int,
    ) : AutoConnectState

    /**
     * The scan finished. [suggestedIp] is the ranker's winner, but the choice
     * belongs to the user: nothing is written until they confirm.
     */
    data class AwaitingIpChoice(
        val candidates: List<ScanCandidate>,
        val suggestedIp: String?,
    ) : AutoConnectState

    /** The confirmed edge is being written to every eligible config of the scope. */
    data class ApplyingEdge(val ip: String, val configCount: Int) : AutoConnectState

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

/**
 * Safety net for a full Cloudflare scan.
 *
 * Thirty candidates at concurrency four, each with three latency probes and a
 * bounded 1 MiB up + 1 MiB down test, do not fit in three minutes on a slow
 * mobile link. When this expired the flow reported "no working IP" while
 * candidates were still being measured, which is the post-scan failure. A
 * timeout now keeps the measurements that already arrived instead of throwing
 * them away.
 */
private const val SCAN_TIMEOUT_MS = 300_000L

/**
 * Safety net for Stage 1.
 *
 * Discovery measures the whole dataset and is resumable, so a connection must
 * not wait for it indefinitely. When this expires the flow continues with the
 * ranges already stored; the discovery run itself is left running (it is not
 * cancelled) and its progress stays visible on the IP Ranges screen.
 */
private const val DISCOVERY_TIMEOUT_MS = 300_000L

/**
 * How long a first connection waits for one usable range.
 *
 * The discovery run is not bounded by this: it keeps measuring the whole
 * dataset in the background. The timeout only decides how long the connection
 * attempt waits before it falls back to whatever is already stored.
 */
private const val FIRST_RANGE_TIMEOUT_MS = 120_000L

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

    /** Pending "which config" question, completed by [chooseConfig] or [cancel]. */
    @Volatile
    private var configChoice: CompletableDeferred<String?>? = null

    /** Pending "which IP" question, completed by [chooseIp] or [cancel]. */
    @Volatile
    private var ipChoice: CompletableDeferred<String?>? = null

    /**
     * A candidate the user confirmed while the scan was still running.
     *
     * It is kept apart from [ipChoice] because the "which IP" question is only
     * asked after the scan ends. An early pick must survive the scan's own
     * cancellation, so it is consumed once, after the scan returns.
     */
    @Volatile
    private var earlyPick: String? = null

    /** Deferred for the current discovery wait, so [cancel] can end it. */
    @Volatile
    private var discoveryDeferred: CompletableDeferred<DiscoveryOutcome>? = null

    /**
     * Answers that arrived before the flow started waiting for them.
     *
     * The state broadcast and the suspending await are two steps: a click in
     * between used to find no deferred and be dropped, leaving the flow parked
     * forever. Buffering the answer closes that window in both directions.
     */
    @Volatile
    private var pendingConfigPick: String? = null

    @Volatile
    private var pendingIpPick: String? = null

    /** True while a flow owns this orchestrator; a stale click cannot buffer. */
    @Volatile
    private var flowActive: Boolean = false

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
        flowActive = true
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
     * Connects one stored configuration exactly as it is.
     *
     * This is the Configurations screen's action. It is a different operation
     * from [autoConnect], not a flag on it:
     *  - no subscription refresh,
     *  - no Cloudflare eligibility filter,
     *  - no discovery, no candidate scan, no edge rewrite,
     *  - no substitution of another profile.
     *
     * It shares the single-flight mutex, the VPN consent check and the daemon
     * confirmation with the automatic path, so a direct connect can never run
     * beside a scan and can never report Connected before the core says so.
     */
    suspend fun directConnect(
        context: Context,
        guid: String,
        onState: (AutoConnectState) -> Unit = {},
    ): AutoConnectResult {
        if (!mutex.tryLock()) {
            onState(AutoConnectState.Failed("Another operation is already running"))
            return AutoConnectResult.Failure("Already running")
        }
        cancelled = false
        flowActive = true
        return try {
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile == null) {
                onState(AutoConnectState.Failed("Configuration not found"))
                AutoConnectResult.Failure("Configuration not found")
            } else {
                withContext(Dispatchers.IO) {
                    connectSelected(context, guid, profile, onState, ConnectMode.Direct)
                }
            }
        } catch (e: CancellationException) {
            cancel()
            throw e
        } catch (e: Exception) {
            LogUtil.e(TAG, "directConnect failed: " + e.javaClass.simpleName)
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
        ispName: String? = null,
        onState: (AutoConnectState) -> Unit = {},
    ): CandidateResult? {
        if (!mutex.tryLock()) {
            onState(AutoConnectState.Failed("Another operation is already running"))
            return null
        }
        cancelled = false
        flowActive = true
        return try {
            withContext(Dispatchers.IO) {
                onState(AutoConnectState.ScanningCloudflareIps)
                // Stage 2 measures the ranges that really qualified for the
                // chosen ISP. The global store is the fallback for a connection
                // started before any ISP was named; the user's manual ranges are
                // the last resort and stay supported.
                val isp = ispName?.trim()?.ifEmpty { null } ?: QsPrefs.lastIsp()
                // Named ispProfile: "profile" is this function's ProfileItem.
                val ispProfile = isp?.let { IspManager.getProfile(context, it) }
                // Stage 2 measures what the user picked for this ISP: the
                // selection when there is one, otherwise its good ranges. A
                // failed or unchecked range never joins on its own, and another
                // ISP's ranges can never leak in.
                val ranges = if (ispProfile != null) {
                    Stage2Ranges.resolve(
                        good = ispProfile.goodCidrs,
                        manual = ispProfile.manualCidrs,
                        selected = QsPrefs.selectedRanges(ispProfile.name),
                    )
                } else {
                    RangeSet.cidrs(QualifiedRangeStore.snapshot())
                }
                val winner = scanForBestIp(context, guid, onState, ranges) { }
                    ?: return@withContext null
                // Persist the winner so the next connect can reuse it; the
                // cache TTL and health check still apply.
                QsPrefs.saveSelection(
                    IpSelection(
                        fingerprint = fingerprintFor(guid, profile),
                        ip = winner.ip,
                        score = winner.score,
                        latencyMs = winner.latencyMs.takeIf { it >= 0L },
                        jitterMs = winner.jitterMs,
                        downloadKBps = if (winner.hasTraffic) winner.downloadKBps else null,
                        uploadKBps = if (winner.hasTraffic) winner.uploadKBps else null,
                        confidence = winner.confidence,
                        validatedAt = System.currentTimeMillis(),
                    )
                )
                QsPrefs.markScanned(System.currentTimeMillis())
                winner
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
        discoveryDeferred?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(
                    DiscoveryOutcome(
                        qualified = emptyList(),
                        scanned = 0,
                        total = 0,
                        nextIndex = 0,
                        exhausted = false,
                        cancelled = true,
                    ),
                )
            }
        }
        coreSignals?.close()
        // A flow parked on a user question must not keep running after Cancel.
        configChoice?.complete(null)
        ipChoice?.complete(null)
    }

    /**
     * Answers the pending "which configuration" question.
     *
     * Ignored when nothing is waiting, so a stale UI click cannot resume a
     * flow that already moved on.
     */
    fun chooseConfig(guid: String) {
        if (configChoice?.complete(guid) != true && flowActive) {
            pendingConfigPick = guid
        }
    }

    /**
     * Answers the pending "which IP" question.
     *
     * The value is validated against the measured candidates inside the flow;
     * an IP that was never measured is rejected there.
     */
    fun chooseIp(ip: String) {
        if (ipChoice?.complete(ip) != true && flowActive) {
            pendingIpPick = ip
        }
    }

    /**
     * Confirms a candidate while the scan is still running.
     *
     * The pick is stored in [earlyPick] because the "which IP" question is only
     * asked after the scan ends; completing [ipChoice] covers the case where the
     * scan already finished and the flow is parked. The scanner is cancelled so
     * no further candidate is measured, and a callback that arrives afterwards
     * cannot replace the choice: the flow reads [earlyPick] once.
     */
    fun chooseIpNow(ip: String) {
        earlyPick = ip
        if (ipChoice?.complete(ip) != true && flowActive) {
            // The scan may already have finished and the flow may not be
            // parked yet: keep the answer for the waiter.
            pendingIpPick = ip
        }
        CloudflareScanner.cancel()
    }

    /**
     * Persists and applies one measured candidate outside a connect flow.
     *
     * Used by the standalone scan screen when the user confirms a candidate: it
     * writes the selection and the edge, and deliberately does not start the
     * tunnel. The profile keeps its GUID and every transport setting; only the
     * edge field changes.
     *
     * @return true when the edge was written to at least one configuration.
     */
    fun applyCandidateNow(guid: String, ip: String): Boolean {
        if (!IpAddress.isIpv4(ip)) return false
        val profile = MmkvManager.decodeServerConfig(guid) ?: return false
        QsPrefs.saveSelection(
            IpSelection(
                fingerprint = fingerprintFor(guid, profile),
                ip = ip,
                validatedAt = System.currentTimeMillis(),
            )
        )
        QsPrefs.markScanned(System.currentTimeMillis())
        // The standalone scan is confirmed for the configuration it ran
        // against: report success only when that one really holds the edge.
        return applyEdgeToScope(guid, profile, ip).selectedApplied
    }

    /** True while the flow waits for the user; used by the UI to keep Cancel. */
    val awaitingUserInput: Boolean
        get() = configChoice != null || ipChoice != null

    private suspend fun awaitConfigChoice(): String? {
        pendingConfigPick?.let {
            pendingConfigPick = null
            return it
        }
        val deferred = CompletableDeferred<String?>()
        configChoice = deferred
        // A click between publishing the question and this line found no
        // deferred; deliver it now instead of waiting forever.
        pendingConfigPick?.let {
            pendingConfigPick = null
            deferred.complete(it)
        }
        return try {
            deferred.await()
        } finally {
            configChoice = null
        }
    }

    private suspend fun awaitIpChoice(): String? {
        pendingIpPick?.let {
            pendingIpPick = null
            return it
        }
        val deferred = CompletableDeferred<String?>()
        ipChoice = deferred
        pendingIpPick?.let {
            pendingIpPick = null
            deferred.complete(it)
        }
        return try {
            deferred.await()
        } finally {
            ipChoice = null
        }
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
                    // A pin set by a direct connect may be a configuration this
                    // flow cannot optimise (another port, another transport).
                    // Fall through to the subscription path instead of failing
                    // the Connect tap: the pin still belongs to the direct
                    // path, and this path needs an eligible configuration.
                    LogUtil.i(TAG, "Pinned configuration is not Cloudflare-eligible; using the subscription path")
                }

                else -> return connectSelected(context, pinnedGuid, pinned, onState, ConnectMode.Automatic)
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

        // 6. The user chooses which eligible config to scan with. The list is
        //    built from this subscription only, so a config from another
        //    subscription can never appear here.
        val subscriptionName = targetSub.subscription.remarks
            .ifBlank { targetSub.subscription.url }
        val choices = eligible.map { (guid, profile) ->
            ConfigChoice.from(guid, profile, subscriptionName)
        }
        onState(AutoConnectState.AwaitingConfigChoice(choices, choices.firstOrNull()?.guid))
        val pickedGuid = awaitConfigChoice()
        if (cancelled || pickedGuid == null) return cancelResult(onState)
        val picked = eligible.firstOrNull { it.first == pickedGuid }
            ?: return AutoConnectResult.Failure("Selected configuration is gone")
        return connectSelected(context, picked.first, picked.second, onState, ConnectMode.Automatic)
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
        mode: ConnectMode,
    ): AutoConnectResult {
        MmkvManager.setSelectServer(selectedGuid)
        if (cancelled) return cancelResult(onState)

        // Fill blank connection settings without overwriting existing ones.
        // On the direct path this is the only write the profile receives.
        onState(AutoConnectState.ApplyingConnectionSettings)
        ensureSettings(selectedGuid, selected)
        if (cancelled) return cancelResult(onState)

        if (mode == ConnectMode.Direct) {
            // Direct connection: this exact profile, exactly as stored. No
            // discovery, no candidate scan, no edge rewrite, no subscription
            // refresh, no substitution of another configuration.
            LogUtil.i(TAG, "Direct connect: starting the core with the stored configuration")
            return startTunnel(context, selectedGuid, onState)
        }

        // Automatic optimisation: resolve an edge, then start.
        val fingerprint = fingerprintFor(selectedGuid, selected)
        val bestIp = resolveEdgeIp(context, selectedGuid, selected, fingerprint, onState)
        if (cancelled) return cancelResult(onState)
        if (bestIp == null) {
            return AutoConnectResult.Failure("Cloudflare scan found no working IP")
        }

        // Write the confirmed edge to every eligible config of the scope: the
        // whole subscription, or just this manual config.
        onState(AutoConnectState.SelectingBestIp(bestIp))
        val applied = applyEdgeToScope(selectedGuid, selected, bestIp)
        if (!applied.selectedApplied) {
            // The tunnel would dial the old address: this is a real failure,
            // not a partial success.
            return AutoConnectResult.Failure("Failed to apply the edge to the selected configuration")
        }
        if (applied.failed > 0) {
            // Some sibling configurations kept the previous edge. The selected
            // one is correct, so the connection can proceed, but the count is
            // reported truthfully instead of claiming every profile changed.
            LogUtil.w(
                TAG,
                "Edge applied to " + applied.applied + " configuration(s), " + applied.failed + " failed",
            )
        }
        onState(AutoConnectState.ApplyingEdge(bestIp, applied.applied))

        val result = startTunnel(context, selectedGuid, onState)
        if (result is AutoConnectResult.Success) {
            // The tunnel is confirmed: keep collecting ranges in the background.
            // The scheduler only writes the range database, never the profile
            // the tunnel is using.
            DiscoveryScheduler.start(
                context,
                selectedGuid,
                QsPrefs.lastIsp() ?: DiscoveryScheduler.DEFAULT_ISP,
            )
        }
        return result
    }

    /**
     * Starts the core and waits for the daemon to confirm it is running.
     *
     * A missing VPN consent makes CoreVpnService.setupVpnService() return false
     * and stopSelf() WITHOUT broadcasting START_FAILURE, so waiting would burn
     * the full 30 s timeout and then report a misleading core failure. Fail
     * fast instead.
     */
    private suspend fun startTunnel(
        context: Context,
        guid: String,
        onState: (AutoConnectState) -> Unit,
    ): AutoConnectResult {
        if (SettingsManager.isVpnMode() && VpnService.prepare(context) != null) {
            onState(AutoConnectState.Failed("VPN permission required"))
            return AutoConnectResult.Failure("VPN permission required")
        }
        onState(AutoConnectState.StartingVpn)
        val connected = ensureCoreRunning(context, guid)
        if (cancelled) return cancelResult(onState)
        if (connected) {
            onState(AutoConnectState.Connected(guid))
            return AutoConnectResult.Success(guid)
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
        if (fillBlankSettings(profile)) {
            MmkvManager.encodeServerConfig(guid, profile)
            LogUtil.i(TAG, "ensureSettings: filled blank connection settings")
        }
    }

    /**
     * Fills the connection settings this flow needs when the profile has none.
     *
     * finalMask and cipherSuites are real ProfileItem fields and are part of the
     * outbound this flow measures through. A stored value is never overwritten:
     * it is user data, and replacing it would silently change a working
     * transport. Only blanks are filled.
     *
     * @return true when the profile changed and must be persisted.
     */
    private fun fillBlankSettings(profile: ProfileItem): Boolean {
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
        return changed
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
        cidrs: List<String> = emptyList(),
        onCandidate: (ScanCandidate) -> Unit,
    ): CandidateResult? {
        val deferred = CompletableDeferred<CandidateResult?>()
        scanDeferred = deferred

        val callback = object : ScanCallback {
            override fun onProgress(result: CandidateResult, done: Int, total: Int) {
                // Live delivery: every measured candidate reaches the UI while
                // the scan runs, not only when it finishes.
                val candidate = ScanCandidate.from(result)
                onCandidate(candidate)
                onState(AutoConnectState.ScanCandidateFound(candidate, done, total))
                onState(AutoConnectState.ScanProgress(done, total))
            }

            override fun onFinish(best: CandidateResult?) {
                deferred.complete(best)
            }

            override fun onCancelled() {
                deferred.complete(null)
            }
        }

        if (cidrs.isEmpty()) {
            // Empty means the scanner falls back to the bundled Cloudflare
            // ranges; a non-empty list means the user configured ranges.
            CloudflareScanner.scan(
                context = context,
                guid = guid,
                candidates = ScanRangePlanner.plan(QsPrefs.loadRanges()),
                callback = callback,
            )
        } else {
            // Stage 2 over the qualified ranges, measured by the same runScan.
            CloudflareScanner.scanRanges(
                context = context,
                guid = guid,
                cidrs = cidrs,
                callback = callback,
            )
        }

        val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) { deferred.await() }
        if (!deferred.isCompleted) {
            // Timed out: stop the orphaned scan before returning.
            LogUtil.w(TAG, "scanForBestIp: timed out, cancelling scan")
            CloudflareScanner.cancel()
        }
        scanDeferred = null
        // Persisting the selection is the caller's job: the user may still
        // choose a different candidate, and only the confirmed one is cached.
        return result
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
        val savedUsable = saved != null && saved.isUsable(now, fingerprint)
        val storedRanges = QualifiedRangeStore.snapshot()

        // The decision table lives in ConnectPolicy so it is a test, not a set
        // of booleans scattered through the flow. The refresh flag is read once
        // here: it is a one-shot request, and reading it inside decide() would
        // make the later discovery condition untestable.
        val refresh = QsPrefs.consumeRangeRefresh()
        val decision = ConnectPolicy.decide(
            mode = ConnectMode.Automatic,
            guid = guid,
            savedEdgeUsable = savedUsable,
            hasQualifiedRanges = storedRanges.isNotEmpty(),
            refreshRequested = refresh,
        )

        if (decision == ConnectDecision.ReuseSavedEdge && saved != null) {
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

        // Stage 1 and Stage 2 are independent. A discovery run that is already
        // active is left running: starting the candidate scan is not a reason
        // to cancel it, and its results are persisted as they arrive.

        // The chosen ISP's qualified ranges are the canonical source. The
        // global store covers a connection made before an ISP was named, and a
        // user who configured ranges by hand keeps them as the last fallback.
        val isp = QsPrefs.lastIsp()
        val ispRanges = isp
            ?.let { IspManager.getProfile(context, it)?.goodCidrs }
            .orEmpty()
        val manual = ScanRangePlanner.dedupe(QsPrefs.loadRanges()).map { it.cidr }
        var ranges = ispRanges
            .ifEmpty { RangeSet.cidrs(storedRanges) }
            .ifEmpty { manual }

        // Stage 1 runs only when there is nothing to scan with, or when the
        // user explicitly asked for a refresh. A saved edge that failed its
        // health check does not restart discovery while the range database
        // still has entries.
        if (refresh || ranges.isEmpty()) {
            onState(AutoConnectState.DiscoveringRanges)
            val ispName = isp ?: DiscoveryScheduler.DEFAULT_ISP
            // A first connection must not wait for the whole dataset. The run
            // starts in the background and the flow takes the first range that
            // passes the real quality gate; the run then keeps measuring.
            CloudflareScanner.discoverRanges(
                context = context,
                guid = guid,
                ispName = ispName,
                background = true,
                force = refresh,
            ) { }
            val first = CloudflareScanner.awaitFirstQualifiedRange(FIRST_RANGE_TIMEOUT_MS)
            if (cancelled) return null
            ranges = if (first != null) {
                listOf(first.cidr)
            } else {
                // Nothing qualified in time: use what the run already stored
                // (or the manual ranges) rather than scanning unrelated data.
                RangeSet.cidrs(discoverRanges(context, guid, onState, force = false))
                    .ifEmpty { manual }
            }
            if (cancelled) return null
        }

        onState(AutoConnectState.ScanningCloudflareIps)
        val measured = ArrayList<ScanCandidate>()
        val winner = scanForBestIp(context, guid, onState, ranges) { measured.add(it) }
        val early = consumeEarlyPick()
        if (cancelled) return null

        // The ranker needs a reachable candidate. If it has none but the scan
        // still measured something that answered (a timeout keeps partial
        // results), the user chooses from those: a finished scan is not a
        // failed connection, and the flow stays in a valid waiting state.
        val suggestion = CandidateSelection.suggestion(winner?.ip, measured)
        if (!CandidateSelection.canContinue(winner?.ip, measured, early)) {
            LogUtil.w(TAG, "Scan finished without a single answering candidate")
            return null
        }

        // A stable order: score first, then latency, then address. The UI shows
        // this order and marks the first row as the suggestion.
        val ranked = measured
            .distinctBy { it.ip }
            .sortedWith(
                compareByDescending<ScanCandidate> { it.score }
                    .thenBy { it.pingMs ?: Long.MAX_VALUE }
                    .thenBy { it.ip },
            )

        val chosen = if (early != null) {
            // The user confirmed while the scan was running. That choice wins
            // over the ranker, and no later callback can replace it.
            ranked.firstOrNull { it.ip == early && it.answered }
        } else {
            onState(AutoConnectState.AwaitingIpChoice(ranked, suggestion))
            val chosenIp = awaitIpChoice()
            if (cancelled || chosenIp == null) return null
            ranked.firstOrNull { it.ip == chosenIp && it.answered }
        }
        if (chosen == null) return null

        QsPrefs.saveSelection(
            IpSelection(
                fingerprint = fingerprint,
                ip = chosen.ip,
                score = chosen.score,
                latencyMs = chosen.pingMs,
                jitterMs = chosen.jitterMs,
                downloadKBps = chosen.downloadKBps,
                uploadKBps = chosen.uploadKBps,
                confidence = chosen.confidence,
                validatedAt = System.currentTimeMillis(),
            )
        )
        QsPrefs.markScanned(System.currentTimeMillis())
        return chosen.ip
    }

    /**
     * Runs Stage 1 and merges what it qualified into the range database.
     *
     * A failed or empty run leaves the stored database untouched: a bad network
     * must not erase ranges that were qualified earlier.
     */
    private suspend fun discoverRanges(
        context: Context,
        guid: String,
        onState: (AutoConnectState) -> Unit,
        force: Boolean = false,
    ): List<QualifiedRange> {
        val deferred = CompletableDeferred<DiscoveryOutcome>()
        discoveryDeferred = deferred
        val isp = QsPrefs.lastIsp() ?: DiscoveryScheduler.DEFAULT_ISP
        CloudflareScanner.discoverRanges(
            context = context,
            guid = guid,
            ispName = isp,
            background = false,
            force = force,
            onProgress = { done, total, _, qualified ->
                onState(AutoConnectState.DiscoveryProgress(done, total, qualified))
            },
        ) { outcome -> deferred.complete(outcome) }

        val outcome = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { deferred.await() }
        discoveryDeferred = null
        if (outcome == null) {
            // The run is still measuring. It is not cancelled: Stage 2 can use
            // what this ISP already stored while discovery continues, and a
            // later screen shows the real phase and progress.
            LogUtil.w(TAG, "Discovery is still running; continuing with the stored ranges")
            return IspManager.getProfile(context, isp)?.toQualifiedRanges().orEmpty()
        }
        // The scanner persisted every qualified range as it found it, so this
        // is the same canonical set a later connection reads. Nothing to merge
        // here: a cancelled or empty run must not shrink what is stored.
        if (outcome.qualified.isNotEmpty()) {
            QualifiedRangeStore.add(outcome.qualified)
            LogUtil.i(TAG, "Discovery qualified " + outcome.qualified.size + " range(s)")
        }
        // Only this ISP's ranges: Stage 2 must not measure ranges that were
        // qualified for a different ISP.
        return outcome.qualified
    }

    /** Reads and clears the candidate the user confirmed mid-scan. */
    private fun consumeEarlyPick(): String? {
        val pick = earlyPick
        earlyPick = null
        return pick
    }

    /** What one edge application actually did. */
    internal data class EdgeApplyResult(
        /** Configurations that now hold the edge (written or already correct). */
        val applied: Int,
        /** Configurations whose write threw. */
        val failed: Int,
        /** True when the configuration the tunnel will use holds the edge. */
        val selectedApplied: Boolean,
    )

    /**
     * Writes [ip] to every eligible configuration the selected profile covers.
     *
     * A subscription config takes the whole subscription (so all three eligible
     * configs switch to the same edge, as required); a manual config takes only
     * itself. Nothing outside that scope is read, written or re-saved.
     *
     * @return what actually happened, including the count that failed.
     */
    private fun applyEdgeToScope(
        selectedGuid: String,
        selected: ProfileItem,
        ip: String,
    ): EdgeApplyResult {
        val scope = edgeScopeOf(selectedGuid, selected)
        val guids = when (scope) {
            is EdgeApplication.Scope.Single -> listOf(selectedGuid)
            is EdgeApplication.Scope.Subscription ->
                MmkvManager.decodeServerList(scope.subscriptionId).distinct()
        }
        val targets = guids
            // A probe is never a user configuration, even if one leaked into a
            // list from an older build.
            .filterNot { ProbeProfile.isProbe(it) }
            .mapNotNull { guid ->
                val profile = if (guid == selectedGuid) {
                    selected
                } else {
                    MmkvManager.decodeServerConfig(guid)
                }
                profile?.let { EdgeApplication.Target(guid, it) }
            }
        val inScope = EdgeApplication.targets(scope, targets, AutoConfigFilter::isEligible)
        if (inScope.isEmpty()) return EdgeApplyResult(0, 0, false)

        val changed = EdgeApplication.apply(inScope, ip).toHashSet()
        var applied = 0
        var failed = 0
        var selectedApplied = false
        inScope.forEach { target ->
            // Fill blanks only; an existing cipherSuites / finalMask /
            // fingerprint is user data and is never overwritten.
            val settingsChanged = fillBlankSettings(target.profile)
            val needsWrite = target.guid in changed || settingsChanged
            if (!needsWrite) {
                // Re-applying the same edge is a no-op, not a duplicate write.
                applied++
                if (target.guid == selectedGuid) selectedApplied = true
                return@forEach
            }
            try {
                MmkvManager.encodeServerConfig(target.guid, target.profile)
                applied++
                if (target.guid == selectedGuid) selectedApplied = true
            } catch (e: Exception) {
                failed++
                LogUtil.e(TAG, "applyEdgeToScope: failed to persist " + target.guid)
            }
        }
        LogUtil.i(
            TAG,
            "applyEdgeToScope: " + applied + " applied, " + failed + " failed, scope=" + scope,
        )
        return EdgeApplyResult(applied, failed, selectedApplied)
    }

    /**
     * The configurations [selectedGuid] may share an edge with.
     *
     * A subscription profile takes its whole subscription, so every eligible
     * sibling is updated and the chosen address is available whichever one the
     * user connects with later. A manual profile takes only itself.
     *
     * The profile's own subscriptionId is authoritative. When it is missing
     * (older storage, an import), the subscription lists are asked which one
     * owns the GUID, so the scope is not silently narrowed to one config.
     */
    private fun edgeScopeOf(
        selectedGuid: String,
        selected: ProfileItem,
    ): EdgeApplication.Scope {
        val declared = selected.subscriptionId
        if (declared.isNotBlank() && declared != AppConfig.DEFAULT_SUBSCRIPTION_ID) {
            return EdgeApplication.Scope.Subscription(declared)
        }
        val owner = MmkvManager.decodeSubscriptions()
            .map { it.guid }
            .firstOrNull { id ->
                id != AppConfig.DEFAULT_SUBSCRIPTION_ID &&
                    MmkvManager.decodeServerList(id).contains(selectedGuid)
            }
        return owner?.let { EdgeApplication.Scope.Subscription(it) }
            ?: EdgeApplication.Scope.Single(selectedGuid)
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
        discoveryDeferred?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(
                    DiscoveryOutcome(
                        qualified = emptyList(),
                        scanned = 0,
                        total = 0,
                        nextIndex = 0,
                        exhausted = false,
                        cancelled = true,
                    ),
                )
            }
        }
        discoveryDeferred = null
        // No question may survive its flow: a late click must find nothing to
        // answer, and an early pick from a finished flow must not be reused.
        configChoice = null
        ipChoice = null
        earlyPick = null
        pendingConfigPick = null
        pendingIpPick = null
        flowActive = false
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
