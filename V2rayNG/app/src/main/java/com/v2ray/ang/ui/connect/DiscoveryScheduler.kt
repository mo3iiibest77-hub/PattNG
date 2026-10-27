package com.v2ray.ang.ui.connect

import android.content.Context
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.DiscoveryOutcome
import com.v2ray.ang.senpai.IspManager
import com.v2ray.ang.ui.qsn.data.QsPrefs
import com.v2ray.ang.ui.qsn.domain.RangeSet
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs Stage 1 discovery in the background after a connection is up.
 *
 * Why this exists: the foreground run stops as soon as five ranges qualify, so
 * the database only grows while the user happens to be connecting. This keeps
 * collecting in the background and writes what it finds to the range database.
 *
 * What it deliberately does not do:
 *  - it never writes a profile, an edge or a selection, so it cannot replace the
 *    address the tunnel is using;
 *  - it never starts or restarts the VPN;
 *  - it runs at most once at a time ([isRunning]) and is skipped entirely while
 *    another scan owns [CloudflareScanner];
 *  - it is not a Service. It lives in an application-scoped coroutine, so it
 *    survives a screen change but dies with the process, which is the right
 *    lifetime for optional background improvement.
 */
object DiscoveryScheduler {

    private const val TAG = "DiscoveryScheduler"

    /** Range set name used by the ISP-aware discovery path. */
    const val DEFAULT_ISP = "default"

    /** How many ranges one background pass tries to qualify before it stops. */
    private const val BACKGROUND_TARGET = 12

    /** Hard cap on one background pass, so it cannot run forever. */
    private const val BACKGROUND_TIMEOUT_MS = 10L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts one background pass for [guid], or does nothing when one is
     * already running or the scanner is busy.
     */
    fun start(context: Context, guid: String, ispName: String = DEFAULT_ISP) {
        if (!running.compareAndSet(false, true)) return
        if (CloudflareScanner.isBusy) {
            running.set(false)
            return
        }
        val app = context.applicationContext
        job = scope.launch {
            try {
                val resumeFrom = IspManager.getProfile(app, ispName)?.lastScannedIndex ?: 0
                val outcome = discover(app, guid, ispName, resumeFrom) ?: return@launch
                if (outcome.cancelled || outcome.qualified.isEmpty()) return@launch
                val merged = RangeSet.merge(QsPrefs.loadQualifiedRanges(), outcome.qualified)
                QsPrefs.saveQualifiedRanges(merged)
                LogUtil.i(
                    TAG,
                    "Background discovery qualified " + outcome.qualified.size +
                        " range(s); database now " + merged.size,
                )
            } catch (e: CancellationException) {
                CloudflareScanner.cancel()
                throw e
            } catch (e: Exception) {
                // Background work must never surface as a user-visible failure.
                LogUtil.e(TAG, "Background discovery failed: " + e.javaClass.simpleName)
            } finally {
                running.set(false)
            }
        }
    }

    /** Cancels the background pass, if any. */
    fun stop() {
        job?.cancel()
        job = null
        running.set(false)
    }

    private suspend fun discover(
        context: Context,
        guid: String,
        ispName: String,
        startIndex: Int,
    ): DiscoveryOutcome? {
        val deferred = CompletableDeferred<DiscoveryOutcome>()
        CloudflareScanner.discoverRanges(
            context = context,
            guid = guid,
            ispName = ispName,
            target = BACKGROUND_TARGET,
            startIndex = startIndex,
        ) { outcome -> deferred.complete(outcome) }
        val outcome = withTimeoutOrNull(BACKGROUND_TIMEOUT_MS) { deferred.await() }
        if (outcome == null) {
            // The pass outlived its budget: stop the scanner so it cannot keep
            // probing in the background forever.
            LogUtil.w(TAG, "Background discovery timed out")
            CloudflareScanner.cancel()
        }
        return outcome
    }
}
