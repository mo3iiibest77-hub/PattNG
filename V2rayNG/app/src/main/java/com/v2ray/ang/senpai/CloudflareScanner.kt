package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Data types
// ─────────────────────────────────────────────────────────────────────────────

/** Result for one scanned IP. latencyMs == -1 means failed. */
data class CandidateResult(
    val ip: String,
    val latencyMs: Long,
) {
    val isSuccess: Boolean get() = latencyMs >= 0
}

/** Callbacks delivered on Dispatchers.IO — post to main thread if touching UI. */
interface ScanCallback {
    fun onProgress(result: CandidateResult, done: Int, total: Int)
    fun onFinish(best: CandidateResult?)
    fun onCancelled()
}

// ─────────────────────────────────────────────────────────────────────────────
// CloudflareScanner
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Scans a list of Cloudflare IPs using the xray-core that PattNG already
 * bundles — no extra .aar needed.
 *
 * Full flow
 * ─────────
 * 1. Caller supplies a saved profile guid (VLESS+WS+TLS from subscription).
 *    That profile already has wsPath, host, TLS, fragment/finalmask,
 *    cipherSuites, and fingerprint=unsafe set by subscription import.
 * 2. For each candidate IP the scanner:
 *      a. Clones the profile, replaces only `server` with the candidate IP.
 *      b. Saves the clone under a throw-away guid.
 *      c. Calls CoreConfigManager.getV2rayConfig4Speedtest() — same path
 *         RealPingWorkerService uses — to build a lightweight xray JSON config.
 *      d. Calls CoreNativeManager.measureOutboundDelay() — starts a real xray
 *         instance, sends an HTTP request through the tunnel, measures latency,
 *         tears xray down.
 *      e. Removes the temporary profile.
 * 3. After all IPs are tested, finds the one with lowest latency.
 * 4. applyBestAndConnect() writes the winning IP back onto the original
 *    profile so the next VPN connect uses it.
 */
object CloudflareScanner {

    private const val TAG = "CloudflareScanner"

    /**
     * URL used for the real tunnel test.
     * gstatic 204 is lightweight, Cloudflare-reachable, and returns fast.
     */
    private const val TEST_URL = "https://www.gstatic.com/generate_204"

    /** Max parallel tests. Keep low — each test spins up a real xray instance. */
    private const val DEFAULT_CONCURRENCY = 3

    private var scanJob = SupervisorJob()
    private var scanScope = CoroutineScope(scanJob + Dispatchers.IO)

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Start a scan. Replaces any scan already in progress.
     *
     * @param context     Android context.
     * @param guid        GUID of a saved profile whose transport settings
     *                    (WS, TLS, fragment, cipherSuites, fingerprint) will
     *                    be reused. Only server IP is swapped per candidate.
     * @param candidates  Cloudflare IP addresses to test.
     * @param concurrency Parallel workers (default 3).
     * @param callback    Progress and finish events.
     */
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

    /** Cancel a running scan. onCancelled() will be called. */
    fun cancel() {
        scanJob.cancel()
    }

    /**
     * Write the best IP back onto the original profile.
     *
     * Call this from onFinish() when best != null.
     * After this the caller should start the VPN normally — the profile now
     * points at the fastest validated Cloudflare IP.
     *
     * @param guid  The same guid passed to scan().
     * @param bestIp The winning IP from CandidateResult.ip.
     * @return true if the profile was updated successfully.
     */
    fun applyBestIp(guid: String, bestIp: String): Boolean {
        val profile = MmkvManager.decodeServerConfig(guid) ?: run {
            LogUtil.e(TAG, "applyBestIp: profile not found for guid=$guid")
            return false
        }
        val updated = profile.copy(server = bestIp)
        MmkvManager.encodeServerConfig(guid, updated)
        LogUtil.i(TAG, "applyBestIp: updated profile $guid → server=$bestIp")
        return true
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private suspend fun runScan(
        context: Context,
        guid: String,
        candidates: List<String>,
        concurrency: Int,
        callback: ScanCallback,
    ) {
        // Validate the base profile exists before doing any work.
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
        val scope = CoroutineScope(coroutineContext)

        val jobs = candidates.map { ip ->
            scope.launch {
                semaphore.withPermit {
                    val result = testCandidate(context, guid, baseProfile, ip)
                    synchronized(lock) {
                        done++
                        results.add(result)
                    }
                    if (scope.isActive) {
                        callback.onProgress(result, done, total)
                    }
                }
            }
        }

        try {
            joinAll(*jobs.toTypedArray())
            if (scope.isActive) {
                val best = results.filter { it.isSuccess }.minByOrNull { it.latencyMs }
                LogUtil.i(TAG, "Scan complete. Best: ${best?.ip} @ ${best?.latencyMs}ms")
                callback.onFinish(best)
            } else {
                callback.onCancelled()
            }
        } catch (_: CancellationException) {
            callback.onCancelled()
        } catch (e: Exception) {
            LogUtil.e(TAG, "Scan error: ${e.message}", e)
            callback.onFinish(null)
        }
    }

    /**
     * Test one candidate IP against the base profile's transport settings.
     *
     * Saves a temporary profile (base profile + candidate IP), builds a
     * speedtest config, runs a real xray tunnel test, removes the temp profile.
     */
    private suspend fun testCandidate(
        context: Context,
        baseGuid: String,
        baseProfile: com.v2ray.ang.dto.entities.ProfileItem,
        ip: String,
    ): CandidateResult = withContext(Dispatchers.IO) {
        // Unique guid per candidate so parallel tests don't collide.
        val tempGuid = "cfscanner-$baseGuid-${ip.replace('.', '-').replace(':', '-')}"

        return@withContext try {
            // Clone the profile — swap only the server IP.
            val tempProfile = baseProfile.copy(
                guid    = tempGuid,
                server  = ip,
                // wsPath, host, sni, tls settings, fragment, cipherSuites,
                // fingerprint — all inherited from baseProfile unchanged.
            )

            // Persist temporarily so CoreConfigManager can load it by guid.
            MmkvManager.encodeServerConfig(tempGuid, tempProfile)

            // Build a lightweight speedtest config (strips routing/DNS overhead).
            val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, tempGuid)
            if (!configResult.status) {
                LogUtil.d(TAG, "Config build failed for $ip: ${configResult.errorMessage}")
                return@withContext CandidateResult(ip, -1L)
            }

            // Real test: starts xray, sends HTTP through the tunnel, returns ms.
            val latency = CoreNativeManager.measureOutboundDelay(
                configResult.content,
                TEST_URL,
            )

            val status = if (latency >= 0) "${latency}ms" else "FAILED"
            LogUtil.d(TAG, "  $ip → $status")

            CandidateResult(ip, latency)

        } catch (e: Exception) {
            LogUtil.e(TAG, "Error testing $ip: ${e.message}", e)
            CandidateResult(ip, -1L)
        } finally {
            // Always remove the temporary profile — even on exception.
            try { MmkvManager.removeServer(tempGuid) } catch (_: Exception) {}
        }
    }
}
