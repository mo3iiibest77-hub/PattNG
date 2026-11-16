package com.v2ray.ang.senpai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Guaranteed background WorkManager retry task that flushes any offline pending server logout
 * once device connectivity recovers (for example right after local VPN teardown switches network).
 */
class PendingLogoutWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (LicenseSessionStore.getPendingLogout() == null) {
            return@withContext Result.success()
        }
        val flushed = LicenseBackendClient.flushPendingLogoutAndReport()
        if (flushed || LicenseSessionStore.getPendingLogout() == null) {
            Result.success()
        } else if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
            Result.failure()
        } else {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "np_pending_logout_sync"
        private const val MAX_RETRY_ATTEMPTS = 12

        fun schedule(context: Context) {
            runCatching {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = OneTimeWorkRequestBuilder<PendingLogoutWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }
        }
    }
}
