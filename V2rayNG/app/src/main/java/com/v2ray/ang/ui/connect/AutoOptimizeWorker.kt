package com.v2ray.ang.ui.connect

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.senpai.CandidateResult
import com.v2ray.ang.senpai.CloudFrontScanner
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.ui.qsn.data.IpHealthChecker
import com.v2ray.ang.ui.qsn.data.ProviderRangeStore
import com.v2ray.ang.ui.qsn.data.QsPrefs
import com.v2ray.ang.ui.qsn.domain.AutoOptimizationPolicy
import com.v2ray.ang.ui.qsn.domain.CurrentEdgeHealth
import com.v2ray.ang.ui.qsn.domain.EdgeApplication
import com.v2ray.ang.ui.qsn.domain.EdgeProvider
import com.v2ray.ang.ui.qsn.domain.EdgeRollbackSnapshot
import com.v2ray.ang.ui.qsn.domain.IpSelection
import com.v2ray.ang.ui.qsn.domain.MeasuredEdgeCandidate
import com.v2ray.ang.ui.qsn.domain.OptimizationDecision
import com.v2ray.ang.ui.qsn.domain.ReferenceCandidateSelector
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic (2h..3h) Smart VPN background optimizer.
 *
 * Evaluates the current active edge and compares against candidates sampled from
 * stored valid ranges (respecting the September 8 `c489a5aa` latency + mandatory
 * real-traffic speedtest gate). Switches only when [AutoOptimizationPolicy]
 * determines the current IP is degraded or a candidate provides meaningful speed
 * gain, updates eligible configs cleanly preserving SNI/Host/TLS/Path/UUID/Port,
 * reloads the running Xray core when connected, and automatically rolls back to
 * the previous working IP if verification fails.
 */
class AutoOptimizeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!QsPrefs.autoOptimizeEnabled()) {
            return@withContext Result.success()
        }
        val selectedGuid = MmkvManager.getSelectServer() ?: QsPrefs.pinnedGuid()
        if (selectedGuid.isNullOrBlank()) {
            QsPrefs.markAutoOptimized(now, "skipped:no-config")
            return@withContext Result.success()
        }
        val profile = MmkvManager.decodeServerConfig(selectedGuid)
        if (profile == null || !AutoConfigFilter.isEligible(profile)) {
            QsPrefs.markAutoOptimized(now, "skipped:ineligible")
            return@withContext Result.success()
        }
        if (!CoreNativeManager.isNativeAvailable()) {
            QsPrefs.markAutoOptimized(now, "skipped:native-unavailable")
            return@withContext Result.success()
        }

        val provider = QsPrefs.selectedProvider()
        val cidrs = ProviderRangeStore.snapshot(provider).enabledScanCidrs.ifEmpty {
            ProviderRangeStore.snapshot(EdgeProvider.CLOUDFLARE).enabledScanCidrs
        }
        if (cidrs.isEmpty()) {
            QsPrefs.markAutoOptimized(now, "skipped:no-ranges")
            return@withContext Result.success()
        }

        val currentSelection = QsPrefs.loadSelection()
        val currentIp = profile.qsEdgeIp?.takeIf { it.isNotBlank() } ?: currentSelection?.ip
        val port = profile.serverPort?.toIntOrNull() ?: 443
        val currentHealthy = !currentIp.isNullOrBlank() &&
            IpHealthChecker.isHealthy(currentIp, port, profile.sni)

        var currentMeasuredUpload = currentSelection?.uploadKBps ?: 0L
        var currentLatency = currentSelection?.latencyMs ?: -1L
        var retestedHealthy = currentHealthy
        if (!currentIp.isNullOrBlank() && currentHealthy) {
            val probe = CloudflareScanner.probeCandidate(
                applicationContext,
                selectedGuid,
                profile,
                currentIp,
                "optcur",
            )
            if (probe.isSuccess && probe.hasTraffic) {
                currentMeasuredUpload = probe.uploadKBps
                currentLatency = probe.latencyMs
            } else {
                retestedHealthy = false
            }
        }

        val sampleIps = CloudflareScanner.candidatesFromRanges(cidrs, ipsPerCidr = 2, maxCandidates = 16)
        val measured = ArrayList<CandidateResult>(sampleIps.size)
        for (ip in sampleIps) {
            if (isStopped) break
            val res = CloudflareScanner.probeCandidate(
                applicationContext,
                selectedGuid,
                profile,
                ip,
                if (provider == EdgeProvider.CLOUDFRONT) "optcf" else "opt",
            )
            measured.add(res)
        }
        val best = ReferenceCandidateSelector.selectBest(measured)
        val candidateModel = best?.let {
            MeasuredEdgeCandidate(
                ip = it.ip,
                passedGate = it.isSuccess && it.hasTraffic,
                uploadKBps = it.uploadKBps,
                latencyMs = it.latencyMs,
            )
        }

        val decision = AutoOptimizationPolicy.evaluate(
            current = CurrentEdgeHealth(
                ip = currentIp,
                healthy = retestedHealthy,
                uploadKBps = currentMeasuredUpload,
                latencyMs = currentLatency,
            ),
            candidate = candidateModel,
        )

        when (decision) {
            is OptimizationDecision.KeepCurrent -> {
                val finished = System.currentTimeMillis()
                QsPrefs.markScanned(finished)
                QsPrefs.markAutoOptimized(finished, "kept:${decision.reason.name}:${currentIp.orEmpty()}")
                LogUtil.i(TAG, "AutoOptimize kept current IP=" + currentIp + " reason=" + decision.reason)
                Result.success()
            }

            is OptimizationDecision.Switch -> {
                val targetIp = best!!.ip
                val snapshot = applySwitchWithSnapshot(selectedGuid, profile, targetIp)
                val wasRunning = CoreDaemonProbe.isDaemonRunningBlocking(applicationContext)
                val switchedOk = if (wasRunning) {
                    CoreDaemonProbe.restartRunningAndWait(applicationContext)
                } else {
                    IpHealthChecker.isHealthy(targetIp, port, profile.sni)
                }

                if (!switchedOk) {
                    rollbackSwitch(selectedGuid, snapshot, wasRunning)
                    val finished = System.currentTimeMillis()
                    QsPrefs.markAutoOptimized(finished, "rolled_back:${currentIp.orEmpty()}")
                    LogUtil.w(TAG, "AutoOptimize rolled back from $targetIp to $currentIp")
                    return@withContext Result.success()
                }

                val finished = System.currentTimeMillis()
                val baseFingerprint = currentSelection?.fingerprint.orEmpty()
                QsPrefs.saveSelection(
                    IpSelection(
                        fingerprint = baseFingerprint,
                        ip = targetIp,
                        score = ReferenceCandidateSelector.scoreForDisplay(listOf(best)).first().score,
                        latencyMs = best.latencyMs.takeIf { it >= 0L },
                        downloadKBps = best.downloadKBps,
                        uploadKBps = best.uploadKBps,
                        confidence = 100,
                        validatedAt = finished,
                    ),
                )
                QsPrefs.markScanned(finished)
                QsPrefs.markAutoOptimized(
                    finished,
                    "switched:${decision.reason.name}:$targetIp:${best.uploadKBps}KB/s",
                )
                LogUtil.i(
                    TAG,
                    "AutoOptimize switched IP=$targetIp up=${best.uploadKBps}KB/s reason=${decision.reason}",
                )
                Result.success()
            }
        }
    }

    private fun applySwitchWithSnapshot(
        selectedGuid: String,
        selected: ProfileItem,
        newIp: String,
    ): EdgeRollbackSnapshot {
        val prevSelection = QsPrefs.loadSelection()
        val subId = selected.subscriptionId
        val guids = if (subId.isNotBlank() && subId != AppConfig.DEFAULT_SUBSCRIPTION_ID) {
            MmkvManager.decodeServerList(subId).distinct()
        } else {
            listOf(selectedGuid)
        }
        val targets = guids.mapNotNull { guid ->
            val p = if (guid == selectedGuid) selected else MmkvManager.decodeServerConfig(guid)
            p?.let { EdgeApplication.Target(guid, it) }
        }.filter { AutoConfigFilter.isEligible(it.profile) }

        val prevEdges = targets.associate { it.guid to it.profile.qsEdgeIp }
        targets.forEach { target ->
            target.profile.qsEdgeIp = newIp
            MmkvManager.encodeServerConfig(target.guid, target.profile)
        }
        return EdgeRollbackSnapshot(prevSelection, prevEdges)
    }

    private suspend fun rollbackSwitch(
        selectedGuid: String,
        snapshot: EdgeRollbackSnapshot,
        restartCore: Boolean,
    ) {
        snapshot.previousEdgesByGuid.forEach { (guid, prevIp) ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@forEach
            profile.qsEdgeIp = prevIp
            MmkvManager.encodeServerConfig(guid, profile)
        }
        if (snapshot.previousSelection != null) {
            QsPrefs.saveSelection(snapshot.previousSelection)
        }
        if (restartCore) {
            MmkvManager.setSelectServer(selectedGuid)
            CoreDaemonProbe.restartRunningAndWait(applicationContext)
        }
    }

    companion object {
        private const val TAG = "AutoOptimizeWorker"
    }
}
