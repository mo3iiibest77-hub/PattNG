package com.v2ray.ang.ui.connect

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.v2ray.ang.ui.qsn.data.QsPrefs
import com.v2ray.ang.ui.qsn.domain.AutoOptimizationPolicy
import java.util.concurrent.TimeUnit

/** Schedules and manages the 2h..3h periodic Smart VPN IP optimizer via WorkManager. */
object AutoOptimizeScheduler {

    const val UNIQUE_PERIODIC_WORK = "qsn_periodic_auto_optimize"
    const val UNIQUE_IMMEDIATE_WORK = "qsn_immediate_auto_optimize"

    fun syncSchedule(context: Context) {
        val enabled = QsPrefs.autoOptimizeEnabled()
        val hours = QsPrefs.autoOptimizeIntervalHours()
        reschedule(context, enabled, hours)
    }

    fun reschedule(context: Context, enabled: Boolean, intervalHours: Int) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            return
        }
        val clampedHours = AutoOptimizationPolicy.clampIntervalHours(intervalHours).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoOptimizeWorker>(clampedHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun runNow(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<AutoOptimizeWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_IMMEDIATE_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
