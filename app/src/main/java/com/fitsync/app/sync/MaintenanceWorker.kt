package com.fitsync.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fitsync.app.FitSyncApp
import java.util.concurrent.TimeUnit

class MaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FitSyncApp
        return runCatching {
            val hasMore = app.importService.reparseStoredActivitiesBatch(MAINTENANCE_BATCH_SIZE)
            if (hasMore) {
                MaintenanceScheduler.enqueueContinuation(applicationContext)
            } else {
                app.fitStorageJanitor.run()
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

object MaintenanceScheduler {
    private const val UNIQUE_NAME = "fitsync-local-maintenance"

    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            request(initialDelayMs = 10_000),
        )
    }

    internal fun enqueueContinuation(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(initialDelayMs = 250),
        )
    }

    private fun request(initialDelayMs: Long) = OneTimeWorkRequestBuilder<MaintenanceWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build(),
        )
        .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
        .build()
}

private const val MAINTENANCE_BATCH_SIZE = 25
