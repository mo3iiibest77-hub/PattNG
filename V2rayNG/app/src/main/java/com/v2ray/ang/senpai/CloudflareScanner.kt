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
import java.net.InetAddress
import kotlin.random.Random

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

object CloudflareScanner {
    private const val TAG = "CloudflareScanner"
    private const val TEST_URL = "https://www.gstatic.com/generate_204"
    private const val DEFAULT_CONCURRENCY = 4
    private const val IPS_PER_CIDR = 2      // از هر CIDR چند IP بگیر
    private const val MAX_CANDIDATES = 60   // حداکثر تعداد IP برای تست

    private var scanJob = SupervisorJob()
    private var scanScope = CoroutineScope(scanJob + Dispatchers.IO)

    /**
     * بارگذاری CIDR ها از assets و تولید IP های random
     */
    private fun generateCandidates(context: Context): List<String> {
        val candidates = mutableListOf<String>()
        try {
            val lines = context.assets.open("cf_ranges_v4.txt")
                .bufferedReader()
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .shuffled() // ترتیب random

            for (cidr in lines) {
                if (candidates.size >= MAX_CANDIDATES) break
                try {
                    val parts = cidr.trim().split("/")
                    if (parts.size != 2) continue
                    val baseIp = parts[0]
                    val prefix = parts[1].toInt()
                    val ips = randomIpsFromCidr(baseIp, prefix, IPS_PER_CIDR)
                    candidates.addAll(ips)
                } catch (e: Exception) {
                    LogUtil.d(TAG, "Skip invalid CIDR: $cidr")
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to load cf_ranges_v4.txt: ${e.message}")
            // fallback به چند IP ثابت
            candidates.addAll(listOf(
                "104.16.0.1", "104.17.0.1", "104.18.0.1",
                "172.64.0.1", "162.159.0.1", "104.21.0.1"
            ))
        }
        return candidates.take(MAX_CANDIDATES)
    }

    /**
     * تولید IP های random از یک CIDR
     */
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
            repeat(count * 3) { // چند بار تلاش برای پیدا کردن unique
                if (result.size >= count) return result
                val offset = (Random.nextLong() and Long.MAX_VALUE) % maxHosts + 1
                if (seen.add(offset)) {
                    result.add(longToIp(base + offset))
                }
            }
        } catch (e: Exception) {
            LogUtil.d(TAG, "randomIpsFromCidr error: ${e.message}")
        }
        return result
    }

    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".")
        return (parts[0].toLong() shl 24) or
               (parts[1].toLong() shl 16) or
               (parts[2].toLong() shl 8) or
               parts[3].toLong()
    }

    private fun longToIp(n: Long): String {
        return "${(n shr 24) and 0xFF}.${(n shr 16) and 0xFF}.${(n shr 8) and 0xFF}.${n and 0xFF}"
    }

    fun scan(
        context: Context,
        guid: String,
        candidates: List<String> = emptyList(), // اگه خالی بود از CIDR می‌سازه
        concurrency: Int = DEFAULT_CONCURRENCY,
        callback: ScanCallback,
    ) {
        cancel()
        scanJob = SupervisorJob()
        scanScope = CoroutineScope(scanJob + Dispatchers.IO)

        scanScope.launch {
            val actualCandidates = if (candidates.isEmpty())
                generateCandidates(context)
            else candidates

            LogUtil.i(TAG, "Starting scan with ${actualCandidates.size} candidates")
            runScan(context, guid, actualCandidates, concurrency, callback)
        }
    }

    fun cancel() {
        scanJob.cancel()
    }

    fun applyBestIp(guid: String, bestIp: String): Boolean {
        val profile = MmkvManager.decodeServerConfig(guid) ?: run {
            LogUtil.e(TAG, "applyBestIp: profile not found for guid=$guid")
            return false
        }
        profile.server = bestIp
        // enforce production TLS defaults — بدون اینا upload کار نمیکنه
        profile.fingerPrint = "unsafe"
        if (profile.finalMask.isNullOrBlank()) {
            profile.finalMask = "{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["5","94","1"],"delays":["0"],"maxSplit":"0"}},{"type":"fragment","settings":{"packets":"1-1","lengths":["109","1"],"delays":["1"],"maxSplit":"355"}}]}"
        }
        if (profile.cipherSuites.isNullOrBlank()) {
            profile.cipherSuites = "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"
        }
        MmkvManager.encodeServerConfig(guid, profile)
        LogUtil.i(TAG, "applyBestIp: $guid -> server=$bestIp fp=unsafe")
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
            val temp = base.copy(server = ip)
            MmkvManager.encodeServerConfig(tempGuid, temp)
            val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, tempGuid)
            if (!configResult.status) {
                LogUtil.d(TAG, "Config build failed for $ip")
                return@withContext CandidateResult(ip, -1L)
            }
            val latency = CoreNativeManager.measureOutboundDelay(configResult.content, TEST_URL)
            LogUtil.d(TAG, "  $ip -> ${if (latency >= 0) "${latency}ms" else "FAILED"}")
            CandidateResult(ip, latency)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error testing $ip: ${e.message}", e)
            CandidateResult(ip, -1L)
        } finally {
            try { MmkvManager.removeServer(tempGuid) } catch (_: Exception) {}
        }
    }
}
