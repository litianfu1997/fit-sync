package com.fitsync.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.entity.AppSettingsEntity
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.sync.BackgroundRunGuard
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.SettingsDivider
import com.fitsync.app.ui.components.SettingsGroup
import com.fitsync.app.ui.components.SettingsRow
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.theme.FitSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onOpenPlatforms: () -> Unit = {},
    onOpenRules: () -> Unit = {},
    onOpenBackground: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
) {
    val app = FitSyncApp.instance
    val settings by app.database.appSettingsDao().observe()
        .collectAsStateWithLifecycle(initialValue = null)
    val accounts by app.database.platformAccountDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val rules by app.database.syncRuleDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val current = settings ?: AppSettingsEntity()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val guard = remember(context) { BackgroundRunGuard(context.applicationContext) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var showBackgroundPrompt by remember { mutableStateOf(false) }
    val backgroundStatus = remember(resumeTick, current.autoSyncEnabled) {
        guard.evaluate(current.autoSyncEnabled)
    }
    val boundAccounts = accounts.filter { it.status != AccountStatus.DISABLED }
    val accountsNeedingAttention = boundAccounts.count {
        it.status == AccountStatus.AUTH_EXPIRED || it.status == AccountStatus.NEEDS_USER_ACTION
    }
    val enabledRuleCount = rules.count { it.enabled }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun save(next: AppSettingsEntity, afterSaved: (() -> Unit)? = null) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.database.appSettingsDao().upsert(next)
                    SyncScheduler.schedule(context, next.autoSyncEnabled, next.syncIntervalMinutes)
                }
            }
            result.exceptionOrNull()?.let {
                snackbarHostState.showSnackbar("保存失败：${it.message ?: "请稍后重试"}")
            } ?: afterSaved?.invoke()
        }
    }

    if (showBackgroundPrompt) {
        AlertDialog(
            onDismissRequest = {
                showBackgroundPrompt = false
                guard.markBatteryPromptDone()
            },
            title = { Text("允许后台运行") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FitSpacing.sm)) {
                    Text("后台同步依赖系统调度，部分手机的省电策略会限制 FitSync 按时工作。")
                    Text(
                        BackgroundRunGuard.oemSummary(backgroundStatus.brand),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackgroundPrompt = false
                        guard.markBatteryPromptDone()
                        guard.openBackgroundSettings(preferBatteryRequest = true)
                        resumeTick++
                    },
                ) { Text("立即设置") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBackgroundPrompt = false
                        guard.markBatteryPromptDone()
                    },
                ) { Text("稍后") }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FitTopBar(title = "设置", subtitle = "集中管理同步与本机数据")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = FitSpacing.screenGutter,
                    top = FitSpacing.sm,
                    end = FitSpacing.screenGutter,
                    bottom = FitSpacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
            ) {
                item {
                    SettingsGroup("同步") {
                        SettingsRow(
                            title = "自动同步",
                            subtitle = "由系统在后台增量拉取与投递",
                            icon = Icons.Default.Sync,
                            checked = current.autoSyncEnabled,
                            onCheckedChange = { enabled ->
                                save(current.copy(autoSyncEnabled = enabled)) {
                                    if (enabled &&
                                        !guard.isIgnoringBatteryOptimizations() &&
                                        !guard.isBatteryPromptDone()
                                    ) {
                                        showBackgroundPrompt = true
                                    }
                                }
                            },
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Default.Devices,
                            title = "平台账号",
                            subtitle = when {
                                boundAccounts.isEmpty() -> "添加需要同步的平台"
                                accountsNeedingAttention > 0 ->
                                    "${boundAccounts.size} 个账号，$accountsNeedingAttention 个需处理"
                                else -> "${boundAccounts.size} 个账号已连接"
                            },
                            onClick = onOpenPlatforms,
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Default.Rule,
                            title = "同步自动化",
                            subtitle = when {
                                rules.isEmpty() -> "设置活动来源与目标平台"
                                enabledRuleCount == 0 -> "已有规则，尚未启用"
                                else -> "$enabledRuleCount 条路径已启用"
                            },
                            onClick = onOpenRules,
                        )
                    }
                }
                item {
                    SettingsGroup("系统") {
                        SettingsRow(
                            icon = Icons.Default.BatteryChargingFull,
                            title = "后台运行",
                            subtitle = if (backgroundStatus.needsAttention) "系统设置可能影响同步" else "后台运行状态正常",
                            onClick = onOpenBackground,
                            trailing = {
                                StatusPill(
                                    if (backgroundStatus.needsAttention) "需检查" else "正常",
                                    if (backgroundStatus.needsAttention) StatusTone.WARNING else StatusTone.SUCCESS,
                                )
                            },
                        )
                        SettingsDivider()
                        SettingsRow(
                            title = "操作提醒",
                            subtitle = "登录失效、验证码或网页操作时通知",
                            icon = Icons.Default.NotificationsActive,
                            checked = current.notificationsEnabled,
                            onCheckedChange = { enabled ->
                                if (
                                    enabled && Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                save(current.copy(notificationsEnabled = enabled))
                            },
                        )
                    }
                }
                item {
                    SettingsGroup("数据") {
                        SettingsRow(
                            icon = Icons.Default.Storage,
                            title = "数据与存储",
                            subtitle = "历史范围、FIT 保留与文件清理",
                            onClick = onOpenStorage,
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Default.Security,
                            title = "隐私与关于",
                            subtitle = "本地架构、安全说明与版本信息",
                            onClick = onOpenPrivacy,
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(FitSpacing.lg),
        )
    }
}
