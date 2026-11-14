package com.v2ray.ang.senpai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Periodic 2-hour WorkManager task that evaluates candidates from the active CDN's saved/seed/
 * discovered pools using the untouched September 8 measurement core (`probeCandidateWithCore`),
 * switches the selected edge IP when upload speed meaningfully improves (or the current IP stops
 * passing), and restarts the running daemon cleanly with rollback safety.
 */
class SmartOptimizeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext Result.success()

        val guid = MmkvManager.getSelectServer() ?: return@withContext Result.success()
        val profile = MmkvManager.decodeServerConfig(guid) ?: return@withContext Result.success()
        if (!AutoConfigFilter.isCdnScanEligible(profile)) {
            return@withContext Result.success()
        }

        CoreNativeManager.initCoreEnv(applicationContext)
        OriginDomainStore.rememberIfDomain(guid, profile)

        val cdnType = CdnPingClassifier.detectForProfile(applicationContext, guid, profile).cdnType
            .let { if (it == CdnType.UNKNOWN) CdnType.CLOUDFLARE else it }

        val candidatePoolCidrs = ProviderRangeStore.getScanPoolCidrs(applicationContext, cdnType)
            .shuffled()
            .take(6)

        val candidates = candidatePoolCidrs
            .flatMap { CloudflareScanner.sampleIpsFromCidr(it, 2) }
            .distinct()
            .take(10)

        if (candidates.isEmpty()) return@withContext Result.success()

        val currentIp = profile.server
        val currentBaselineUpload = if (!currentIp.isNullOrBlank()) {
            val res = CloudflareScanner.probeCandidateWithCore(applicationContext, guid, profile, currentIp)
            if (res.isSuccess) res.uploadKBps else 0L
        } else 0L

        val semaphore = Semaphore(3)
        val probed = coroutineScope {
            candidates.map { ip ->
                async {
                    semaphore.withPermit {
                        CloudflareScanner.probeCandidateWithCore(applicationContext, guid, profile, ip)
                    }
                }
            }.awaitAll()
        }

        val best = probed.filter { it.isSuccess }.maxByOrNull { it.uploadKBps }
        MmkvManager.encodeSettings(PREF_LAST_OPTIMIZE_AT, System.currentTimeMillis())

        if (!SmartConnectPolicy.shouldSwitchEdgeIp(currentIp, currentBaselineUpload, best)) {
            LogUtil.i(TAG, "2h optimize: keeping current IP=$currentIp baseline=${currentBaselineUpload}KB/s")
            return@withContext Result.success()
        }

        val winner = best ?: return@withContext Result.success()
        val previousServer = profile.server
        CloudflareScanner.applyBestIp(guid, winner.ip)
        MmkvManager.encodeSettings(PREF_LAST_OPTIMIZE_IP, winner.ip)
        MmkvManager.encodeSettings(PREF_LAST_OPTIMIZE_UPLOAD, winner.uploadKBps)
        LogUtil.i(TAG, "2h optimize: switching $previousServer -> ${winner.ip} (${winner.uploadKBps}KB/s)")

        val restarted = restartDaemonIfRunning(applicationContext)
        if (!restarted && !previousServer.isNullOrBlank()) {
            // Daemon was not running cleanly after switch? Leave previous if service signaled error.
            LogUtil.w(TAG, "2h optimize: daemon not active, updated profile IP without starting stopped VPN")
        }
        Result.success()
    }

    private suspend fun restartDaemonIfRunning(context: Context): Boolean =
        suspendCoroutine { cont ->
            MessageHelper.sendMsg2ServiceForResult(context, AppConfig.MSG_STATE_RESTART, "") { handled ->
                cont.resume(handled)
            }
        }

    companion object {
        private const val TAG = "SmartOptimizeWorker"
        private const val WORK_NAME = "qsn_smart_2h_optimize"
        const val PREF_OPTIMIZE_ENABLED = "qsn_auto_2h_optimize_enabled"
        const val PREF_LAST_OPTIMIZE_AT = "qsn_last_optimize_at"
        const val PREF_LAST_OPTIMIZE_IP = "qsn_last_optimize_ip"
        const val PREF_LAST_OPTIMIZE_UPLOAD = "qsn_last_optimize_upload"

        fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(PREF_OPTIMIZE_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            MmkvManager.encodeSettings(PREF_OPTIMIZE_ENABLED, enabled)
            if (enabled) schedule(context) else cancel(context)
        }

        fun schedule(context: Context) {
            if (!isEnabled()) return
            val request = PeriodicWorkRequestBuilder<SmartOptimizeWorker>(
                SmartConnectPolicy.OPTIMIZE_INTERVAL_HOURS,
                TimeUnit.HOURS
            ).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
