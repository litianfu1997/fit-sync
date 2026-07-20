package com.fitsync.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fitsync.app.FitSyncApp
import kotlinx.coroutines.runBlocking

/**
 * 开机或覆盖安装后补注册周期同步任务。
 * 与 [FitSyncApp] 冷启动注册互补，减轻国产机清理调度的影响。
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT_POWERON &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        Thread {
            try {
                runBlocking {
                    SyncScheduler.ensureScheduled(context.applicationContext)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
