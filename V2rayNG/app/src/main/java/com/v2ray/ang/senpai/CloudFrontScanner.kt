package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.qsn.data.CloudFrontRangeProvider
import com.v2ray.ang.ui.qsn.data.ProviderRangeStore
import com.v2ray.ang.ui.qsn.domain.DiscoveryPhase
import com.v2ray.ang.ui.qsn.domain.DiscoveryQuality
import com.v2ray.ang.ui.qsn.domain.DiscoveryStatus
import com.v2ray.ang.ui.qsn.domain.EdgeProvider
import com.v2ray.ang.ui.qsn.domain.QualifiedRange
import com.v2ray.ang.ui.qsn.domain.RangeOutcome
import com.v2ray.ang.ui.qsn.domain.RangeState
import com.v2ray.ang.ui.qsn.domain.ReferenceCandidateSelector
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Independent Amazon CloudFront scanner.
 *
 * Uses the same verified September 8 (`c489a5aa`) probing core ([CloudflareScanner.probeCandidate]
 * and [ReferenceCandidateSelector]), while keeping all CloudFront ranges, discovery runs,
 * and candidate results strictly separated in [ProviderRangeStore] ([EdgeProvider.CLOUDFRONT]).
 */
object CloudFrontScanner {

    private const val TAG = "CloudFrontScanner"
    private const val DEFAULT_CONCURRENCY = 4
    private const val MAX_CANDIDATES = 30
    private const val RUN_CF_DISCOVERY = "cfd"
    private const val RUN_CF_SCAN = "cfs"

    private val scanExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LogUtil.e(TAG, "CloudFront scan scope failed: " + throwable.javaClass.name, throwable)
    }

    private var scanJob = SupervisorJob()
    private var scanScope = CoroutineScope(scanJob + Dispatchers.IO + scanExceptionHandler)

    private val discoveryLock = Any()
    @Volatile private var discoveryJob = SupervisorJob()
    private var discoveryScope = CoroutineScope(discoveryJob + Dispatchers.IO + scanExceptionHandler)

    private val _discovery = MutableStateFlow(DiscoveryStatus(ispName = EdgeProvider.CLOUDFRONT.id))
    val discovery: StateFlow<DiscoveryStatus> = _discovery.asStateFlow()

    val isBusy: Boolean get() = scanJob.isActive
    val isDiscoveryActive: Boolean get() = discoveryJob.isActive

    fun cancelScan() {
        scanJob.cancel()
    }

    fun stopDiscovery() {
        synchronized(discoveryLock) {
            if (!discoveryJob.isActive) return
            discoveryJob.cancel()
            _discovery.update { it.copy(phase = DiscoveryPhase.Paused, measuring = emptyMap()) }
        }
    }

    fun scanRanges(
        context: Context,
        guid: String,
        cidrs: List<String>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        callback: ScanCallback,
    ) {
        if (cidrs.isEmpty()) {
            LogUtil.w(TAG, "scanRanges called without CloudFront ranges")
            callback.onFinish(null)
            return
        }
        cancelScan()
        if (!CoreNativeManager.isNativeAvailable()) {
            callback.onFinish(null)
            return
        }
        scanJob = SupervisorJob()
        scanScope = CoroutineScope(scanJob + Dispatchers.IO + scanExceptionHandler)
        scanScope.launch {
            try {
                val candidates = CloudflareScanner.candidatesFromRanges(cidrs, maxCandidates = MAX_CANDIDATES)
                val baseProfile = MmkvManager.decodeServerConfig(guid) ?: run {
                    callback.onFinish(null)
                    return@launch
                }
                val total = candidates.size
                var done = 0
                val results = mutableListOf<CandidateResult>()
                val semaphore = Semaphore(concurrency)
                val lock = Any()
                val jobs = candidates.map { ip ->
                    scanScope.launch {
                        semaphore.withPermit {
                            val result = CloudflareScanner.probeCandidate(
                                context,
                                guid,
                                baseProfile,
                                ip,
                                RUN_CF_SCAN,
                            )
                            synchronized(lock) {
                                done++
                                results.add(result)
                            }
                            callback.onProgress(result, done, total)
                        }
                    }
                }
                joinAll(*jobs.toTypedArray())
                val scored = ReferenceCandidateSelector.scoreForDisplay(results)
                val best = ReferenceCandidateSelector.selectBest(scored)
                callback.onFinish(best)
            } catch (_: CancellationException) {
                callback.onCancelled()
            } catch (t: Throwable) {
                LogUtil.e(TAG, "CloudFront scan error: " + t.javaClass.name, t)
                callback.onFinish(null)
            }
        }
    }

    /**
     * Discovers working CloudFront ranges against official AWS `ip-ranges.json`
     * entries (`service == "CLOUDFRONT"`), persisting qualified ranges into
     * [ProviderRangeStore] under [EdgeProvider.CLOUDFRONT].
     */
    fun discoverRanges(
        context: Context,
        guid: String,
        background: Boolean = true,
        onFinish: (List<QualifiedRange>) -> Unit = {},
    ): Boolean {
        synchronized(discoveryLock) {
            if (discoveryJob.isActive) return false
            val base: ProfileItem = MmkvManager.decodeServerConfig(guid) ?: run {
                _discovery.update { it.copy(phase = DiscoveryPhase.Failed) }
                onFinish(emptyList())
                return false
            }
            if (!CoreNativeManager.isNativeAvailable()) {
                _discovery.update { it.copy(phase = DiscoveryPhase.Failed) }
                onFinish(emptyList())
                return false
            }

            discoveryJob = SupervisorJob()
            discoveryScope = CoroutineScope(discoveryJob + Dispatchers.IO + scanExceptionHandler)
            val app = context.applicationContext

            discoveryScope.launch {
                val currentStored = ProviderRangeStore.snapshot(EdgeProvider.CLOUDFRONT).fetchedCidrs
                val dataset = if (currentStored.isEmpty()) {
                    when (val fetched = CloudFrontRangeProvider.fetchOfficialRanges(app)) {
                        is CloudFrontRangeProvider.FetchResult.Success -> fetched.cidrs
                        is CloudFrontRangeProvider.FetchResult.Failure -> emptyList()
                    }
                } else {
                    currentStored.map { it.cidr }
                }

                if (dataset.isEmpty()) {
                    _discovery.update { it.copy(phase = DiscoveryPhase.Failed, background = background) }
                    withContext(Dispatchers.Main) { onFinish(emptyList()) }
                    return@launch
                }

                val measuring = ConcurrentHashMap<String, List<String>>()
                val outcomes = ConcurrentHashMap<String, RangeOutcome>()
                val goodList = mutableListOf<QualifiedRange>()
                val total = dataset.size
                var done = 0
                var good = 0
                var failed = 0
                var errors = 0
                val lock = Any()
                val semaphore = Semaphore(DiscoveryQuality.CONCURRENCY)

                _discovery.value = DiscoveryStatus(
                    phase = DiscoveryPhase.Running,
                    background = background,
                    ispName = EdgeProvider.CLOUDFRONT.id,
                    total = total,
                )

                val jobs = dataset.map { cidr ->
                    discoveryScope.launch {
                        semaphore.withPermit {
                            val ips = CloudflareScanner.sampleIpsForRange(cidr, DiscoveryQuality.IPS_PER_RANGE)
                            if (ips.isEmpty()) {
                                synchronized(lock) {
                                    done++
                                    errors++
                                    outcomes[cidr] = RangeOutcome(cidr, RangeState.Error, checkedAt = System.currentTimeMillis())
                                }
                                return@withPermit
                            }
                            measuring[cidr] = ips
                            _discovery.update { it.copy(measuring = measuring.toMap()) }

                            var bestUp: Long? = null
                            var bestDn: Long? = null
                            var bestLat: Long? = null
                            var anyError = true
                            var anySuccess = false

                            for (ip in ips) {
                                val res = CloudflareScanner.probeCandidate(app, guid, base, ip, RUN_CF_DISCOVERY)
                                if (res.isSuccess && res.hasTraffic) {
                                    anySuccess = true
                                    anyError = false
                                    bestUp = bestUp?.let { maxOf(it, res.uploadKBps) } ?: res.uploadKBps
                                    bestDn = bestDn?.let { maxOf(it, res.downloadKBps) } ?: res.downloadKBps
                                    bestLat = bestLat?.let { minOf(it, res.latencyMs) } ?: res.latencyMs
                                } else if (!res.configError) {
                                    anyError = false
                                }
                            }
                            measuring.remove(cidr)

                            val verdict = DiscoveryQuality.verdict(anySuccess, bestUp)
                            val now = System.currentTimeMillis()
                            val state = when {
                                verdict == com.v2ray.ang.ui.qsn.domain.RangeVerdict.Good -> RangeState.Good
                                !anySuccess && anyError -> RangeState.Error
                                else -> RangeState.Failed
                            }
                            val outcome = RangeOutcome(
                                cidr = cidr,
                                state = state,
                                uploadKBps = bestUp,
                                downloadKBps = bestDn,
                                latencyMs = bestLat,
                                checkedAt = now,
                            )
                            outcomes[cidr] = outcome

                            if (state == RangeState.Good) {
                                val q = outcome.toQualifiedRange()
                                ProviderRangeStore.mergeDiscovered(EdgeProvider.CLOUDFRONT, listOf(q))
                                synchronized(lock) { goodList.add(q) }
                            }
                            synchronized(lock) {
                                done++
                                when (state) {
                                    RangeState.Good -> good++
                                    RangeState.Failed -> failed++
                                    RangeState.Error -> errors++
                                    else -> Unit
                                }
                            }
                            _discovery.update {
                                it.copy(
                                    phase = DiscoveryPhase.Running,
                                    background = background,
                                    done = done,
                                    total = total,
                                    qualified = good,
                                    failed = failed,
                                    errors = errors,
                                    lastCidr = cidr,
                                    rows = outcomes.values.sortedBy { r -> r.cidr },
                                    measuring = measuring.toMap(),
                                )
                            }
                        }
                    }
                }

                try {
                    joinAll(*jobs.toTypedArray())
                    _discovery.update {
                        it.copy(
                            phase = DiscoveryPhase.Completed,
                            measuring = emptyMap(),
                        )
                    }
                    val result = ProviderRangeStore.snapshot(EdgeProvider.CLOUDFRONT).discoveredCidrs
                    withContext(Dispatchers.Main) { onFinish(result) }
                } catch (_: CancellationException) {
                    _discovery.update { it.copy(phase = DiscoveryPhase.Paused, measuring = emptyMap()) }
                    val result = ProviderRangeStore.snapshot(EdgeProvider.CLOUDFRONT).discoveredCidrs
                    withContext(Dispatchers.Main) { onFinish(result) }
                }
            }
            true
        }
    }
}
