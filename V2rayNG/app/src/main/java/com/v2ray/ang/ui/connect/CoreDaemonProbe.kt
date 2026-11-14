package com.v2ray.ang.ui.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared daemon state probe and restart verifier used by both
 * [ConnectionOrchestrator] and [AutoOptimizeWorker] without loading native core
 * libraries into the UI process.
 */
internal object CoreDaemonProbe {

    enum class Signal { RUNNING, NOT_RUNNING, START_FAILED, STOPPED }

    private const val PROBE_TIMEOUT_MS = 1_500L
    private const val RUNNING_TIMEOUT_MS = 25_000L

    fun isDaemonRunningBlocking(context: Context): Boolean = kotlinx.coroutines.runBlocking {
        probeSignal(context) == Signal.RUNNING
    }

    suspend fun probeSignal(context: Context): Signal? {
        val signals = Channel<Signal>(Channel.UNLIMITED)
        val receiver = createReceiver(signals)
        return try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
                Utils.receiverFlags(),
            )
            MessageHelper.sendMsg2Service(context, AppConfig.MSG_REGISTER_CLIENT, "")
            withTimeoutOrNull(PROBE_TIMEOUT_MS) { signals.receiveCatching().getOrNull() }
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            signals.close()
        }
    }

    /** Restarts an already running daemon and waits for [AppConfig.MSG_STATE_RUNNING]. */
    suspend fun restartRunningAndWait(context: Context, timeoutMs: Long = RUNNING_TIMEOUT_MS): Boolean {
        val signals = Channel<Signal>(Channel.UNLIMITED)
        val receiver = createReceiver(signals)
        return try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
                Utils.receiverFlags(),
            )
            LauncherManager.restartService(context)
            var connected = false
            withTimeoutOrNull(timeoutMs) {
                for (signal in signals) {
                    when (signal) {
                        Signal.RUNNING -> {
                            connected = true
                            return@withTimeoutOrNull
                        }
                        Signal.START_FAILED -> return@withTimeoutOrNull
                        Signal.NOT_RUNNING, Signal.STOPPED -> Unit
                    }
                }
            }
            connected
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            signals.close()
        }
    }

    private fun createReceiver(signals: Channel<Signal>): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING,
                AppConfig.MSG_STATE_START_SUCCESS -> signals.trySend(Signal.RUNNING)
                AppConfig.MSG_STATE_START_FAILURE -> signals.trySend(Signal.START_FAILED)
                AppConfig.MSG_STATE_STOP_SUCCESS -> signals.trySend(Signal.STOPPED)
                AppConfig.MSG_STATE_NOT_RUNNING -> signals.trySend(Signal.NOT_RUNNING)
            }
        }
    }
}
