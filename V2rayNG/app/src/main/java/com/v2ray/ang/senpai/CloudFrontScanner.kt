package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Amazon CloudFront IPv4 scanner and ISP Discovery engine.
 *
 * Strictly reuses the exact September 8 measurement core
 * ([CloudflareScanner.probeCandidateWithCore] + [CloudflareScanner.applyBestIp])
 * while reading and persisting CloudFront-only ranges via [ProviderRangeStore].
 */
object CloudFrontScanner {

    private const val TAG = "CloudFrontScanner"
    private const val DEFAULT_CONCURRENCY = 4
    private const val IPS_PER_CIDR = 2
    private const val MAX_CANDIDATES = 30
    private const val DISCOVERY_CONCURRENCY = 6
    private const val MIN_DISCOVERY_UPLOAD_KBPS = 20L

    private var scanJob = SupervisorJob()
    private var scanScope = CoroutineScope(scanJob + Dispatchers.IO)

    fun cancel() {
        scanJob.cancel()
    }

    /**
     * Generates up to [MAX_CANDIDATES] CloudFront candidate IPv4s, prioritizing any
     * ISP-qualified [RangeOrigin.DISCOVERED] and [RangeOrigin.MANUAL] CIDRs first.
     */
    fun generateCandidates(context: Context): List<String> {
        val entries = ProviderRangeStore.getEntries(context, CdnType.CLOUDFRONT).filter { it.enabled }
        val priority = entries.filter {
            it.origin == RangeOrigin.DISCOVERED || it.origin == RangeOrigin.MANUAL
        }.map { it.cidr }.shuffled()
        val fetched = entries.filter { it.origin == RangeOrigin.FETCHED }
            .map { it.cidr }
            .ifEmpty { ProviderRangeStore.loadBundledCloudFrontCidrs(context) }
            .shuffled()

        val orderedCidrs = (priority + fetched).distinct()
        val candidates = mutableListOf<String>()
        for (cidr in orderedCidrs) {
            if (candidates.size >= MAX_CANDIDATES) break
            candidates.addAll(CloudflareScanner.sampleIpsFromCidr(cidr, IPS_PER_CIDR))
        }
        return candidates.distinct().take(MAX_CANDIDATES)
    }

    fun scan(
        context: Context,
        guid: String,
        candidates: List<String> = emptyList(),
        concurrency: Int = DEFAULT_CONCURRENCY,
        callback: ScanCallback,
    ) {
        cancel()
        scanJob = SupervisorJob()
        scanScope = CoroutineScope(scanJob + Dispatchers.IO)
        scanScope.launch {
            CoreNativeManager.initCoreEnv(context.applicationContext)
            val actual = if (candidates.isEmpty()) generateCandidates(context) else candidates
            val baseProfile = MmkvManager.decodeServerConfig(guid) ?: run {
                callback.onFinish(null)
                return@launch
            }
            OriginDomainStore.rememberIfDomain(guid, baseProfile)

            if (actual.isEmpty()) {
                callback.onFinish(null)
                return@launch
            }

            val total = actual.size
            var done = 0
            val results = mutableListOf<CandidateResult>()
            val semaphore = Semaphore(concurrency)
            val lock = Any()

            val jobs = actual.map { ip ->
                scanScope.launch {
                    semaphore.withPermit {
                        val result = CloudflareScanner.probeCandidateWithCore(context, guid, baseProfile, ip)
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
                val best = results.filter { it.isSuccess }.maxByOrNull { it.uploadKBps }
                LogUtil.i(TAG, "Scan done. Best=${best?.ip} @ ${best?.latencyMs}ms up=${best?.uploadKBps}KB/s")
                callback.onFinish(best)
            } catch (_: CancellationException) {
                callback.onCancelled()
            } catch (e: Exception) {
                LogUtil.e(TAG, "CloudFront scan error: ${e.message}", e)
                callback.onFinish(null)
            }
        }
    }

    /**
     * Refreshes AWS CloudFront IPv4 CIDRs (falling back cleanly to the bundled snapshot) and
     * probes every range with the untouched Xray measurement core, appending working CIDRs to
     * CloudFront's separate [ProviderRangeStore] bucket.
     */
    fun discoverGoodCidrs(
        context: Context,
        guid: String,
        onProgress: (done: Int, total: Int, cidr: String, responded: Boolean) -> Unit,
        onFinish: (goodCidrs: List<String>) -> Unit,
    ) {
        cancel()
        scanJob = SupervisorJob()
        scanScope = CoroutineScope(scanJob + Dispatchers.IO)
        scanScope.launch {
            val appCtx = context.applicationContext
            if (!PhysicalNetworkGuard.hasPhysicalInternet(appCtx)) {
                LogUtil.w(TAG, "CloudFront discovery skipped: no physical non-VPN internet available")
                withContext(Dispatchers.Main) { onFinish(emptyList()) }
                return@launch
            }
            com.v2ray.ang.handler.SettingsManager.initAssets(appCtx, appCtx.assets)
            CoreNativeManager.initCoreEnv(appCtx)
            val watcher = PhysicalNetworkGuard.Watcher(appCtx) { cancel() }
            watcher.start()
            ProviderRangeStore.syncCloudFrontFromAws(appCtx)
            val allCidrs = ProviderRangeStore.getEntries(appCtx, CdnType.CLOUDFRONT)
                .map { it.cidr }
                .distinct()
                .ifEmpty { ProviderRangeStore.loadBundledCloudFrontCidrs(appCtx) }

            val baseProfile = MmkvManager.decodeServerConfig(guid) ?: run {
                watcher.stop()
                withContext(Dispatchers.Main) { onFinish(emptyList()) }
                return@launch
            }
            OriginDomainStore.rememberIfDomain(guid, baseProfile)

            val total = allCidrs.size
            var done = 0
            val discovered = mutableListOf<Pair<String, Long>>()
            val semaphore = Semaphore(DISCOVERY_CONCURRENCY)
            val lock = Any()

            val jobs = allCidrs.map { cidr ->
                scanScope.launch {
                    semaphore.withPermit {
                        var bestUpload = 0L
                        var hasGoodIp = false
                        val ips = CloudflareScanner.sampleIpsFromCidr(cidr, IPS_PER_CIDR)
                        for (ip in ips) {
                            val res = CloudflareScanner.probeCandidateWithCore(context, guid, baseProfile, ip)
                            if (res.isSuccess && res.uploadKBps >= MIN_DISCOVERY_UPLOAD_KBPS) {
                                hasGoodIp = true
                                if (res.uploadKBps > bestUpload) bestUpload = res.uploadKBps
                            }
                        }
                        synchronized(lock) {
                            done++
                            if (hasGoodIp) discovered.add(cidr to bestUpload)
                            if (done % 15 == 0 && discovered.isNotEmpty()) {
                                ProviderRangeStore.appendDiscoveredCidrs(
                                    context,
                                    CdnType.CLOUDFRONT,
                                    discovered.map { it.first }
                                )
                            }
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(done, total, cidr, hasGoodIp)
                        }
                    }
                }
            }
            try {
                joinAll(*jobs.toTypedArray())
                val sortedGood = synchronized(lock) {
                    discovered.sortedByDescending { it.second }.map { it.first }.distinct()
                }
                val merged = if (sortedGood.isNotEmpty()) {
                    ProviderRangeStore.appendDiscoveredCidrs(context, CdnType.CLOUDFRONT, sortedGood)
                } else {
                    emptyList()
                }
                withContext(Dispatchers.Main) { onFinish(merged) }
            } catch (_: CancellationException) {
                val partial = synchronized(lock) { discovered.map { it.first }.distinct() }
                if (partial.isNotEmpty()) {
                    ProviderRangeStore.appendDiscoveredCidrs(context, CdnType.CLOUDFRONT, partial)
                }
                withContext(Dispatchers.Main) { onFinish(partial) }
            } finally {
                watcher.stop()
            }
        }
    }
}
