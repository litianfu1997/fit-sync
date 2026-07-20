package com.fitsync.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
            SyncScheduler.enqueueContinuation(applicationContext, summary.nextDeliveryDelayMs ?: 0)
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
    private const val DELIVERY_NAME = "fitsync-delivery-sync"
    const val MANUAL_NAME = "fitsync-manual-sync"
    const val PERIODIC_WORK_NAME = PERIODIC_NAME

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

    /**
     * 从 Room 读取偏好并注册/取消周期任务。供冷启动与开机广播共用。
     */
    suspend fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        val settings = runCatching {
            (appContext as? FitSyncApp)?.database?.appSettingsDao()?.get()
        }.getOrNull()
        schedule(
            appContext,
            enabled = settings?.autoSyncEnabled != false,
            intervalMinutes = settings?.syncIntervalMinutes ?: 15,
        )
    }

    fun enqueueNow(context: Context) {
        val request = oneTimeRequest(force = true, deliveryOnly = false, expedited = true)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MANUAL_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueDeliveryNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DELIVERY_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            oneTimeRequest(force = false, deliveryOnly = true, expedited = true),
        )
    }

    internal fun enqueueContinuation(context: Context, delayMs: Long = 0) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DELIVERY_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            oneTimeRequest(force = false, deliveryOnly = true, delayMs = delayMs, expedited = delayMs <= 0),
        )
    }

    private fun oneTimeRequest(
        force: Boolean,
        deliveryOnly: Boolean,
        delayMs: Long = 0,
        expedited: Boolean = false,
    ) = OneTimeWorkRequestBuilder<SyncWorker>()
        .setInputData(workDataOf(KEY_FORCE to force, KEY_DELIVERY_ONLY to deliveryOnly))
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        .apply {
            if (expedited && delayMs <= 0) {
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
        }
        .build()
}
