package com.fitsync.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.fitsync.app.MainActivity
import com.fitsync.app.R
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.web.PlatformWebActivity

class SyncNotificationManager(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                CHANNEL_ID,
                "同步操作提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "登录失效或需要在平台网页中完成操作时提醒" },
                NotificationChannel(
                    PROGRESS_CHANNEL_ID,
                    "后台同步进度",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "同步运行时保持后台任务不中断"
                    setSound(null, null)
                    enableVibration(false)
                },
            ),
        )
    }

    fun foregroundInfo(deliveryOnly: Boolean): ForegroundInfo {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            FOREGROUND_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (deliveryOnly) "正在后台投递 FIT 文件" else "正在后台检查平台活动并处理投递"
        val notification = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("FitSync 正在同步")
            .setContentText(text)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    fun notifyUserAction(jobId: Long, platform: Platform, url: String, message: String) {
        notify(jobId.toInt(), jobId, platform, url, message)
    }

    fun notifyAccountAction(accountId: Long, platform: Platform, url: String, message: String) {
        notify((-10_000L - accountId).toInt(), -1, platform, url, message)
    }

    private fun notify(notificationId: Int, jobId: Long, platform: Platform, url: String, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = PlatformWebActivity.createIntent(context, platform, jobId, url)
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            notificationId,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("${platform.displayName} 需要处理")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancel(jobId: Long) = manager.cancel(jobId.toInt())

    companion object {
        const val CHANNEL_ID = "sync_user_action"
        const val PROGRESS_CHANNEL_ID = "sync_progress"
        private const val FOREGROUND_NOTIFICATION_ID = 9_001
    }
}
