package com.v2ray.ang.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CandidateResult
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.ScanCallback

data class ScanState(val mode: String, val ispName: String)

class CfScanService : Service() {

    companion object {
        const val CHANNEL_ID  = "cf_scan_channel"
        const val NOTIF_ID    = 9001
        const val ACTION_STOP = "com.quietstorm.ng.STOP_SCAN"

        const val EXTRA_MODE = "mode"
        const val EXTRA_GUID = "guid"
        const val EXTRA_ISP  = "isp"

        const val BROADCAST_PROGRESS      = "com.quietstorm.ng.SCAN_PROGRESS"
        const val BROADCAST_DISC_PROGRESS = "com.quietstorm.ng.DISC_PROGRESS"
        const val BROADCAST_FINISH        = "com.quietstorm.ng.SCAN_FINISH"
        const val BROADCAST_DISC_FINISH   = "com.quietstorm.ng.DISC_FINISH"

        // ── State که Activity میتونه وقتی برمیگرده بخونه ──────────────────
        @Volatile var currentState: ScanState? = null
            private set

        fun startDiscovery(ctx: Context, guid: String, ispName: String) {
            currentState = ScanState("discovery", ispName)
            ctx.startForegroundService(
                Intent(ctx, CfScanService::class.java).apply {
                    putExtra(EXTRA_MODE, "discovery")
                    putExtra(EXTRA_GUID, guid)
                    putExtra(EXTRA_ISP, ispName)
                }
            )
        }

        fun startScan(ctx: Context, guid: String, ispName: String) {
            currentState = ScanState("scan", ispName)
            ctx.startForegroundService(
                Intent(ctx, CfScanService::class.java).apply {
                    putExtra(EXTRA_MODE, "scan")
                    putExtra(EXTRA_GUID, guid)
                    putExtra(EXTRA_ISP, ispName)
                }
            )
        }

        fun stop(ctx: Context) {
            currentState = null
            CloudflareScanner.cancel()
            ctx.stopService(Intent(ctx, CfScanService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif("در حال اسکن..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            currentState = null
            CloudflareScanner.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        val mode    = intent?.getStringExtra(EXTRA_MODE) ?: return START_NOT_STICKY
        val guid    = intent.getStringExtra(EXTRA_GUID)  ?: return START_NOT_STICKY
        val ispName = intent.getStringExtra(EXTRA_ISP)   ?: return START_NOT_STICKY

        when (mode) {
            "discovery" -> runDiscovery(guid, ispName)
            "scan"      -> runScan(guid, ispName)
        }
        return START_NOT_STICKY
    }

    private fun runDiscovery(guid: String, ispName: String) {
        updateNotif("Discovery: $ispName")
        CloudflareScanner.discoverGoodCidrs(
            context    = this,
            ispName    = ispName,
            guid       = guid,
            onProgress = { done, total, cidr, responded ->
                updateNotif("Discovery $done/$total — خوب: $responded")
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
            }
        )
    }

    private fun runScan(guid: String, ispName: String) {
        updateNotif("Scan: $ispName")
        CloudflareScanner.scanForIsp(
            context  = this,
            guid     = guid,
            ispName  = ispName,
            callback = object : ScanCallback {
                override fun onProgress(result: CandidateResult, done: Int, total: Int) {
                    updateNotif("Scan $done/$total — ${result.ip}")
                    sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
                        putExtra("done", done);   putExtra("total", total)
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
                        putExtra("best_upload",  best?.uploadKBps  ?: 0L)
                        putExtra("best_latency", best?.latencyMs   ?: -1L)
                    })
                    stopSelf()
                }
                override fun onCancelled() {
                    currentState = null
                    stopSelf()
                }
            }
        )
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "CF Scanner", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "اسکن Cloudflare در پس‌زمینه" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CfScanService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QuietStorm Scanner")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_name)
            .addAction(Notification.Action.Builder(null, "توقف", stopPi).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))

    override fun onDestroy() {
        currentState = null
        CloudflareScanner.cancel()
        super.onDestroy()
    }
}
