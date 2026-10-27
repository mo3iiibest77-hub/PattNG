package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.qsn.domain.CandidateMetrics
import com.v2ray.ang.ui.qsn.domain.CandidateRanker
import com.v2ray.ang.ui.qsn.domain.DiscoveryAccumulator
import com.v2ray.ang.ui.qsn.domain.DiscoveryQuality
import com.v2ray.ang.ui.qsn.domain.QualifiedRange
import com.v2ray.ang.ui.qsn.domain.RangeVerdict
import com.v2ray.ang.ui.qsn.domain.Statistics
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.v2ray.ang.senpai.IspManager
import com.v2ray.ang.senpai.IspProfile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
) {
    /** Median latency, or -1 when the edge never answered. */
    val latencyMs: Long get() = Statistics.median(latencySamples) ?: -1L

    /** Median absolute deviation of the samples. */
    val jitterMs: Long get() = Statistics.medianAbsoluteDeviation(latencySamples) ?: 0L

    val isSuccess: Boolean get() = latencySamples.isNotEmpty()
}

/** Result of one Stage 1 discovery run. */
data class DiscoveryOutcome(
    /** Ranges that passed the quality rule during this run. */
    val qualified: List<QualifiedRange>,
    /** Ranges actually measured, counting from the start index. */
    val scanned: Int,
    /** Total ranges in the bundled dataset. */
    val total: Int,
    /** Where the next run should resume; 0 once the dataset was exhausted. */
    val nextIndex: Int,
    /** True when the run reached the end of the dataset. */
    val exhausted: Boolean,
    /** True when cancel() stopped the run. */
    val cancelled: Boolean,
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

    /**
     * Subscription id that owns every throw-away probe profile.
     *
     * It is deliberately **not** registered in the subscription list, so
     * [MmkvManager.decodeAllServerList] and every screen built on it never see
     * a probe. Before this, a probe was published into the user's own
     * subscription and therefore appeared in the Configurations screen as a
     * copy of the selected configuration while the scan ran.
     */
    private const val PROBE_SUBSCRIPTION_ID = ProbeProfile.SUBSCRIPTION_ID

    /** Discovery concurrency. Lower than a candidate scan: every probe carries traffic. */
    private const val DISCOVERY_CONCURRENCY = 3

    /** Addresses sampled per range during discovery. */
    private const val DISCOVERY_IPS_PER_CIDR = 1

    /** Latency probes per candidate: median and jitter need more than one. */
    private const val LATENCY_SAMPLES = 3

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

    private var scanJob = SupervisorJob()

    /**
     * Last line of defence for the scan scope.
     *
     * The scan calls native code in the UI process. A native failure arrives as
     * an [Error] (UnsatisfiedLinkError, ExceptionInInitializerError, OOM), which
     * no catch(Exception) can contain; without this handler it would reach the
     * default handler and kill the app with no message. The handler turns it
     * into a logged, reported scan failure instead.
     */
    private val scanExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LogUtil.e(TAG, "Scan scope failed: " + throwable.javaClass.name, throwable)
    }

    private var scanScope = CoroutineScope(scanJob + Dispatchers.IO + scanExceptionHandler)

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
                    LogUtil.d(TAG, "Skip CIDR: $cidr")
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to load cf_ranges_v4.txt: ${e.message}")
            candidates.addAll(listOf("104.16.0.1", "104.17.0.1", "172.64.0.1", "162.159.0.1"))
        }
        return candidates.take(MAX_CANDIDATES)
    }

    private fun randomIpsFromCidr(baseIp: String, prefix: Int, count: Int): List<String> {
        val result = mutableListOf<String>()
        try {
            val base = ipToLong(baseIp)
            val hostBits = 32 - prefix
            if (hostBits <= 0) { result.add(baseIp); return result }
            val maxHosts = (1L shl hostBits) - 2
            if (maxHosts <= 0) { result.add(baseIp); return result }
            val seen = mutableSetOf<Long>()
            repeat(count * 3) {
                if (result.size >= count) return result
                val offset = (Random.nextLong() and Long.MAX_VALUE) % maxHosts + 1
                if (seen.add(offset)) result.add(longToIp(base + offset))
            }
        } catch (e: Exception) {
            LogUtil.d(TAG, "randomIpsFromCidr error: ${e.message}")
        }
        return result
    }

    private fun ipToLong(ip: String): Long {
        val p = ip.split(".")
        return (p[0].toLong() shl 24) or (p[1].toLong() shl 16) or (p[2].toLong() shl 8) or p[3].toLong()
    }

    private fun longToIp(n: Long) =
        "${(n shr 24) and 0xFF}.${(n shr 16) and 0xFF}.${(n shr 8) and 0xFF}.${n and 0xFF}"

    fun scan(
        context: Context,
        guid: String,
        candidates: List<String> = emptyList(),
        concurrency: Int = DEFAULT_CONCURRENCY,
        callback: ScanCallback,
    ) {
        cancel()
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
                purgeStaleTempProfiles()
                val actual = if (candidates.isEmpty()) generateCandidates(context) else candidates
                LogUtil.i(TAG, "Scan started: ${actual.size} candidates")
                runScan(context, guid, actual, concurrency, callback)
            } catch (e: CancellationException) {
                callback.onCancelled()
            } catch (t: Throwable) {
                // Includes Errors from the native layer: report, never crash.
                LogUtil.e(TAG, "Scan failed: " + t.javaClass.name, t)
                callback.onFinish(null)
            }
        }
    }

    fun cancel() { scanJob.cancel() }

    /** True while a scan or a discovery run is active in this process. */
    val isBusy: Boolean get() = scanJob.isActive

    /**
     * Deletes temp profiles left behind by a scan that was killed before its
     * per-candidate cleanup could run (process death, OOM kill).
     *
     * [testCandidate] removes its temp profile in a finally block, so a scan
     * that finished, failed, was cancelled or timed out leaves nothing behind.
     * Only an abrupt process death can orphan one. A probe now lives in its own
     * never-registered index, so an orphan is invisible to the Configurations
     * screen; this purge keeps the storage clean rather than hiding a duplicate.
     */
    fun purgeStaleTempProfiles() {
        var removed = 0

        // Everything in the probe index is ours and disposable: it is cleared
        // wholesale, which also covers a probe whose payload was already gone.
        val probeIndex = MmkvManager.decodeServerList(PROBE_SUBSCRIPTION_ID)
        if (probeIndex.isNotEmpty()) {
            MmkvManager.removeServers(probeIndex, PROBE_SUBSCRIPTION_ID)
            removed += probeIndex.size
        }

        // Migration: earlier builds wrote probes into the user's own
        // subscription, so any leftover with the prefix is removed there too.
        val subIds = LinkedHashSet<String>()
        subIds.addAll(MmkvManager.decodeSubsList())
        subIds.add(AppConfig.DEFAULT_SUBSCRIPTION_ID)
        subIds.forEach { subId ->
            val stale = MmkvManager.decodeServerList(subId)
                .filter { ProbeProfile.isProbe(it) }
            if (stale.isNotEmpty()) {
                MmkvManager.removeServers(stale, subId)
                removed += stale.size
            }
        }
        if (removed > 0) {
            LogUtil.i(TAG, "purgeStaleTempProfiles: removed $removed orphaned temp profile(s)")
        }
    }

    // ── Discovery: تست کامل با threshold نرم — فقط رنج‌هایی که واقعاً کار می‌کنن ────
    // لایه ۱: از هر CIDR یه IP تست کامل می‌زنه (latency + upload)
    // threshold: upload >= 20 KB/s — نرمه، هر رنجی که حداقل یه IP خوب داشت ذخیره میشه
    fun discoverGoodCidrs(
        context: Context,
        ispName: String,
        guid: String,
        onProgress: (done: Int, total: Int, cidr: String, responded: Boolean) -> Unit,
        onFinish: (goodCidrs: List<String>) -> Unit,
    ) {
        cancel()
        scanJob = SupervisorJob()
        scanScope = CoroutineScope(scanJob + Dispatchers.IO)
        scanScope.launch {
            val allLines = try {
                context.assets.open("cf_ranges_v4.txt")
                    .bufferedReader().readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
            } catch (e: Exception) { emptyList() }

            val existing     = IspManager.getProfile(context, ispName)
            val startFrom    = existing?.lastScannedIndex ?: 0
            val alreadyFound = existing?.goodCidrs?.toMutableList() ?: mutableListOf()
            val lines = if (startFrom > 0 && startFrom < allLines.size) {
                LogUtil.i(TAG, "Discovery resuming from $startFrom for $ispName")
                allLines.drop(startFrom)
            } else allLines

            val baseProfile = MmkvManager.decodeServerConfig(guid) ?: run {
                withContext(Dispatchers.Main) { onFinish(emptyList()) }
                return@launch
            }

            val total = allLines.size
            var done  = startFrom
            val newGoodCidrs = mutableListOf<Pair<String, Long>>()
            val semaphore = Semaphore(6)
            val lock = Any()

            val jobs = lines.map { cidr ->
                scanScope.launch {
                    semaphore.withPermit {
                        val parts = cidr.trim().split("/")
                        var bestUpload = 0L
                        var hasGoodIp  = false
                        if (parts.size == 2) {
                            try {
                                val ips = randomIpsFromCidr(parts[0], parts[1].toInt(), 2)
                                for (ip in ips) {
                                    val r = testCandidate(context, guid, baseProfile, ip)
                                    if (r.isSuccess && r.uploadKBps >= 20L) {
                                        hasGoodIp = true
                                        if (r.uploadKBps > bestUpload) bestUpload = r.uploadKBps
                                    }
                                }
                            } catch (e: Exception) {
                                LogUtil.d(TAG, "Discovery error $cidr: ${e.message}")
                            }
                        }
                        synchronized(lock) {
                            done++
                            if (hasGoodIp) newGoodCidrs.add(cidr.trim() to bestUpload)
                            if (done % 20 == 0) {
                                val partial = (alreadyFound + newGoodCidrs.map { it.first }).distinct()
                                IspManager.savePartialProgress(context, ispName, partial, done)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(done, total, cidr.trim(), hasGoodIp)
                        }
                    }
                }
            }
            try {
                joinAll(*jobs.toTypedArray())
                val allGood = (alreadyFound + newGoodCidrs.sortedByDescending { it.second }.map { it.first }).distinct().take(30)
                IspManager.saveProfile(context, IspProfile(ispName, allGood, lastScannedIndex = 0))
                withContext(Dispatchers.Main) { onFinish(allGood) }
            } catch (_: CancellationException) {
                val partial = (alreadyFound + newGoodCidrs.map { it.first }).distinct()
                IspManager.savePartialProgress(context, ispName, partial, done)
                withContext(Dispatchers.Main) { onFinish(partial) }
            }
        }
    }


    /**
     * Stage 2 over an explicit list of ranges.
     *
     * The measurement itself is [runScan]: same latency samples, same bounded
     * traffic test, same ranker as every other scan. Only the candidate source
     * changes, which is what keeps Stage 2 identical whether the ranges came
     * from discovery, the user, or the legacy ISP profile.
     */
    fun scanRanges(
        context: Context,
        guid: String,
        cidrs: List<String>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        callback: ScanCallback,
    ) {
        if (cidrs.isEmpty()) {
            // No range to sample: fall back to the bundled dataset, exactly as
            // the legacy path did, instead of scanning nothing.
            scan(context, guid, emptyList(), concurrency, callback)
            return
        }
        cancel()
        scanJob = SupervisorJob()
        scanScope = CoroutineScope(scanJob + Dispatchers.IO + scanExceptionHandler)
        scanScope.launch {
            try {
                purgeStaleTempProfiles()
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

    fun scanForIsp(
        context: Context,
        guid: String,
        ispName: String,
        concurrency: Int = DEFAULT_CONCURRENCY,
        callback: ScanCallback,
    ) {
        val profile = IspManager.getProfile(context, ispName)
        if (profile == null || profile.goodCidrs.isEmpty()) {
            scan(context, guid, emptyList(), concurrency, callback)
            return
        }
        scanRanges(context, guid, profile.goodCidrs, concurrency, callback)
    }

    /**
     * Stage 1: measures a sample from each range and keeps the ones that
     * qualify, then stops.
     *
     * The quality rule is [DiscoveryQuality] — reachable **and** real upload
     * above the gate — the same rule the historical ISP discovery used. A range
     * that merely answers is not saved.
     *
     * The run stops as soon as [target] distinct ranges qualify, so a connect
     * attempt never waits for the whole dataset. That is a pause, not a
     * cancellation: [startIndex] and [DiscoveryOutcome.nextIndex] let the next
     * run continue where this one stopped, which is what the background run
     * after a successful connection uses.
     */
    fun discoverRanges(
        context: Context,
        guid: String,
        ispName: String,
        target: Int = DiscoveryQuality.TARGET_RANGES,
        startIndex: Int = 0,
        onProgress: (done: Int, total: Int, cidr: String, qualified: Boolean) -> Unit = { _, _, _, _ -> },
        onFinish: (DiscoveryOutcome) -> Unit,
    ) {
        cancel()
        scanJob = SupervisorJob()
        scanScope = CoroutineScope(scanJob + Dispatchers.IO + scanExceptionHandler)
        scanScope.launch {
            val outcome = try {
                purgeStaleTempProfiles()
                runDiscovery(context, guid, ispName, target, startIndex, onProgress)
            } catch (e: CancellationException) {
                DiscoveryOutcome(emptyList(), 0, 0, startIndex, exhausted = false, cancelled = true)
            } catch (t: Throwable) {
                LogUtil.e(TAG, "Discovery failed: " + t.javaClass.name, t)
                DiscoveryOutcome(emptyList(), 0, 0, startIndex, exhausted = false, cancelled = false)
            }
            onFinish(outcome)
        }
    }

    private suspend fun runDiscovery(
        context: Context,
        guid: String,
        ispName: String,
        target: Int,
        startIndex: Int,
        onProgress: (done: Int, total: Int, cidr: String, qualified: Boolean) -> Unit,
    ): DiscoveryOutcome {
        val lines = readRangeAsset(context)
        if (lines.isEmpty()) {
            return DiscoveryOutcome(emptyList(), 0, 0, startIndex, exhausted = true, cancelled = false)
        }
        val base = MmkvManager.decodeServerConfig(guid)
            ?: return DiscoveryOutcome(emptyList(), 0, 0, startIndex, exhausted = false, cancelled = false)

        val total = lines.size
        val from = startIndex.coerceIn(0, total)
        val work = lines.subList(from, total)
        val accumulator = DiscoveryAccumulator(target)
        val done = AtomicInteger(from)
        val stopped = AtomicBoolean(false)
        val semaphore = Semaphore(DISCOVERY_CONCURRENCY)

        val jobs = work.map { cidr ->
            scanScope.launch {
                semaphore.withPermit {
                    if (!stopped.get()) {
                        val qualified = probeRange(context, guid, base, cidr)
                        if (qualified != null) {
                            // Three coroutines share the accumulator.
                            synchronized(accumulator) {
                                accumulator.offer(qualified)
                                if (accumulator.reachedTarget) stopped.set(true)
                            }
                        }
                        val scanned = done.incrementAndGet()
                        onProgress(scanned, total, cidr.trim(), qualified != null)
                    }
                }
            }
        }
        jobs.joinAll()

        val scanned = done.get()
        val exhausted = !stopped.get() && from + work.size >= total
        val nextIndex = if (exhausted) 0 else scanned.coerceIn(0, total)
        IspManager.savePartialProgress(
            context = context,
            ispName = ispName,
            foundCidrs = accumulator.qualified.map { it.cidr },
            lastIndex = nextIndex,
        )
        return DiscoveryOutcome(
            qualified = accumulator.qualified,
            scanned = scanned,
            total = total,
            nextIndex = nextIndex,
            exhausted = exhausted,
            cancelled = false,
        )
    }

    /** Measures one sampled address and returns the range when it qualifies. */
    private suspend fun probeRange(
        context: Context,
        guid: String,
        base: ProfileItem,
        cidr: String,
    ): QualifiedRange? {
        val parts = cidr.trim().split("/")
        if (parts.size != 2) return null
        val prefix = parts[1].toIntOrNull() ?: return null
        val ips = randomIpsFromCidr(parts[0], prefix, DISCOVERY_IPS_PER_CIDR)
        var best: QualifiedRange? = null
        for (ip in ips) {
            val result = testCandidate(context, guid, base, ip)
            // A missing traffic measurement is null, never 0: the range is then
            // neither accepted nor rejected.
            val upload = if (result.hasTraffic) result.uploadKBps else null
            if (DiscoveryQuality.verdict(result.isSuccess, upload) != RangeVerdict.Good) continue
            val candidate = QualifiedRange(
                cidr = cidr.trim(),
                uploadKBps = upload ?: 0L,
                latencyMs = result.latencyMs.coerceAtLeast(0L),
                validatedAt = System.currentTimeMillis(),
            )
            val current = best
            if (current == null || candidate.uploadKBps > current.uploadKBps) best = candidate
        }
        return best
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

    private fun pingIp(ip: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", "1", ip))
            proc.waitFor() == 0
        } catch (e: Exception) { false }
    }

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
        // time (line 558), so the profile value is not load-bearing for the
        // outbound config. We do not overwrite a non-blank value to respect
        // user intent and avoid unnecessary mutation of TLS settings.
        if (profile.fingerPrint.isNullOrBlank()) profile.fingerPrint = "unsafe"
        if (profile.finalMask.isNullOrBlank()) profile.finalMask = PRODUCTION_FINALMASK
        if (profile.cipherSuites.isNullOrBlank()) profile.cipherSuites = PRODUCTION_CIPHERSUITES
        MmkvManager.encodeServerConfig(guid, profile)
        LogUtil.i(TAG, "applyBestIp: applied best IP to profile")
        return true
    }

    private suspend fun runScan(
        context: Context,
        guid: String,
        candidates: List<String>,
        concurrency: Int,
        callback: ScanCallback,
    ) {
        val baseProfile = MmkvManager.decodeServerConfig(guid) ?: run {
            callback.onFinish(null); return
        }
        val total = candidates.size
        var done = 0
        val results = mutableListOf<CandidateResult>()
        val semaphore = Semaphore(concurrency)
        val lock = Any()
        val jobs = candidates.map { ip ->
            scanScope.launch {
                semaphore.withPermit {
                    val result = testCandidate(context, guid, baseProfile, ip)
                    synchronized(lock) { done++; results.add(result) }
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
                "Done. Candidates=${metrics.size} reachable=" + metrics.count { it.reachable } +
                    " best=${best?.ip} score=" + (scored?.score?.toInt() ?: -1) +
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
            // Throwable, not Exception: a native Error must become a reported
            // scan failure instead of a process death.
            LogUtil.e(TAG, "Scan error: " + t.javaClass.name, t)
            callback.onFinish(null)
        }
    }

    private suspend fun testCandidate(
        context: Context,
        baseGuid: String,
        base: ProfileItem,
        ip: String,
    ): CandidateResult = withContext(Dispatchers.IO) {
        val tempGuid = ProbeProfile.guidFor(baseGuid, ip)
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
                subscriptionId = PROBE_SUBSCRIPTION_ID,
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
                LogUtil.d(TAG, "$ip -> FAIL (config build)")
                return@withContext CandidateResult(ip, failures = 1)
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
                LogUtil.d(TAG, "$ip -> FAIL (no latency sample)")
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
                "$ip -> p50=" + (Statistics.median(samples) ?: -1) + "ms jitter=" +
                    (Statistics.medianAbsoluteDeviation(samples) ?: -1) + "ms dn=" + downloadKBps +
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
            LogUtil.e(TAG, "$ip -> probe failed: " + t.javaClass.name)
            CandidateResult(ip, failures = 1)
        } finally {
            try { MmkvManager.removeServer(tempGuid) } catch (_: Exception) {}
        }
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

    /** Stable probe GUID for one address measured against one base profile. */
    fun guidFor(baseGuid: String, ip: String): String =
        GUID_PREFIX + baseGuid + "-" + ip.replace('.', '-').replace(':', '-')

    /** True when [guid] belongs to a probe, including leftovers from old builds. */
    fun isProbe(guid: String): Boolean = guid.startsWith(GUID_PREFIX)

    /** True when a profile stored under [subscriptionId] is isolated from user lists. */
    fun isIsolated(subscriptionId: String?): Boolean = subscriptionId == SUBSCRIPTION_ID
}
