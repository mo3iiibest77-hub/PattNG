package com.v2ray.ang.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.senpai.CandidateResult
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.IspManager
import com.v2ray.ang.senpai.ScanCallback
import com.v2ray.ang.ui.qsn.data.QsPrefs
import com.v2ray.ang.ui.qsn.domain.DiscoveryPhase
import com.v2ray.ang.ui.qsn.domain.DiscoveryStatus
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class ScanState(val mode: String, val ispName: String)

/**
 * Foreground host for the Cloudflare measurement work.
 *
 * It runs in three modes:
 *
 *  - [MODE_DISCOVERY] runs a discovery itself for the legacy scanner screen and
 *    reports through the historical broadcasts;
 *  - [MODE_HOST] does not run anything. It attaches to a discovery that
 *    [CloudflareScanner] already owns and keeps the process in the foreground
 *    state while it measures, which is what lets the run survive leaving the
 *    screen, backgrounding the app and removal from the recent-tasks list;
 *  - [MODE_SCAN] runs a Stage 2 candidate scan for the legacy screen.
 *
 * The service never becomes a second owner of a run: in host mode it reads
 * [CloudflareScanner.discovery] and nothing else. When the process is killed by
 * the system, a host-mode service is started again with a null intent and
 * resumes the run storage still reports as unfinished. After an explicit
 * force-stop Android starts nothing at all, and the run stays paused until the
 * user opens the app and presses Resume; every completed range is kept either
 * way because progress is persisted per range as it is measured.
 */
class CfScanService : Service() {

    companion object {
        private const val TAG = "CfScanService"

        const val CHANNEL_ID = "cf_scan_channel"
        const val NOTIF_ID = 9001
        const val ACTION_STOP = "com.quietstorm.ng.STOP_SCAN"

        const val EXTRA_MODE = "mode"
        const val EXTRA_GUID = "guid"
        const val EXTRA_ISP = "isp"

        const val MODE_DISCOVERY = "discovery"
        const val MODE_HOST = "host"
        const val MODE_SCAN = "scan"

        const val BROADCAST_PROGRESS = "com.quietstorm.ng.SCAN_PROGRESS"
        const val BROADCAST_DISC_PROGRESS = "com.quietstorm.ng.DISC_PROGRESS"
        const val BROADCAST_FINISH = "com.quietstorm.ng.SCAN_FINISH"
        const val BROADCAST_DISC_FINISH = "com.quietstorm.ng.DISC_FINISH"

        // ── State که Activity میتونه وقتی برمیگرده بخونه ─────────────────
        @Volatile var currentState: ScanState? = null
            private set

        /** Legacy entry point: the service runs the discovery itself. */
        fun startDiscovery(ctx: Context, guid: String, ispName: String) {
            currentState = ScanState(MODE_DISCOVERY, ispName)
            startServiceSafely(
                ctx,
                Intent(ctx, CfScanService::class.java).apply {
                    putExtra(EXTRA_MODE, MODE_DISCOVERY)
                    putExtra(EXTRA_GUID, guid)
                    putExtra(EXTRA_ISP, ispName)
                },
            )
        }

        /**
         * Hosts a discovery that the caller already started in [CloudflareScanner].
         *
         * The caller keeps ownership of the run and of its callbacks; the service
         * only provides the foreground state and the notification, and it resumes
         * the run after a system kill. Returns false when Android refuses to start
         * a foreground service from the current state, in which case the run keeps
         * going in the app process without that protection.
         */
        fun hostDiscovery(ctx: Context, guid: String, ispName: String): Boolean {
            currentState = ScanState(MODE_HOST, ispName)
            QsPrefs.setDiscoveryTarget(ispName, guid)
            return startServiceSafely(
                ctx,
                Intent(ctx, CfScanService::class.java).apply {
                    putExtra(EXTRA_MODE, MODE_HOST)
                    putExtra(EXTRA_GUID, guid)
                    putExtra(EXTRA_ISP, ispName)
                },
            )
        }

        fun startScan(ctx: Context, guid: String, ispName: String) {
            currentState = ScanState(MODE_SCAN, ispName)
            startServiceSafely(
                ctx,
                Intent(ctx, CfScanService::class.java).apply {
                    putExtra(EXTRA_MODE, MODE_SCAN)
                    putExtra(EXTRA_GUID, guid)
                    putExtra(EXTRA_ISP, ispName)
                },
            )
        }

        fun stop(ctx: Context) {
            currentState = null
            CloudflareScanner.cancel()
            CloudflareScanner.stopDiscovery()
            ctx.stopService(Intent(ctx, CfScanService::class.java))
        }

        private fun startServiceSafely(ctx: Context, intent: Intent): Boolean = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            true
        } catch (e: Exception) {
            // Android 12+ refuses a foreground start from the background. The
            // measurement keeps running in the process; only the notification
            // and the kill protection are lost, so this is logged, not fatal.
            LogUtil.e(TAG, "foreground start refused: " + e.javaClass.simpleName)
            false
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mode: String? = null
    private var ispName: String = ""
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif(getString(R.string.qs_discovery_running, 0, 0, 0)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            currentState = null
            when (mode) {
                MODE_SCAN -> CloudflareScanner.cancel()
                else -> CloudflareScanner.stopDiscovery()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val requested = intent?.getStringExtra(EXTRA_MODE)
        if (requested == null) {
            // A null intent is the system starting a sticky service again after
            // it killed the process. Only a run storage still calls unfinished is
            // resumed: a run the user stopped stays stopped.
            return resumeAfterRestart()
        }

        val guid = intent.getStringExtra(EXTRA_GUID) ?: return START_NOT_STICKY
        val isp = intent.getStringExtra(EXTRA_ISP) ?: return START_NOT_STICKY
        mode = requested
        ispName = isp
        currentState = ScanState(requested, isp)

        return when (requested) {
            MODE_DISCOVERY -> {
                runLegacyDiscovery(guid, isp)
                START_NOT_STICKY
            }
            MODE_HOST -> host()
            MODE_SCAN -> {
                runScan(guid, isp)
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    /**
     * Holds the foreground state for a discovery run the app process owns.
     *
     * The caller started the run, so there is nothing to start here: only a
     * system-restart instance resumes work, and it goes through
     * [resumeAfterRestart].
     */
    private fun host(): Int {
        if (!CloudflareScanner.isDiscoveryActive) {
            // Nothing to host: the dataset is complete or the run was refused.
            currentState = null
            stopSelf()
            return START_NOT_STICKY
        }
        observeDiscovery()
        return START_STICKY
    }

    private fun restartRun(guid: String, isp: String): Boolean {
        if (CloudflareScanner.isDiscoveryActive) return false
        val base = MmkvManager.decodeServerConfig(guid)
        if (base == null) {
            LogUtil.e(TAG, "cannot resume discovery: base configuration is gone")
            return false
        }
        return CloudflareScanner.discoverRanges(
            context = this,
            guid = guid,
            ispName = isp,
            background = false,
            force = false,
            onFinish = { },
        )
    }

    private fun resumeAfterRestart(): Int {
        val target = QsPrefs.discoveryTarget() ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val isp = target.first
        val guid = target.second
        val profile = IspManager.getProfile(this, isp)
        val resumable = profile != null && !profile.isComplete &&
            (profile.phase == DiscoveryPhase.Running || profile.phase == DiscoveryPhase.Interrupted)
        if (!resumable && !CloudflareScanner.isDiscoveryActive) {
            // Completed, stopped by the user, or never started: nothing to resume.
            stopSelf()
            return START_NOT_STICKY
        }
        mode = MODE_HOST
        ispName = isp
        currentState = ScanState(MODE_HOST, isp)
        restartRun(guid, isp)
        if (!CloudflareScanner.isDiscoveryActive) {
            currentState = null
            stopSelf()
            return START_NOT_STICKY
        }
        observeDiscovery()
        return START_STICKY
    }

    private fun observeDiscovery() {
        observer?.cancel()
        observer = serviceScope.launch {
            CloudflareScanner.discovery.collect { status ->
                val mine = status.ispName.isEmpty() ||
                    status.ispName.equals(ispName, ignoreCase = true)
                if (mine) {
                    updateNotif(discoveryText(status))
                    if (!status.isActive) {
                        // The run ended (completed, stopped or failed): there is
                        // nothing left to host and the notification must not lie.
                        currentState = null
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun discoveryText(status: DiscoveryStatus): String = when {
        status.isActive -> getString(
            R.string.qs_discovery_running,
            status.done,
            status.total,
            status.qualified,
        )
        status.phase == DiscoveryPhase.Completed ->
            getString(R.string.qs_discovery_completed, status.qualified)
        status.phase == DiscoveryPhase.Paused ->
            getString(R.string.qs_discovery_paused, status.qualified)
        status.phase == DiscoveryPhase.Failed -> getString(R.string.qs_discovery_failed)
        else -> getString(R.string.qs_discovery_stopped)
    }

    private fun runLegacyDiscovery(guid: String, ispName: String) {
        updateNotif("Discovery: " + ispName)
        CloudflareScanner.discoverGoodCidrs(
            context = this,
            ispName = ispName,
            guid = guid,
            onProgress = { done, total, cidr, responded ->
                updateNotif("Discovery " + done + "/" + total + " — good: " + responded)
                sendBroadcast(Intent(BROADCAST_DISC_PROGRESS).apply {
                    putExtra("done", done)
                    putExtra("total", total)
                    putExtra("cidr", cidr)
                    putExtra("responded", responded)
                })
            },
            onFinish = { goodCidrs ->
                currentState = null
                sendBroadcast(Intent(BROADCAST_DISC_FINISH).apply {
                    putExtra("count", goodCidrs.size)
                    putExtra("isp", ispName)
                })
                stopSelf()
            },
        )
    }

    private fun runScan(guid: String, ispName: String) {
        updateNotif("Scan: " + ispName)
        CloudflareScanner.scanForIsp(
            context = this,
            guid = guid,
            ispName = ispName,
            callback = object : ScanCallback {
                override fun onProgress(result: CandidateResult, done: Int, total: Int) {
                    updateNotif("Scan " + done + "/" + total + " — " + result.ip)
                    sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
                        putExtra("done", done); putExtra("total", total)
                        putExtra("ip", result.ip)
                        putExtra("latency", result.latencyMs)
                        putExtra("upload", result.uploadKBps)
                        putExtra("download", result.downloadKBps)
                        putExtra("success", result.isSuccess)
                    })
                }

                override fun onFinish(best: CandidateResult?) {
                    currentState = null
                    sendBroadcast(Intent(BROADCAST_FINISH).apply {
                        putExtra("best_ip", best?.ip)
                        putExtra("best_upload", best?.uploadKBps ?: 0L)
                        putExtra("best_latency", best?.latencyMs ?: -1L)
                    })
                    stopSelf()
                }

                override fun onCancelled() {
                    currentState = null
                    stopSelf()
                }
            },
        )
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "CF Scanner", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Cloudflare measurement in the background" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CfScanService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QuietStorm Scanner")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_name)
            .addAction(Notification.Action.Builder(null, getString(R.string.qs_discovery_stop), stopPi).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))

    override fun onDestroy() {
        observer?.cancel()
        serviceScope.cancel()
        currentState = null
        // Destroying the service must not abort a run it does not own: a
        // host-mode discovery belongs to the scanner and keeps measuring. The
        // explicit stop paths above are the only ones that end a run.
        super.onDestroy()
    }
}