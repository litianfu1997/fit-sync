package com.fitsync.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
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
            app.syncCoordinator.runSync(
                force = inputData.getBoolean(KEY_FORCE, false),
                manualSelection = inputData.toManualSyncSelection(),
            )
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
    private const val AUTO_NOW_NAME = "fitsync-auto-sync-now"
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
    suspend fun ensureScheduled(context: Context): Boolean {
        val appContext = context.applicationContext
        val settings = runCatching {
            (appContext as? FitSyncApp)?.database?.appSettingsDao()?.get()
        }.getOrNull()
        val enabled = settings?.autoSyncEnabled != false
        schedule(
            appContext,
            enabled = enabled,
            intervalMinutes = settings?.syncIntervalMinutes ?: 15,
        )
        return enabled
    }

    /**
     * 冷启动或平台登录恢复后立即执行一次完整自动同步。
     * 与手动同步使用不同的唯一任务链，避免覆盖用户正在执行的一次性同步。
     */
    fun enqueueAutoNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            AUTO_NOW_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            oneTimeRequest(force = false, deliveryOnly = false, expedited = true),
        )
    }

    fun enqueueNow(context: Context, selection: ManualSyncSelection? = null) {
        val request = oneTimeRequest(
            force = true,
            deliveryOnly = false,
            expedited = true,
            manualSelection = selection,
        )
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MANUAL_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueDeliveryNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DELIVERY_NAME,
            // 用户显式重试必须越过已有的退避/延迟续跑；本次 Worker 结束后会按剩余任务重新排期。
            ExistingWorkPolicy.REPLACE,
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
        manualSelection: ManualSyncSelection? = null,
    ) = OneTimeWorkRequestBuilder<SyncWorker>()
        .setInputData(
            Data.Builder()
                .putBoolean(KEY_FORCE, force)
                .putBoolean(KEY_DELIVERY_ONLY, deliveryOnly)
                .apply {
                    if (manualSelection != null) putManualSyncSelection(manualSelection)
                }
                .build(),
        )
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
