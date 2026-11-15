package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.qsn3d.QuietStormActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.v2ray.ang.extension.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2
    private const val NOTIFICATION_ICON_THRESHOLD = 3000
    private const val QUERY_INTERVAL_MS = 3000L

    private var lastQueryTime = 0L
    private var mBuilder: NotificationCompat.Builder? = null
    private var speedNotificationJob: Job? = null
    private var mNotificationManager: NotificationManager? = null
    private var activeServerLabel: String = ""
    private var stopPendingIntent: PendingIntent? = null
    private var restartPendingIntent: PendingIntent? = null
    @Volatile
    private var cachedBrandIcon: Bitmap? = null

    private fun getBrandLargeIcon(context: Context): Bitmap? {
        cachedBrandIcon?.let { return it }
        return runCatching {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_round)
                ?: BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        }.getOrNull()?.also { cachedBrandIcon = it }
    }

    /**
     * Starts the speed notification.
     * @param currentConfig The current profile configuration.
     */
    fun startSpeedNotification() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED, true) != true) return
        if (speedNotificationJob != null || CoreServiceManager.isRunning() == false) return

        var lastZeroSpeed = false

        speedNotificationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                lastZeroSpeed = updateSpeedNotificationOnce(lastZeroSpeed)
                delay(QUERY_INTERVAL_MS)
            }
        }
    }

    /**
     * Shows the notification.
     * @param currentConfig The current profile configuration.
     */
    fun showNotification(currentConfig: ProfileItem?) {
        val service = getService() ?: return

        // Reset last query time to avoid querying stats too soon after showing the notification
        lastQueryTime = System.currentTimeMillis()

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent = Intent(service, QuietStormActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(service, NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent, flags)

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        stopV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        stopV2RayIntent.putExtra("key", AppConfig.MSG_STATE_STOP)
        val stopV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent, flags)

        val restartV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        restartV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        restartV2RayIntent.putExtra("key", AppConfig.MSG_STATE_RESTART)
        val restartV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_RESTART_V2RAY, restartV2RayIntent, flags)

        stopPendingIntent = stopV2RayPendingIntent
        restartPendingIntent = restartV2RayPendingIntent

        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            } else {
                // If earlier version channel ID is not used
                // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                ""
            }

        val configTitle = currentConfig?.remarks?.takeIf { it.isNotBlank() }
        activeServerLabel = configTitle ?: service.getString(R.string.novapulse_notif_subtext)
        val fullTitle = if (configTitle != null) {
            "${service.getString(R.string.app_name)} • $configTitle"
        } else {
            service.getString(R.string.app_name)
        }

        mBuilder = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setLargeIcon(getBrandLargeIcon(service))
            .setColor(Color.parseColor("#00E5FF"))
            .setContentTitle(fullTitle)
            .setSubText(service.getString(R.string.novapulse_notif_subtext))
            .setContentText(service.getString(R.string.novapulse_notif_connected_ready))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)

        applyCustomNotificationViews(service, downSpeed = "0 B/s", upSpeed = "0 B/s", directLine = null)
        service.startForeground(NOTIFICATION_ID, mBuilder?.build())
    }

    /**
     * Fulfills or refreshes the foreground-service contract before a start command can
     * return early. A duplicate startForegroundService call still requires the service
     * to enter foreground state promptly, even when the core is already running.
     */
    fun ensureForeground() {
        val service = getService() ?: return
        val notification = mBuilder?.build()
        if (notification == null) showNotification(null) else service.startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Cancels the notification.
     */
    fun cancelNotification() {
        val service = getService() ?: return
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)

        mBuilder = null
        speedNotificationJob?.cancel()
        speedNotificationJob = null
        mNotificationManager = null
    }

    /**
     * Stops the speed notification.
     */
    fun stopSpeedNotification() {
        speedNotificationJob?.let {
            it.cancel()
            speedNotificationJob = null
            updateNotification("0 B/s", "0 B/s", null, "", 0, 0)
        }
    }

    /**
     * Creates a notification channel for Android O and above.
     * @return The channel ID.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val svc = getService()
        val channelName = svc?.getString(R.string.novapulse_notif_channel_name) ?: AppConfig.RAY_NG_CHANNEL_NAME
        // Foreground-service notifications must remain visible; LOW is silent but valid.
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
            description = svc?.getString(R.string.novapulse_notif_channel_desc)
            lightColor = Color.parseColor("#00E5FF")
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getNotificationManager()?.createNotificationChannel(chan)
        return channelId
    }

    /**
     * Updates the notification with the given content text and traffic data.
     * @param contentText The content text.
     * @param proxyTraffic The proxy traffic.
     * @param directTraffic The direct traffic.
     */
    private fun applyCustomNotificationViews(
        service: Service,
        downSpeed: String,
        upSpeed: String,
        directLine: String?,
    ) {
        val builder = mBuilder ?: return
        val appName = service.getString(R.string.app_name)
        val serverName = activeServerLabel.ifBlank { service.getString(R.string.novapulse_notif_subtext) }

        val collapsed = RemoteViews(service.packageName, R.layout.notif_novapulse_collapsed).apply {
            setTextViewText(R.id.np_notif_server_title, "$appName • $serverName")
            setTextViewText(R.id.np_notif_down_compact, "↓ $downSpeed")
            setTextViewText(R.id.np_notif_up_compact, "↑ $upSpeed")
            stopPendingIntent?.let { setOnClickPendingIntent(R.id.np_notif_btn_stop_compact, it) }
        }

        val expanded = RemoteViews(service.packageName, R.layout.notif_novapulse_expanded).apply {
            setTextViewText(R.id.np_notif_exp_title, appName)
            setTextViewText(R.id.np_notif_exp_subtitle, serverName)
            setTextViewText(R.id.np_notif_exp_down_val, downSpeed)
            setTextViewText(R.id.np_notif_exp_up_val, upSpeed)
            if (directLine.isNullOrBlank()) {
                setViewVisibility(R.id.np_notif_exp_direct, View.GONE)
            } else {
                setViewVisibility(R.id.np_notif_exp_direct, View.VISIBLE)
                setTextViewText(R.id.np_notif_exp_direct, directLine)
            }
            stopPendingIntent?.let { setOnClickPendingIntent(R.id.np_notif_btn_stop, it) }
            restartPendingIntent?.let { setOnClickPendingIntent(R.id.np_notif_btn_reconnect, it) }
        }

        builder.setCustomContentView(collapsed)
        builder.setCustomBigContentView(expanded)
        builder.setCustomHeadsUpContentView(collapsed)
    }

    private fun updateNotification(
        downSpeed: String,
        upSpeed: String,
        directLine: String?,
        contentText: String?,
        proxyTraffic: Long,
        directTraffic: Long,
    ) {
        val svc = getService() ?: return
        if (mBuilder != null) {
            if (proxyTraffic < NOTIFICATION_ICON_THRESHOLD && directTraffic < NOTIFICATION_ICON_THRESHOLD) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_name)
            } else if (proxyTraffic > directTraffic) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_proxy)
            } else {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_direct)
            }
            mBuilder?.setContentText(contentText)
            applyCustomNotificationViews(svc, downSpeed, upSpeed, directLine)
            getNotificationManager()?.notify(NOTIFICATION_ID, mBuilder?.build())
        }
    }

    /**
     * Gets the notification manager.
     * @return The notification manager.
     */
    private fun getNotificationManager(): NotificationManager? {
        if (mNotificationManager == null) {
            val service = getService() ?: return null
            mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager
    }

    /**
     * Updates the speed notification once.
     * Queries traffic stats, separates proxy and direct, and updates the notification.
     * @param lastZeroSpeed The previous zero speed state.
     * @return The current zero speed state.
     */
    private fun updateSpeedNotificationOnce(lastZeroSpeed: Boolean): Boolean {
        val queryTime = System.currentTimeMillis()
        val sinceLastQueryIn = (queryTime - lastQueryTime)

        // If the query interval is too short, skip this round to avoid excessive CPU usage
        if (sinceLastQueryIn < QUERY_INTERVAL_MS) {
            LogUtil.w(AppConfig.TAG, "Query interval too short: ${sinceLastQueryIn}ms, skipping")
            lastQueryTime = queryTime
            return lastZeroSpeed
        }
        val sinceLastQueryInSeconds = sinceLastQueryIn / 1000.0

        var proxyUplink = 0L
        var proxyDownlink = 0L
        var directUplink = 0L
        var directDownlink = 0L

        CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
            when {
                stat.tag == AppConfig.TAG_DIRECT -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> directUplink += stat.value
                        AppConfig.DOWNLINK -> directDownlink += stat.value
                    }
                }

                // Accumulate stats for all proxy outbounds (including custom subscription tags)
                stat.tag != AppConfig.TAG_BLOCKED -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> proxyUplink += stat.value
                        AppConfig.DOWNLINK -> proxyDownlink += stat.value
                    }
                }
            }
        }

        val proxyTotal = proxyUplink + proxyDownlink
        val directTotal = directUplink + directDownlink
        val proxyUpBps = (proxyUplink / sinceLastQueryInSeconds).toLong().coerceAtLeast(0L)
        val proxyDownBps = (proxyDownlink / sinceLastQueryInSeconds).toLong().coerceAtLeast(0L)
        val directUpBps = (directUplink / sinceLastQueryInSeconds).toLong().coerceAtLeast(0L)
        val directDownBps = (directDownlink / sinceLastQueryInSeconds).toLong().coerceAtLeast(0L)
        getService()?.let { svc ->
            MessageHelper.sendMsg2UI(
                svc,
                AppConfig.MSG_LIVE_SPEED_UPDATE,
                "$proxyUpBps|$proxyDownBps|$directUpBps|$directDownBps",
            )
        }
        val zeroSpeed = proxyTotal + directTotal == 0L
        if (!zeroSpeed || !lastZeroSpeed) {
            val svc = getService()
            val tunnelLabel = svc?.getString(R.string.novapulse_notif_tunnel_label) ?: "Tunnel"
            val directLabel = svc?.getString(R.string.novapulse_notif_direct_label) ?: "Direct"
            val downFmt = proxyDownBps.toSpeedString()
            val upFmt = proxyUpBps.toSpeedString()
            val directLine = if (directTotal > 0L) {
                "$directLabel:  ↓ ${directDownBps.toSpeedString()}   ↑ ${directUpBps.toSpeedString()}"
            } else {
                null
            }
            val text = buildString {
                append("$tunnelLabel:  ↓ $downFmt   ↑ $upFmt")
                if (directLine != null) {
                    append("\n$directLine")
                }
            }
            updateNotification(downFmt, upFmt, directLine, text, proxyTotal, directTotal)
        }
        lastQueryTime = queryTime
        return zeroSpeed
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    private fun getService(): Service? {
        return CoreServiceManager.serviceControl?.get()?.getService()
    }
}
