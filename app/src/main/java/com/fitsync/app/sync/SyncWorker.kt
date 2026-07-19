package com.fitsync.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.workDataOf
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fitsync.app.FitSyncApp
import java.util.concurrent.TimeUnit

private const val KEY_FORCE = "force"
private const val KEY_DELIVERY_ONLY = "delivery_only"

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val app = applicationContext as FitSyncApp
        val deliveryOnly = inputData.getBoolean(KEY_DELIVERY_ONLY, false)
        setForeground(app.syncNotificationManager.foregroundInfo(deliveryOnly))
        val summary = if (deliveryOnly) {
            app.syncCoordinator.runDeliveryBatch()
        } else {
            app.syncCoordinator.runSync(force = inputData.getBoolean(KEY_FORCE, false))
        }
        if (summary.hasMoreDeliveries) {
            SyncScheduler.enqueueContinuation(applicationContext)
        }
        Result.success(summary.toWorkData(deliveryOnly))
    }.getOrElse { error ->
        if (inputData.getBoolean(KEY_FORCE, false)) {
            Result.failure(syncFailureData(error.message ?: "后台同步发生异常"))
        } else {
            Result.retry()
        }
    }
}

object SyncScheduler {
    private const val PERIODIC_NAME = "fitsync-periodic-sync"
    const val MANUAL_NAME = "fitsync-manual-sync"

    fun schedule(context: Context, enabled: Boolean, intervalMinutes: Int = 15) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes.coerceAtLeast(15).toLong(),
            TimeUnit.MINUTES,
        ).setConstraints(constraints).build()
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun enqueueNow(context: Context) {
        val request = oneTimeRequest(force = true, deliveryOnly = false)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MANUAL_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueDeliveryNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            oneTimeRequest(force = false, deliveryOnly = true),
        )
    }

    internal fun enqueueContinuation(context: Context) {
        enqueueDeliveryNow(context)
    }

    private fun oneTimeRequest(force: Boolean, deliveryOnly: Boolean) =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(KEY_FORCE to force, KEY_DELIVERY_ONLY to deliveryOnly))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
}
