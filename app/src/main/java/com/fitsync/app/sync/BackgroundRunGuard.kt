package com.fitsync.app.sync

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 检测系统省电 / 后台限制，并引导用户放开后台运行。
 * 国产机「智能后台」差异大，OEM Intent 均为 best-effort，失败回落到应用详情。
 */
class BackgroundRunGuard(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun evaluate(autoSyncEnabled: Boolean = true): BackgroundRunStatus {
        val brand = resolveOemBrand(Build.MANUFACTURER, Build.BRAND)
        val batteryIgnored = isIgnoringBatteryOptimizations()
        val backgroundRestricted = isBackgroundRestricted()
        val notificationsGranted = areNotificationsGranted()
        return BackgroundRunStatus(
            batteryOptimizationIgnored = batteryIgnored,
            isBackgroundRestricted = backgroundRestricted,
            notificationsGranted = notificationsGranted,
            brand = brand,
            needsAttention = computeNeedsAttention(
                autoSyncEnabled = autoSyncEnabled,
                batteryOptimizationIgnored = batteryIgnored,
                isBackgroundRestricted = backgroundRestricted,
                notificationsGranted = notificationsGranted,
            ),
        )
    }

    fun isBannerDismissedRecently(nowMs: Long = System.currentTimeMillis()): Boolean {
        val dismissedAt = prefs.getLong(KEY_BANNER_DISMISSED_AT, 0L)
        if (dismissedAt <= 0L) return false
        return nowMs - dismissedAt < BANNER_COOLDOWN_MS
    }

    fun dismissBanner(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_BANNER_DISMISSED_AT, nowMs).apply()
    }

    fun isBatteryPromptDone(): Boolean = prefs.getBoolean(KEY_BATTERY_PROMPT_DONE, false)

    fun markBatteryPromptDone() {
        prefs.edit().putBoolean(KEY_BATTERY_PROMPT_DONE, true).apply()
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isBackgroundRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        return am.isBackgroundRestricted
    }

    fun areNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 优先一键申请忽略电池优化；已豁免则尝试 OEM 自启动/后台页，最后应用详情。
     * @return 是否成功拉起某个系统界面
     */
    fun openBackgroundSettings(preferBatteryRequest: Boolean = true): Boolean {
        if (preferBatteryRequest && !isIgnoringBatteryOptimizations()) {
            if (requestIgnoreBatteryOptimizations()) return true
        }
        if (openOemBackgroundSettings()) return true
        if (!isIgnoringBatteryOptimizations() && openBatteryOptimizationList()) return true
        return openAppDetails()
    }

    fun requestIgnoreBatteryOptimizations(): Boolean {
        if (isIgnoringBatteryOptimizations()) return false
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startIfResolvable(intent)
    }

    fun openBatteryOptimizationList(): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startIfResolvable(intent)
    }

    fun openNotificationSettings(): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            appDetailsIntent()
        }
        return startIfResolvable(intent)
    }

    fun openAppDetails(): Boolean = startIfResolvable(appDetailsIntent())

    fun openOemBackgroundSettings(): Boolean {
        val brand = resolveOemBrand(Build.MANUFACTURER, Build.BRAND)
        for (intent in oemIntents(brand)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startIfResolvable(intent)) return true
        }
        return false
    }

    fun checklistFor(brand: OemBrand = resolveOemBrand(Build.MANUFACTURER, Build.BRAND)): List<String> =
        oemChecklist(brand)

    fun summaryFor(brand: OemBrand = resolveOemBrand(Build.MANUFACTURER, Build.BRAND)): String =
        oemSummary(brand)

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun startIfResolvable(intent: Intent): Boolean {
        val resolved = intent.resolveActivity(context.packageManager) != null
        if (!resolved) return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun oemIntents(brand: OemBrand): List<Intent> = when (brand) {
        OemBrand.XIAOMI -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                ),
            ),
        )
        OemBrand.HUAWEI, OemBrand.HONOR -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.hihonor.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            ),
        )
        OemBrand.OPPO -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
            ),
        )
        OemBrand.VIVO -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                ),
            ),
        )
        OemBrand.SAMSUNG -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
            ),
        )
        OemBrand.OTHER -> emptyList()
    }

    companion object {
        private const val PREFS_NAME = "background_run_prefs"
        private const val KEY_BANNER_DISMISSED_AT = "banner_dismissed_at"
        private const val KEY_BATTERY_PROMPT_DONE = "battery_prompt_done"
        const val BANNER_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000

        fun resolveOemBrand(manufacturer: String?, brand: String?): OemBrand {
            val tokens = listOfNotNull(manufacturer, brand)
                .joinToString(" ")
                .lowercase()
            return when {
                tokens.contains("xiaomi") || tokens.contains("redmi") || tokens.contains("poco") -> OemBrand.XIAOMI
                tokens.contains("huawei") -> OemBrand.HUAWEI
                tokens.contains("honor") -> OemBrand.HONOR
                tokens.contains("oppo") || tokens.contains("oneplus") || tokens.contains("realme") -> OemBrand.OPPO
                tokens.contains("vivo") || tokens.contains("iqoo") -> OemBrand.VIVO
                tokens.contains("samsung") -> OemBrand.SAMSUNG
                else -> OemBrand.OTHER
            }
        }

        fun oemSummary(brand: OemBrand): String = when (brand) {
            OemBrand.XIAOMI -> "小米/红米手机需允许自启动，并将省电策略设为「无限制」"
            OemBrand.HUAWEI -> "华为手机请在启动管理中允许自启动与后台活动"
            OemBrand.HONOR -> "荣耀手机请在启动管理中允许自启动与后台活动"
            OemBrand.OPPO -> "OPPO/一加/realme 请允许自启动，并关闭后台冻结"
            OemBrand.VIVO -> "vivo/iQOO 请允许后台高耗电与自启动"
            OemBrand.SAMSUNG -> "三星请将本应用从「休眠应用」中移除，电池设为无限制"
            OemBrand.OTHER -> "请允许本应用不受电池优化限制，并允许后台运行"
        }

        fun oemChecklist(brand: OemBrand): List<String> = when (brand) {
            OemBrand.XIAOMI -> listOf(
                "设置 → 应用设置 → 应用管理 → FitSync → 省电策略 → 无限制",
                "设置 → 应用设置 → 授权管理 → 自启动管理 → 允许 FitSync",
                "最近任务中下拉 FitSync 卡片，锁定后台",
            )
            OemBrand.HUAWEI -> listOf(
                "设置 → 应用和服务 → 应用启动管理 → FitSync → 手动管理 → 允许自启动/关联启动/后台活动",
                "设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接（可选）",
            )
            OemBrand.HONOR -> listOf(
                "设置 → 应用和服务 → 应用启动管理 → FitSync → 手动管理全部允许",
                "设置 → 电池 → 将 FitSync 设为不受优化限制",
            )
            OemBrand.OPPO -> listOf(
                "设置 → 应用 → 自启动管理 → 允许 FitSync",
                "设置 → 电池 → 应用耗电管理 → FitSync → 允许后台运行 / 不优化",
            )
            OemBrand.VIVO -> listOf(
                "设置 → 电池 → 后台高耗电 → 允许 FitSync",
                "设置 → 应用与权限 → 权限管理 → 自启动 → 允许 FitSync",
            )
            OemBrand.SAMSUNG -> listOf(
                "设置 → 电池与设备维护 → 电池 → 后台使用限制 → 从未休眠的应用 → 添加 FitSync",
                "应用信息 → 电池 → 不受限制",
            )
            OemBrand.OTHER -> listOf(
                "系统设置中将 FitSync 的电池策略设为「无限制」或不优化",
                "允许应用自启动 / 后台运行（若系统提供该选项）",
                "开启通知权限，以便显示同步进度与操作提醒",
            )
        }

        fun computeNeedsAttention(
            autoSyncEnabled: Boolean,
            batteryOptimizationIgnored: Boolean,
            isBackgroundRestricted: Boolean,
            notificationsGranted: Boolean,
        ): Boolean {
            if (!autoSyncEnabled) return false
            return !batteryOptimizationIgnored || isBackgroundRestricted || !notificationsGranted
        }
    }
}

enum class OemBrand {
    XIAOMI,
    HUAWEI,
    HONOR,
    OPPO,
    VIVO,
    SAMSUNG,
    OTHER,
}

data class BackgroundRunStatus(
    val batteryOptimizationIgnored: Boolean,
    val isBackgroundRestricted: Boolean,
    val notificationsGranted: Boolean,
    val brand: OemBrand,
    val needsAttention: Boolean,
)
