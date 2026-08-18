package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.ProfileItem
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

data class CandidateResult(
    val ip: String,
    val latencyMs: Long,
) {
    val isSuccess: Boolean get() = latencyMs >= 0
}

interface ScanCallback {
    fun onProgress(result: CandidateResult, done: Int, total: Int)
    fun onFinish(best: CandidateResult?)
    fun onCancelled()
}

/**
 * Scans Cloudflare candidate IPs using the xray-core already in PattNG.
 *
 * For each IP: clones the base profile, swaps only `server`, builds a
 * speedtest config, runs a real xray tunnel test via CoreNativeManager,
 * then removes the temp profile. Returns the lowest-latency IP.
 *
 * All TLS settings (sni, alpn, fingerPrint, cipherSuites, finalMask, security)
 * are inherited from the base profile unchanged — exactly what real users connect with.
 */
object CloudflareScanner {

    private const val TAG = "CloudflareScanner"
    private const val TEST_URL = "https://www.gstatic.com/generate_204"
    private const val DEFAULT_CONCURRENCY = 3

    private var scanJob = SupervisorJob()
    private var scanScope = CoroutineScope(scanJob + Dispatchers.IO)

    fun scan(
        context: Context,
        guid: String,
        candidates: List<String>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        callback: ScanCallback,
    ) {
        cancel()
        scanJob = SupervisorJob()
        scanScope = CoroutineScope(scanJob + Dispatchers.IO)
        scanScope.launch {
            runScan(context, guid, candidates, concurrency, callback)
        }
    }

    fun cancel() {
        scanJob.cancel()
    }

    /**
     * Write the winning IP back onto the original profile.
     * Call from onFinish() when best != null, then connect VPN normally.
     */
    fun applyBestIp(guid: String, bestIp: String): Boolean {
        val profile = MmkvManager.decodeServerConfig(guid) ?: run {
            LogUtil.e(TAG, "applyBestIp: profile not found for guid=$guid")
            return false
        }
        profile.server = bestIp
        MmkvManager.encodeServerConfig(guid, profile)
        LogUtil.i(TAG, "applyBestIp: $guid → server=$bestIp")
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
            LogUtil.e(TAG, "Base profile not found: guid=$guid")
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
                    val result = testCandidate(context, guid, baseProfile, ip)
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
            val best = results.filter { it.isSuccess }.minByOrNull { it.latencyMs }
            LogUtil.i(TAG, "Scan done. Best: ${best?.ip} @ ${best?.latencyMs}ms")
            callback.onFinish(best)
        } catch (_: CancellationException) {
            callback.onCancelled()
        } catch (e: Exception) {
            LogUtil.e(TAG, "Scan error: ${e.message}", e)
            callback.onFinish(null)
        }
    }

    private suspend fun testCandidate(
        context: Context,
        baseGuid: String,
        base: ProfileItem,
        ip: String,
    ): CandidateResult = withContext(Dispatchers.IO) {
        val tempGuid = "cfscanner-$baseGuid-${ip.replace('.', '-').replace(':', '-')}"

        return@withContext try {
            // Full copy of all ProfileItem fields — only server is swapped.
            // This ensures sni, alpn, fingerPrint, cipherSuites, finalMask,
            // security, flow, wsPath/host, etc. are identical to the real config.
            val temp = ProfileItem(
                configVersion              = base.configVersion,
                configType                 = base.configType,
                subscriptionId             = base.subscriptionId,
                addedTime                  = base.addedTime,
                remarks                    = base.remarks,
                description                = base.description,
                server                     = ip,              // ← only change
                serverPort                 = base.serverPort,
                password                   = base.password,
                method                     = base.method,
                flow                       = base.flow,
                username                   = base.username,
                network                    = base.network,
                headerType                 = base.headerType,
                host                       = base.host,
                path                       = base.path,
                seed                       = base.seed,
                kcpMtu                     = base.kcpMtu,
                kcpTti                     = base.kcpTti,
                quicSecurity               = base.quicSecurity,
                quicKey                    = base.quicKey,
                mode                       = base.mode,
                serviceName                = base.serviceName,
                authority                  = base.authority,
                xhttpMode                  = base.xhttpMode,
                xhttpExtra                 = base.xhttpExtra,
                finalMask                  = base.finalMask,
                security                   = base.security,
                sni                        = base.sni,
                alpn                       = base.alpn,
                fingerPrint                = base.fingerPrint,
                cipherSuites               = base.cipherSuites,
                insecure                   = base.insecure,
                echConfigList              = base.echConfigList,
                verifyPeerCertByName       = base.verifyPeerCertByName,
                pinnedCA256                = base.pinnedCA256,
                publicKey                  = base.publicKey,
                shortId                    = base.shortId,
                spiderX                    = base.spiderX,
                mldsa65Verify              = base.mldsa65Verify,
                secretKey                  = base.secretKey,
                preSharedKey               = base.preSharedKey,
                localAddress               = base.localAddress,
                reserved                   = base.reserved,
                mtu                        = base.mtu,
                obfsPassword               = base.obfsPassword,
                portHopping                = base.portHopping,
                portHoppingInterval        = base.portHoppingInterval,
                pinSHA256                  = base.pinSHA256,
                bandwidthDown              = base.bandwidthDown,
                bandwidthUp                = base.bandwidthUp,
                policyGroupType            = base.policyGroupType,
                policyGroupSubscriptionId  = base.policyGroupSubscriptionId,
                policyGroupFilter          = base.policyGroupFilter,
                policyGroupTestOutbounds   = base.policyGroupTestOutbounds,
                policyGroupFallbackTag     = base.policyGroupFallbackTag,
                proxyChainProfiles         = base.proxyChainProfiles,
                browserDialerMode          = base.browserDialerMode,
            )

            MmkvManager.encodeServerConfig(tempGuid, temp)

            val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, tempGuid)
            if (!configResult.status) {
                LogUtil.d(TAG, "Config build failed for $ip: ${configResult.errorMessage}")
                return@withContext CandidateResult(ip, -1L)
            }

            val latency = CoreNativeManager.measureOutboundDelay(configResult.content, TEST_URL)
            LogUtil.d(TAG, "  $ip → ${if (latency >= 0) "${latency}ms" else "FAILED"}")

            CandidateResult(ip, latency)

        } catch (e: Exception) {
            LogUtil.e(TAG, "Error testing $ip: ${e.message}", e)
            CandidateResult(ip, -1L)
        } finally {
            try { MmkvManager.removeServer(tempGuid) } catch (_: Exception) {}
        }
    }
}
