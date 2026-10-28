package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.qsn.data.QualifiedRangeStore
import com.v2ray.ang.ui.qsn.domain.CandidateMetrics
import com.v2ray.ang.ui.qsn.domain.CandidateRanker
import com.v2ray.ang.ui.qsn.domain.DiscoveryEngine
import com.v2ray.ang.ui.qsn.domain.DiscoveryPhase
import com.v2ray.ang.ui.qsn.domain.DiscoveryQuality
import com.v2ray.ang.ui.qsn.domain.DiscoveryRunSummary
import com.v2ray.ang.ui.qsn.domain.DiscoverySnapshot
import com.v2ray.ang.ui.qsn.domain.DiscoveryStatus
import com.v2ray.ang.ui.qsn.domain.DiscoveryStore
import com.v2ray.ang.ui.qsn.domain.QualifiedRange
import com.v2ray.ang.ui.qsn.domain.RangeMeasurement
import com.v2ray.ang.ui.qsn.domain.RangeOutcome
import com.v2ray.ang.ui.qsn.domain.RangeState
import com.v2ray.ang.ui.qsn.domain.Statistics
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class CandidateResult(
    val ip: String,
    /** Successful latency samples, in probe order. Empty means the edge never answered. */
    val latencySamples: List<Long> = emptyList(),
    val uploadKBps: Long = 0L,
    val downloadKBps: Long = 0L,
    /** Probes that answered with an error rather than a latency. */
    val failures: Int = 0,
    /** Probes that produced no answer at all (timeout). */
    val timeouts: Int = 0,
    /** True when the bounded traffic test really ran and produced numbers. */
    val hasTraffic: Boolean = false,
    /** Ranker score (0..100) at selection time; 0 when this result was not ranked. */
    val score: Double = 0.0,
    /** Share of the measurement dimensions that were available (0..100). */
    val confidence: Int = 0,
    /**
     * True when the probe could not even build the outbound config.
     *
     * A config/native failure is an uncertain measurement, not proof that the
     * address is dead, so discovery retries it instead of recording a failure.
     */
    val configError: Boolean = false,
) {
    /** Median latency, or -1 when the edge never answered. */
    val latencyMs: Long get() = Statistics.median(latencySamples) ?: -1L

    /** Median absolute deviation of the samples. */
    val jitterMs: Long get() = Statistics.medianAbsoluteDeviation(latencySamples) ?: 0L

    val isSuccess: Boolean get() = latencySamples.isNotEmpty()
}

/** Result of one Stage 1 discovery run. */
data class DiscoveryOutcome(
    /** Every range this ISP currently has a Good result for. */
    val qualified: List<QualifiedRange>,
    /** Ranges with a final result, including results restored from storage. */
    val scanned: Int,
    /** Total ranges in the bundled dataset. */
    val total: Int,
    /** Kept for compatibility; discovery resumes from per-range results now. */
    val nextIndex: Int,
    /** True when every range of the dataset has a final result. */
    val exhausted: Boolean,
    /** True when cancel() stopped the run. */
    val cancelled: Boolean,
    val ispName: String = "",
    val good: Int = qualified.size,
    val failed: Int = 0,
    val errors: Int = 0,
    val phase: DiscoveryPhase = DiscoveryPhase.Idle,
)

interface ScanCallback {
    fun onProgress(result: CandidateResult, done: Int, total: Int)
    fun onFinish(best: CandidateResult?)
    fun onCancelled()
}

object CloudflareScanner {
    private const val TAG = "CloudflareScanner"
    private const val TEST_URL = "https://cp.cloudflare.com/cdn-cgi/trace"
    private const val DEFAULT_CONCURRENCY = 4
    private const val IPS_PER_CIDR = 2
    private const val MAX_CANDIDATES = 30
    private const val LATENCY_SAMPLES = 3

    /** Run ids keep a discovery probe and a Stage 2 probe for the same IP apart. */
    private const val RUN_DISCOVERY = "d"
    private const val RUN_SCAN = "s"

    private val PRODUCTION_FINALMASK = buildString {
        append("""{"tcp":[""")
        append("""{"type":"fragment","settings":{"packets":"tlshello","lengths":["5","94","1"],"delays":["0"],"maxSplit":"0"}},""")
        append("""{"type":"fragment","settings":{"packets":"1-1","lengths":["109","1"],"delays":["1"],"maxSplit":"355"}}""")
        append("""]}""")
    }

    /** Public alias for [PRODUCTION_FINALMASK] so external callers (e.g. the
     *  auto-connect orchestrator) can fill blank finalMask fields without
     *  duplicating the value. */
    val PRODUCTION_FINALMASK_VALUE: String get() = PRODUCTION_FINALMASK

    private const val PRODUCTION_CIPHERSUITES =
        "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256:" +
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:" +
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:" +
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:" +
        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:" +
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"

    /** Public alias for [PRODUCTION_CIPHERSUITES] so external callers can fill
     *  blank cipherSuites fields without duplicating the value. */
    const val PRODUCTION_CIPHERSUITES_VALUE: String = PRODUCTION_CIPHERSUITES

    // ---------- Stage 2: candidate scan ----------

    private var scanJob = SupervisorJob()
    private var scanScope = CoroutineScope(scanJob + Dispatchers.IO)

    // ---------- Stage 1: range discovery ----------
    //
    // Discovery owns its own job and scope. Stage 2 must never be able to cancel
    // it, and a cancelled screen must never be able to stop it: the only thing
    // that stops a discovery run is [stopDiscovery] or an unrecoverable error.

    private val discoveryLock = Any()

    /** Volatile: the UI and the service read it without taking [discoveryLock]. */
    @Volatile private var discoveryJob = SupervisorJob()
    private var discoveryScope = CoroutineScope(discoveryJob + Dispatchers.IO)

    @Volatile private var discoveryIsp: String? = null
    @Volatile private var discoveryBackground: Boolean = false
    @Volatile private var discoveryContext: Context? = null

    /** Ranges currently being measured; transient, never persisted. */
    private val runningCidrs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Live Stage 1 state, shared by the screen and the connection flow. */
    private val _discovery = MutableStateFlow(DiscoveryStatus())
    val discovery: StateFlow<DiscoveryStatus> = _discovery.asStateFlow()

    /**
     * Last line of defence for both scopes.
     *
     * The scan calls native code in the UI process. A native failure arrives as
     * an [Error] (UnsatisfiedLinkError, ExceptionInInitializerError, OOM), which
     * no catch(Exception) can contain; without this handler it would reach the
     * default handler and kill the app with no message. The handler turns it
     * into a logged, reported failure instead.
     */
    private val scanExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LogUtil.e(TAG, "Scan scope failed: " + throwable.javaClass.name, throwable)
    }

    // ---------- Candidate generation ----------

    private fun generateCandidates(context: Context): List<String> {
        val candidates = mutableListOf<String>()
        try {
            val lines = context.assets.open("cf_ranges_v4.txt")
                .bufferedReader()
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .shuffled()

            for (cidr in lines) {
                if (candidates.size >= MAX_CANDIDATES) break
                try {
                    val parts = cidr.trim().split("/")
                    if (parts.size != 2) continue
                    val ips = randomIpsFromCidr(parts[0], parts[1].toInt(), IPS_PER_CIDR)
                    candidates.addAll(ips)
                } catch (e: Exception) {
                    LogUtil.d(TAG, "Skip CIDR: " + cidr)
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to load cf_ranges_v4.txt: " + e.message)
            candidates.addAll(listOf("104.16.0.1", "104.17.0.1", "172.64.0.1", "162.159.0.1"))
        }
        return candidates.take(MAX_CANDIDATES)
    }

    private fun randomIpsFromCidr(baseIp: String, prefix: Int, count: Int): List<String> {
        val result = mutableListOf<String>()
        try {
            val base = ipToLong(baseIp)
            val hostBits = 32 - prefix
            if (hostBits <= 0) {
                result.add(baseIp)
                return result
            }
            val maxHosts = (1L shl hostBits) - 2
            if (maxHosts <= 0) {
                result.add(baseIp)
                return result
            }
            val seen = mutableSetOf<Long>()
            repeat(count * 3) {
                if (result.size >= count) return result
                val offset = (Random.nextLong() and Long.MAX_VALUE) % maxHosts + 1
                if (seen.add(offset)) result.add(longToIp(base + offset))
            }
        } catch (e: Exception) {
            LogUtil.d(TAG, "randomIpsFromCidr error: " + e.message)
        }
        return result
    }

    private fun ipToLong(ip: String): Long {
        val p = ip.split(".")
        return (p[0].toLong() shl 24) or (p[1].toLong() shl 16) or (p[2].toLong() shl 8) or p[3].toLong()
    }

    private fun longToIp(n: Long) =
        "" + ((n shr 24) and 0xFF) + "." + ((n shr 16) and 0xFF) + "." +
            ((n shr 8) and 0xFF) + "." + (n and 0xFF)

    // ---------- Stage 2 entry points ----------

    fun scan(
        context: Context,
        guid: String,
        candidates: List<String> = emptyList(),
        concurrency: Int = DEFAULT_CONCURRENCY,
        callback: ScanCallback,
    ) {
        // Stage 2 only. Discovery has its own job and is deliberately untouched.
        cancelScan()
        // Fail fast with a clear reason when the core library cannot be loaded
        // in this process: probing without it would only produce 30 identical
        // failures, and a failed native load must never take the app down.
        if (!CoreNativeManager.isNativeAvailable()) {
            LogUtil.e(TAG, "Scan aborted: the core library is not available in this process")
            callback.onFinish(null)
            return
        }
        scanJob = SupervisorJob()
        scanScope = CoroutineScope(scanJob + Dispatchers.IO + scanExceptionHandler)
        scanScope.launch {
            try {
                purgeIfNoOtherRun()
                val actual = if (candidates.isEmpty()) generateCandidates(context) else candidates
                LogUtil.i(TAG, "Scan started: " + actual.size + " candidates")
                runScan(context, guid, actual, concurrency, callback)
            } catch (e: CancellationException) {
                callback.onCancelled()
            } catch (t: Throwable) {
                LogUtil.e(TAG, "Scan failed: " + t.javaClass.name, t)
                callback.onFinish(null)
            }
        }
    }

    /** Cancels Stage 2 only. A running discovery is never affected. */
    fun cancel() {
        cancelScan()
    }

    private fun cancelScan() {
        scanJob.cancel()
    }

    /** True while a Stage 2 candidate scan is active in this process. */
    val isBusy: Boolean get() = scanJob.isActive

    /** True while Stage 1 is active in this process. */
    val isDiscoveryActive: Boolean get() = discoveryJob.isActive

    /**
     * Stage 2 over an explicit list of ranges.
     *
     * The measurement itself is [runScan]: same latency samples, same bounded
     * traffic test, same ranker as every other scan. Only the candidate source
     * changes, which is what keeps Stage 2 identical whether the ranges came
     * from discovery or from the user.
     */
    fun scanRanges(
        context: Context,
        guid: String,
        cidrs: List<String>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        callback: ScanCallback,
    ) {
        if (cidrs.isEmpty()) {
            // Stage 2 is defined over stored ranges. An empty list means the
            // caller has nothing qualified to scan; it must not silently fall
            // back to the bundled dataset, which would look like a successful
            // ISP scan while measuring unrelated ranges.
            LogUtil.w(TAG, "scanRanges called without ranges; nothing to scan")
            callback.onFinish(null)
            return
        }
        cancelScan()
        if (!CoreNativeManager.isNativeAvailable()) {
            LogUtil.e(TAG, "Range scan aborted: the core library is not available")
            callback.onFinish(null)
            return
        }
        scanJob = SupervisorJob()
        scanScope = CoroutineScope(scanJob + Dispatchers.IO + scanExceptionHandler)
        scanScope.launch {
            try {
                purgeIfNoOtherRun()
                val candidates = candidatesFromRanges(cidrs)
                LogUtil.i(TAG, "Stage 2: " + candidates.size + " candidates from " + cidrs.size + " ranges")
                runScan(context, guid, candidates, concurrency, callback)
            } catch (e: CancellationException) {
                callback.onCancelled()
            } catch (t: Throwable) {
                LogUtil.e(TAG, "Range scan failed: " + t.javaClass.name, t)
                callback.onFinish(null)
            }
        }
    }

    /** Samples [IPS_PER_CIDR] addresses from every range, bounded by [MAX_CANDIDATES]. */
    private fun candidatesFromRanges(cidrs: List<String>): List<String> {
        val candidates = LinkedHashSet<String>()
        cidrs.shuffled().forEach { cidr ->
            if (candidates.size >= MAX_CANDIDATES) return@forEach
            val parts = cidr.trim().split("/")
            if (parts.size != 2) return@forEach
            val prefix = parts[1].toIntOrNull() ?: return@forEach
            candidates.addAll(randomIpsFromCidr(parts[0], prefix, IPS_PER_CIDR))
        }
        return candidates.take(MAX_CANDIDATES)
    }

    /**
     * Stage 2 for one ISP.
     *
     * The only source of ranges is the stored Good set of that ISP. When it is
     * empty the caller must run discovery first; scanning the bundled dataset
     * here would hide an incomplete discovery behind unrelated measurements.
     */
    fun scanForIsp(
        context: Context,
        guid: String,
        ispName: String,
        concurrency: Int = DEFAULT_CONCURRENCY,
        callback: ScanCallback,
    ) {
        val profile = IspManager.getProfile(context, ispName)
        val ranges = profile?.goodCidrs.orEmpty()
        if (ranges.isEmpty()) {
            LogUtil.w(TAG, "scanForIsp: no stored ranges for ISP " + ispName)
            callback.onFinish(null)
            return
        }
        scanRanges(context, guid, ranges, concurrency, callback)
    }

    // ---------- Stage 1 entry points ----------

    /**
     * Starts (or resumes) discovery for one ISP.
     *
     * Single-flight: while a run is active this returns false and the caller
     * observes [discovery] instead of starting a second job. A completed profile
     * is not re-measured unless [force] is true, which is what makes a later
     * connection use the saved ranges instead of repeating the whole dataset.
     *
     * The run measures **every** range that has no final result. It never stops
     * after an arbitrary number of good ranges, and a failed range only records
     * its own result.
     *
     * @return true when a new job was started.
     */
    fun discoverRanges(
        context: Context,
        guid: String,
        ispName: String,
        background: Boolean = false,
        force: Boolean = false,
        onProgress: (done: Int, total: Int, cidr: String, good: Boolean) -> Unit = { _, _, _, _ -> },
        onFinish: (DiscoveryOutcome) -> Unit,
    ): Boolean {
        val isp = ispName.trim().ifEmpty { IspManager.DEFAULT_ISP }
        synchronized(discoveryLock) {
            if (discoveryJob.isActive) {
                LogUtil.i(TAG, "Discovery already running for " + discoveryIsp + "; not starting another")
                onFinish(outcomeFromProfile(IspManager.getProfile(context, isp), cancelled = false))
                return false
            }

            val base = MmkvManager.decodeServerConfig(guid)
            if (base == null) {
                LogUtil.e(TAG, "Discovery aborted: base configuration not found")
                IspManager.setPhase(context, isp, DiscoveryPhase.Failed, 0, 0)
                publish(IspManager.getProfile(context, isp), null, background)
                onFinish(outcomeFromProfile(IspManager.getProfile(context, isp), cancelled = false))
                return false
            }

            val existing = IspManager.getProfile(context, isp)
            val dataset = readRangeAsset(context)
            val datasetChanged = existing != null &&
                existing.dataset.isNotEmpty() &&
                existing.dataset != dataset
            if (force) {
                // Explicit restart: forget the results, keep manual ranges.
                IspManager.resetDiscovery(context, isp)
            } else if (existing != null && existing.isComplete && !datasetChanged) {
                LogUtil.i(TAG, "Discovery already complete for " + isp + "; reusing stored ranges")
                publish(existing, null, background)
                onFinish(outcomeFromProfile(existing, cancelled = false))
                return false
            }

            discoveryIsp = isp
            discoveryBackground = background
            discoveryContext = context.applicationContext
            runningCidrs.clear()
            discoveryJob = SupervisorJob()
            discoveryScope = CoroutineScope(discoveryJob + Dispatchers.IO + scanExceptionHandler)
            publish(IspManager.getProfile(context, isp), null, background)

            val app = context.applicationContext
            discoveryScope.launch {
                runDiscovery(app, guid, isp, base, background, onProgress, onFinish)
            }
            return true
        }
    }

    /**
     * Stops the active discovery run.
     *
     * Completed results are already persisted, so this is a pause, not a loss.
     * It never touches Stage 2.
     */
    fun stopDiscovery() {
        // Taken under the same lock as a start: a stop that raced with a new
        // run would otherwise cancel the job the user just started.
        synchronized(discoveryLock) {
            if (!discoveryJob.isActive) return
            val ctx = discoveryContext
            val isp = discoveryIsp
            discoveryJob.cancel()
            if (ctx != null && isp != null) {
                val profile = IspManager.getProfile(ctx, isp)
                IspManager.setPhase(
                    ctx = ctx,
                    ispName = isp,
                    phase = DiscoveryPhase.Paused,
                    total = profile?.total ?: 0,
                    scanned = profile?.scanned ?: 0,
                )
                publish(IspManager.getProfile(ctx, isp), null, discoveryBackground)
            }
        }
    }

    /**
     * Reconciles storage with this process on screen entry.
     *
     * A run that was active when the process died is marked [DiscoveryPhase.Interrupted]
     * so the UI can offer Resume. A live run is left alone. When no run is active,
     * the last persisted state of [ispName] is published, so a recreated screen
     * shows real results instead of an empty Idle state.
     */
    fun reconcileDiscovery(context: Context, ispName: String) {
        val isp = ispName.trim().ifEmpty { IspManager.DEFAULT_ISP }
        if (discoveryJob.isActive) return
        IspManager.markInterrupted(context, isp)
        var profile = IspManager.getProfile(context, isp)
        if (profile != null && profile.results.isEmpty() && profile.goodCidrs.isNotEmpty()) {
            // A profile migrated from the old SharedPreferences file has only
            // goodCidrs. Seed those as Good results: they were qualified by the
            // old scanner, they must reach the canonical store, and discovery
            // must not measure them again on the first resume.
            val seeded = profile.toQualifiedRanges()
            QualifiedRangeStore.add(seeded)
            seeded.forEach { range ->
                IspManager.recordOutcome(
                    ctx = context,
                    ispName = isp,
                    outcome = RangeOutcome(
                        cidr = range.cidr,
                        state = RangeState.Good,
                        uploadKBps = range.uploadKBps,
                        latencyMs = range.latencyMs,
                        attempts = 1,
                        checkedAt = range.validatedAt,
                    ),
                )
            }
            profile = IspManager.getProfile(context, isp)
        }
        if (profile != null) publish(profile, null, false)
    }

    /** Legacy entry point used by the old foreground service. */
    fun discoverGoodCidrs(
        context: Context,
        ispName: String,
        guid: String,
        onProgress: (done: Int, total: Int, cidr: String, responded: Boolean) -> Unit,
        onFinish: (goodCidrs: List<String>) -> Unit,
    ) {
        discoverRanges(
            context = context,
            guid = guid,
            ispName = ispName,
            background = false,
            onProgress = onProgress,
            onFinish = { outcome -> onFinish(outcome.qualified.map { it.cidr }) },
        )
    }

    private suspend fun runDiscovery(
        context: Context,
        guid: String,
        isp: String,
        base: ProfileItem,
        background: Boolean,
        onProgress: (done: Int, total: Int, cidr: String, good: Boolean) -> Unit,
        onFinish: (DiscoveryOutcome) -> Unit,
    ) {
        val dataset = readRangeAsset(context)
        if (dataset.isEmpty()) {
            LogUtil.e(TAG, "Discovery aborted: the bundled range dataset is empty")
            IspManager.setPhase(context, isp, DiscoveryPhase.Failed, 0, 0)
            publish(IspManager.getProfile(context, isp), null, background)
            onFinish(outcomeFromProfile(IspManager.getProfile(context, isp), cancelled = false))
            return
        }

        purgeIfScanIdle()
        IspManager.setDataset(context, isp, dataset)
        val store = AndroidDiscoveryStore(context, isp)
        val engine = DiscoveryEngine(
            store = store,
            probe = { cidr -> probeRange(context, guid, base, cidr) },
        )
        try {
            val summary = engine.run(
                isp = isp,
                dataset = dataset,
                onStart = { cidr ->
                    runningCidrs.add(cidr)
                    publish(IspManager.getProfile(context, isp), cidr, background)
                },
            ) { outcome ->
                runningCidrs.remove(outcome.cidr)
                publish(IspManager.getProfile(context, isp), outcome.cidr, background)
                withContext(Dispatchers.Main) {
                    onProgress(
                        IspManager.getProfile(context, isp)?.scanned ?: 0,
                        dataset.size,
                        outcome.cidr,
                        outcome.state == RangeState.Good,
                    )
                }
            }
            runningCidrs.clear()
            publish(IspManager.getProfile(context, isp), null, background)
            onFinish(outcomeFromSummary(summary, IspManager.getProfile(context, isp)))
        } catch (e: CancellationException) {
            runningCidrs.clear()
            val profile = IspManager.getProfile(context, isp)
            IspManager.setPhase(
                ctx = context,
                ispName = isp,
                phase = DiscoveryPhase.Paused,
                total = profile?.total ?: dataset.size,
                scanned = profile?.scanned ?: 0,
            )
            publish(IspManager.getProfile(context, isp), null, background)
            onFinish(outcomeFromProfile(IspManager.getProfile(context, isp), cancelled = true))
            throw e
        } catch (t: Throwable) {
            LogUtil.e(TAG, "Discovery failed: " + t.javaClass.name, t)
            runningCidrs.clear()
            val profile = IspManager.getProfile(context, isp)
            IspManager.setPhase(
                ctx = context,
                ispName = isp,
                phase = DiscoveryPhase.Failed,
                total = profile?.total ?: dataset.size,
                scanned = profile?.scanned ?: 0,
            )
            publish(IspManager.getProfile(context, isp), null, background)
            onFinish(outcomeFromProfile(IspManager.getProfile(context, isp), cancelled = false))
        }
    }

    /** Measures one range through the real configuration and aggregates its sample. */
    private suspend fun probeRange(
        context: Context,
        guid: String,
        base: ProfileItem,
        cidr: String,
    ): RangeMeasurement {
        val parts = cidr.trim().split("/")
        if (parts.size != 2) return RangeMeasurement(reachable = false, error = true)
        val prefix = parts[1].toIntOrNull() ?: return RangeMeasurement(reachable = false, error = true)
        val ips = randomIpsFromCidr(parts[0], prefix, DiscoveryQuality.IPS_PER_RANGE)
        if (ips.isEmpty()) return RangeMeasurement(reachable = false, error = true)

        var reachable = false
        var allConfigErrors = true
        var bestUpload: Long? = null
        var bestDownload: Long? = null
        var bestLatency: Long? = null

        for (ip in ips) {
            val result = testCandidate(context, guid, base, ip, RUN_DISCOVERY)
            if (result.isSuccess) {
                reachable = true
                allConfigErrors = false
                if (result.latencyMs >= 0) {
                    bestLatency = bestLatency?.let { minOf(it, result.latencyMs) } ?: result.latencyMs
                }
                if (result.hasTraffic) {
                    bestUpload = bestUpload?.let { maxOf(it, result.uploadKBps) } ?: result.uploadKBps
                    bestDownload = bestDownload?.let { maxOf(it, result.downloadKBps) } ?: result.downloadKBps
                }
            } else if (!result.configError) {
                allConfigErrors = false
            }
        }

        if (!reachable && allConfigErrors) {
            return RangeMeasurement(reachable = false, error = true)
        }
        return RangeMeasurement(
            reachable = reachable,
            uploadKBps = bestUpload,
            downloadKBps = bestDownload,
            latencyMs = bestLatency,
        )
    }

    private fun readRangeAsset(context: Context): List<String> = try {
        context.assets.open("cf_ranges_v4.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }
    } catch (e: Exception) {
        LogUtil.e(TAG, "Failed to load cf_ranges_v4.txt: " + e.javaClass.simpleName)
        emptyList()
    }

    /**
     * Store adapter for the pure engine.
     *
     * Every final outcome is written immediately, and a Good range is also
     * appended to the canonical candidate store the connection flow reads. The
     * in-memory snapshot avoids re-parsing the whole JSON file for every row
     * while keeping storage as the source of truth after a restart.
     */
    private class AndroidDiscoveryStore(
        private val context: Context,
        private val isp: String,
    ) : DiscoveryStore {

        override fun snapshot(isp: String): DiscoverySnapshot =
            IspManager.getProfile(context, isp)?.toSnapshot() ?: DiscoverySnapshot(isp)

        override fun record(isp: String, outcome: RangeOutcome) {
            IspManager.recordOutcome(context, isp, outcome)
            if (outcome.state == RangeState.Good) {
                QualifiedRangeStore.add(listOf(outcome.toQualifiedRange()))
            }
        }

        override fun setPhase(isp: String, phase: DiscoveryPhase, total: Int, scanned: Int) {
            IspManager.setPhase(context, isp, phase, total, scanned)
        }
    }

    private fun publish(profile: IspProfile?, lastCidr: String?, background: Boolean) {
        val snapshot = profile?.toSnapshot() ?: DiscoverySnapshot("")
        val ordered = if (snapshot.dataset.isNotEmpty()) {
            snapshot.dataset
        } else {
            snapshot.outcomes.keys.toList()
        }
        val rows = ordered.map { cidr ->
            snapshot.outcomes[cidr]
                ?: if (runningCidrs.contains(cidr)) {
                    RangeOutcome(cidr = cidr, state = RangeState.Running)
                } else {
                    RangeOutcome(cidr = cidr, state = RangeState.Pending)
                }
        }
        _discovery.update {
            DiscoveryStatus(
                phase = snapshot.phase,
                background = background,
                ispName = snapshot.isp,
                done = snapshot.done,
                total = snapshot.total,
                qualified = snapshot.good,
                failed = snapshot.failed,
                errors = snapshot.errors,
                lastCidr = lastCidr ?: it.lastCidr,
                rows = rows.sortedBy { row -> row.cidr },
            )
        }
    }

    private fun outcomeFromProfile(profile: IspProfile?, cancelled: Boolean): DiscoveryOutcome {
        if (profile == null) {
            return DiscoveryOutcome(
                qualified = emptyList(),
                scanned = 0,
                total = 0,
                nextIndex = 0,
                exhausted = false,
                cancelled = cancelled,
            )
        }
        return DiscoveryOutcome(
            qualified = profile.toQualifiedRanges(),
            scanned = profile.scanned,
            total = profile.total,
            nextIndex = 0,
            exhausted = profile.isComplete,
            cancelled = cancelled,
            ispName = profile.name,
            good = profile.goodRanges.size,
            failed = profile.failedCount,
            errors = profile.errorCount,
            phase = profile.phase,
        )
    }

    private fun outcomeFromSummary(summary: DiscoveryRunSummary, profile: IspProfile?): DiscoveryOutcome =
        DiscoveryOutcome(
            qualified = profile?.toQualifiedRanges().orEmpty(),
            scanned = summary.scanned,
            total = summary.total,
            nextIndex = 0,
            exhausted = summary.completed,
            cancelled = false,
            ispName = summary.isp,
            good = summary.good,
            failed = summary.failed,
            errors = summary.errors,
            phase = profile?.phase ?: DiscoveryPhase.Idle,
        )

    // ---------- Shared candidate measurement ----------

    private suspend fun runScan(
        context: Context,
        guid: String,
        candidates: List<String>,
        concurrency: Int,
        callback: ScanCallback,
    ) {
        val baseProfile = MmkvManager.decodeServerConfig(guid) ?: run {
            callback.onFinish(null)
            return
        }
        val total = candidates.size
        var done = 0
        val results = mutableListOf<CandidateResult>()
        val semaphore = Semaphore(concurrency)
        val lock = Any()
        val jobs = candidates.map { ip ->
            scanScope.launch {
                semaphore.withPermit {
                    val result = testCandidate(context, guid, baseProfile, ip, RUN_SCAN)
                    synchronized(lock) {
                        done++
                        results.add(result)
                    }
                    callback.onProgress(result, done, total)
                }
            }
        }
        try {
            joinAll(*jobs.toTypedArray())
            val metrics = results.map { result ->
                CandidateMetrics(
                    ip = result.ip,
                    reachable = result.isSuccess,
                    latencySamples = result.latencySamples,
                    downloadKBps = if (result.hasTraffic) result.downloadKBps else null,
                    uploadKBps = if (result.hasTraffic) result.uploadKBps else null,
                    successes = result.latencySamples.size,
                    failures = result.failures,
                    timeouts = result.timeouts,
                )
            }
            val scored = CandidateRanker.best(metrics)
            val best = scored?.let { winner ->
                results.firstOrNull { it.ip == winner.ip }?.copy(
                    score = winner.score,
                    confidence = winner.confidence,
                )
            }
            LogUtil.i(
                TAG,
                "Done. Candidates=" + metrics.size + " reachable=" + metrics.count { it.reachable } +
                    " best=" + best?.ip + " score=" + (scored?.score?.toInt() ?: -1) +
                    " conf=" + (scored?.confidence ?: -1) +
                    " p50=" + (best?.latencyMs ?: -1) + "ms" +
                    " jitter=" + (best?.jitterMs ?: -1) + "ms" +
                    " down=" + (best?.downloadKBps ?: -1) + "KB/s" +
                    " up=" + (best?.uploadKBps ?: -1) + "KB/s",
            )
            callback.onFinish(best)
        } catch (_: CancellationException) {
            callback.onCancelled()
        } catch (t: Throwable) {
            LogUtil.e(TAG, "Scan error: " + t.javaClass.name, t)
            callback.onFinish(null)
        }
    }

    private suspend fun testCandidate(
        context: Context,
        baseGuid: String,
        base: ProfileItem,
        ip: String,
        runId: String,
    ): CandidateResult = withContext(Dispatchers.IO) {
        val tempGuid = ProbeProfile.guidFor(baseGuid, ip, runId)
        return@withContext try {
            // Keep scanner validation identical to the configuration that
            // applyBestIp() writes to the real production profile.
            // qsEdgeIp must be cleared: the temporary profile dials the
            // candidate directly, and a stale edge from a previous run would
            // silently be substituted for it in the outbound builder.
            val temp = base.copy(
                server = ip,
                qsEdgeIp = null,
                // Probe profiles live in their own index, so they can never be
                // listed as a user configuration while the scan runs.
                subscriptionId = ProbeProfile.SUBSCRIPTION_ID,
            ).apply {
                fingerPrint = "unsafe"
                if (finalMask.isNullOrBlank()) finalMask = PRODUCTION_FINALMASK
                if (cipherSuites.isNullOrBlank()) cipherSuites = PRODUCTION_CIPHERSUITES
            }
            MmkvManager.encodeServerConfig(tempGuid, temp)
            // encodeServerConfig selects the profile when nothing is selected
            // yet. A throw-away probe must never become the user's selection,
            // so put the real config back before measuring.
            if (MmkvManager.getSelectServer() == tempGuid) {
                MmkvManager.setSelectServer(baseGuid)
            }
            val cfg = CoreConfigManager.getV2rayConfig4Speedtest(context, tempGuid)
            if (!cfg.status) {
                LogUtil.d(TAG, ip + " -> FAIL (config build)")
                return@withContext CandidateResult(ip, failures = 1, configError = true)
            }

            // Repeated probes: a single sample cannot express jitter, and one
            // lucky number must not decide the winner.
            val samples = ArrayList<Long>(LATENCY_SAMPLES)
            var failures = 0
            repeat(LATENCY_SAMPLES) {
                val latency = CoreNativeManager.measureOutboundDelay(cfg.content, TEST_URL)
                if (latency >= 0) samples.add(latency) else failures++
            }
            if (samples.isEmpty()) {
                LogUtil.d(TAG, ip + " -> FAIL (no latency sample)")
                return@withContext CandidateResult(ip, failures = failures, timeouts = failures)
            }

            // Bounded 1 MiB up + 1 MiB down through the tested config. A
            // failure here leaves throughput unknown (null for the ranker); it
            // must not invalidate an edge that clearly answered.
            val traffic = try {
                com.v2ray.ang.service.RealTrafficSpeedTest.run(cfg.content)
            } catch (e: Exception) {
                null
            }
            val uploadKBps = (traffic?.uploadBytesPerSecond ?: 0L) / 1024
            val downloadKBps = (traffic?.downloadBytesPerSecond ?: 0L) / 1024
            LogUtil.i(
                TAG,
                ip + " -> p50=" + (Statistics.median(samples) ?: -1) + "ms jitter=" +
                    Statistics.medianAbsoluteDeviation(samples) + "ms dn=" + downloadKBps +
                    "KB/s up=" + uploadKBps + "KB/s traffic=" + (traffic != null),
            )
            CandidateResult(
                ip = ip,
                latencySamples = samples,
                uploadKBps = uploadKBps,
                downloadKBps = downloadKBps,
                failures = failures,
                timeouts = 0,
                hasTraffic = traffic != null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // The probe runs native code in this process. An Error here used to
            // escape every catch(Exception) and kill the app during a scan.
            LogUtil.e(TAG, ip + " -> probe failed: " + t.javaClass.name)
            CandidateResult(ip, failures = 1, configError = true)
        } finally {
            try {
                MmkvManager.removeServer(tempGuid)
            } catch (_: Exception) {
            }
        }
    }

    // ---------- Profile helpers ----------

    fun applyBestIp(guid: String, bestIp: String): Boolean {
        val profile = MmkvManager.decodeServerConfig(guid) ?: run {
            LogUtil.e(TAG, "applyBestIp: not found guid")
            return false
        }
        // Quiet Storm NG: the edge is stored separately. Overwriting
        // [ProfileItem.server] would destroy the origin address, which the
        // Connection Details screen has to show and a subscription refresh
        // cannot restore for a manual config.
        profile.qsEdgeIp = bestIp
        // fingerPrint: only fill if blank. CoreOutboundBuilder.populateTlsSettings
        // already hardcodes fingerprint="unsafe" for all TLS configs at build
        // time, so the profile value is not load-bearing for the outbound
        // config. We do not overwrite a non-blank value to respect user intent.
        if (profile.fingerPrint.isNullOrBlank()) profile.fingerPrint = "unsafe"
        if (profile.finalMask.isNullOrBlank()) profile.finalMask = PRODUCTION_FINALMASK
        if (profile.cipherSuites.isNullOrBlank()) profile.cipherSuites = PRODUCTION_CIPHERSUITES
        MmkvManager.encodeServerConfig(guid, profile)
        LogUtil.i(TAG, "applyBestIp: applied best IP to profile")
        return true
    }

    /**
     * Deletes temp profiles left behind by a run that was killed before its
     * per-candidate cleanup could run (process death, OOM kill).
     *
     * Never called while another run is active: both stages share the probe
     * index, and purging it would delete the other stage's live probes.
     */
    fun purgeStaleTempProfiles() {
        var removed = 0
        val probeIndex = MmkvManager.decodeServerList(ProbeProfile.SUBSCRIPTION_ID)
        if (probeIndex.isNotEmpty()) {
            MmkvManager.removeServers(probeIndex, ProbeProfile.SUBSCRIPTION_ID)
            removed += probeIndex.size
        }
        // Migration: earlier builds wrote probes into the user's own
        // subscription, so any leftover with the prefix is removed there too.
        val subIds = LinkedHashSet<String>()
        subIds.addAll(MmkvManager.decodeSubsList())
        subIds.add(AppConfig.DEFAULT_SUBSCRIPTION_ID)
        subIds.forEach { subId ->
            val leftovers = MmkvManager.decodeServerList(subId).filter { ProbeProfile.isProbe(it) }
            if (leftovers.isNotEmpty()) {
                MmkvManager.removeServers(leftovers, subId)
                removed += leftovers.size
            }
        }
        if (removed > 0) {
            LogUtil.i(TAG, "purgeStaleTempProfiles: removed " + removed + " orphaned temp profile(s)")
        }
    }

    private fun purgeIfNoOtherRun() {
        if (scanJob.isActive || discoveryJob.isActive) return
        purgeStaleTempProfiles()
    }

    /** Purges orphans at discovery start unless a Stage 2 run is using them. */
    private fun purgeIfScanIdle() {
        if (scanJob.isActive) return
        purgeStaleTempProfiles()
    }
}

/**
 * Identity rules for the throw-away profiles a scan measures through.
 *
 * A probe must never be mistakable for a user configuration, so both halves of
 * its identity are explicit and testable:
 *
 *  - the **subscription id** is a private one that is never registered in the
 *    subscription list, which is what keeps a probe out of
 *    [MmkvManager.decodeAllServerList] and therefore out of every screen;
 *  - the **GUID** keeps the base configuration's guid inside it but always
 *    carries a prefix, so a leftover can be recognised and purged.
 *
 * The base GUID is preserved inside the probe GUID only for diagnostics; the
 * probe is a copy, and the real profile is never rewritten by a measurement.
 */
internal object ProbeProfile {

    /** Private index that owns every probe profile. */
    const val SUBSCRIPTION_ID: String = "qsn-probe"

    /** Prefix that marks a GUID as a probe. */
    const val GUID_PREFIX: String = "cfscanner-"

    /**
     * Stable probe GUID for one address measured against one base profile.
     *
     * [runId] separates a Stage 1 probe from a Stage 2 probe of the same
     * address: both stages may run at the same time, and they must not share a
     * temporary profile.
     */
    fun guidFor(baseGuid: String, ip: String, runId: String = ""): String {
        val prefix = if (runId.isBlank()) "" else runId + "-"
        return GUID_PREFIX + prefix + baseGuid + "-" + ip.replace('.', '-').replace(':', '-')
    }

    /** True when [guid] belongs to a probe, including leftovers from old builds. */
    fun isProbe(guid: String): Boolean = guid.startsWith(GUID_PREFIX)

    /** True when a profile stored under [subscriptionId] is isolated from user lists. */
    fun isIsolated(subscriptionId: String?): Boolean = subscriptionId == SUBSCRIPTION_ID
}

