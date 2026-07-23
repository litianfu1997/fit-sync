package com.fitsync.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitsync.app.connectors.GarminAuthDiagnostics
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.entity.AppSettingsEntity
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.FitStoragePolicy
import com.fitsync.app.sync.BackgroundRunGuard
import com.fitsync.app.sync.BackgroundRunStatus
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            AppTopBar(title = "设置", subtitle = "集中管理同步与本机数据")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    SettingsGroup("同步") {
                        SettingSwitchRow(
                            title = "自动同步",
                            subtitle = "由系统在后台增量拉取与投递",
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
                            icon = Icons.Default.AutoAwesome,
                        )
                        SettingsDivider()
                        SettingsLinkRow(
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
                        SettingsLinkRow(
                            icon = Icons.Default.Route,
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
                        SettingsLinkRow(
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
                        SettingSwitchRow(
                            title = "操作提醒",
                            subtitle = "登录失效、验证码或网页操作时通知",
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
                            icon = Icons.Default.NotificationsActive,
                        )
                    }
                }
                item {
                    SettingsGroup("数据") {
                        SettingsLinkRow(
                            icon = Icons.Default.Storage,
                            title = "数据与存储",
                            subtitle = "历史范围、FIT 保留与文件清理",
                            onClick = onOpenStorage,
                        )
                        SettingsDivider()
                        SettingsLinkRow(
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
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
fun BackgroundSettingsScreen(onBack: () -> Unit) {
    val settings by FitSyncApp.instance.database.appSettingsDao().observe()
        .collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val guard = remember(context) { BackgroundRunGuard(context.applicationContext) }
    var resumeTick by remember { mutableIntStateOf(0) }
    val status = remember(resumeTick, settings?.autoSyncEnabled) {
        guard.evaluate(settings?.autoSyncEnabled != false)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "后台运行",
            subtitle = "确保系统不会中断同步",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
        ) {
            item {
                BackgroundProtectionCard(
                    status = status,
                    onAllowBackground = {
                        guard.openBackgroundSettings(preferBatteryRequest = true)
                        resumeTick++
                    },
                    onOpenAppDetails = {
                        guard.openAppDetails()
                        resumeTick++
                    },
                    onOpenNotifications = {
                        guard.openNotificationSettings()
                        resumeTick++
                    },
                )
            }
        }
    }
}

@Composable
fun StorageSettingsScreen(onBack: () -> Unit) {
    val app = FitSyncApp.instance
    val settings by app.database.appSettingsDao().observe()
        .collectAsStateWithLifecycle(initialValue = null)
    val current = settings ?: AppSettingsEntity()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    fun save(next: AppSettingsEntity) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.database.appSettingsDao().upsert(next)
                    SyncScheduler.schedule(context, next.autoSyncEnabled, next.syncIntervalMinutes)
                }
            }
            result.exceptionOrNull()?.let {
                snackbarHostState.showSnackbar("保存失败：${it.message ?: "请稍后重试"}")
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = "数据与存储",
                subtitle = "控制拉取范围与原始 FIT 保留",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SettingsCard(Icons.Default.History, "首次历史范围", "新绑定账号第一次拉取的时间跨度") {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(30, 90, 180, 365).forEach { days ->
                                FilterChip(
                                    modifier = Modifier.height(44.dp),
                                    selected = current.initialHistoryDays == days,
                                    onClick = { save(current.copy(initialHistoryDays = days)) },
                                    label = { Text("$days 天") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OutlinedButton(
                                enabled = current.initialHistoryDays > 1,
                                onClick = {
                                    save(current.copy(initialHistoryDays = (current.initialHistoryDays - 30).coerceAtLeast(1)))
                                },
                            ) { Text("−30") }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${current.initialHistoryDays} 天", style = MaterialTheme.typography.titleLarge)
                                Text("当前设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                enabled = current.initialHistoryDays < 3650,
                                onClick = {
                                    save(current.copy(initialHistoryDays = (current.initialHistoryDays + 30).coerceAtMost(3650)))
                                },
                            ) { Text("+30") }
                        }
                        Text(
                            "后续增量同步固定使用 48 小时重叠窗口，避免平台延迟导致漏单。",
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    SettingsCard(Icons.Default.Storage, "原始 FIT 存储", "清理文件不会删除活动记录与平台映射") {
                        StorageOption(
                            title = "长期保存",
                            subtitle = "永不自动删除原始 FIT，便于随时补发",
                            selected = current.storagePolicy == FitStoragePolicy.KEEP_FOREVER,
                            onClick = { save(current.copy(storagePolicy = FitStoragePolicy.KEEP_FOREVER)) },
                        )
                        StorageOption(
                            title = "成功 7 天后删除",
                            subtitle = "无未完成任务并满 7 天后清理原始文件",
                            selected = current.storagePolicy == FitStoragePolicy.DELETE_AFTER_7_DAYS,
                            onClick = { save(current.copy(storagePolicy = FitStoragePolicy.DELETE_AFTER_7_DAYS)) },
                            recommended = true,
                        )
                        StorageOption(
                            title = "同步成功后删除",
                            subtitle = "成功投递且无未完成任务后立即清理",
                            selected = current.storagePolicy == FitStoragePolicy.DELETE_AFTER_SYNC,
                            onClick = { save(current.copy(storagePolicy = FitStoragePolicy.DELETE_AFTER_SYNC)) },
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            app.fitFileStore.cleanupTemporaryFiles()
                                            app.fitStorageJanitor.run()
                                        }
                                    }
                                    snackbarHostState.showSnackbar(
                                        result.fold(
                                            onSuccess = { janitor -> "已清理临时文件；按策略删除 ${janitor.deletedFiles} 个 FIT" },
                                            onFailure = { "清理失败：${it.message ?: "请稍后重试"}" },
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("立即清理文件", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
fun PrivacyAboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "隐私与关于",
            subtitle = "本地优先，不经过自建服务器",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsCard(Icons.Default.Security, "隐私优先的本地架构", "账号会话与活动数据仅保存在本机") {
                    Text(
                        "平台 Cookie、会话令牌和需要自动重登的平台凭据由 WebView 或 Android Keystore 管理。" +
                            "活动库与原始 FIT 只保存在应用沙箱，系统备份已关闭。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                GarminDiagnosticsCard()
            }
            item {
                Text(
                    "FitSync 0.2.0",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GarminDiagnosticsCard() {
    val clipboard = LocalClipboardManager.current
    var events by remember { mutableStateOf(GarminAuthDiagnostics.snapshot()) }
    SettingsCard(
        icon = Icons.Default.BugReport,
        title = "Garmin 登录诊断",
        subtitle = "记录后台判定「登录失效」的真实原因，便于反馈",
    ) {
        if (events.isEmpty()) {
            Text(
                "暂无事件。下次同步触发 Garmin 登录判定后会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "共 ${events.size} 条（仅本机记录，不含敏感凭据）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            events.forEach { ev ->
                Text(
                    formatGarminDiagEvent(ev),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { events = GarminAuthDiagnostics.snapshot() }) { Text("刷新") }
            OutlinedButton(
                enabled = events.isNotEmpty(),
                onClick = {
                    clipboard.setText(AnnotatedString(events.joinToString("\n") { formatGarminDiagEvent(it) }))
                },
            ) { Text("复制") }
            OutlinedButton(
                enabled = events.isNotEmpty(),
                onClick = {
                    GarminAuthDiagnostics.clear()
                    events = emptyList()
                },
            ) { Text("清除") }
        }
    }
}

private fun formatGarminDiagEvent(ev: GarminAuthDiagnostics.Event): String {
    val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(ev.timestampEpochMs))
    val code = ev.httpCode?.let { " code=${ev.httpCode}" }.orEmpty()
    val src = ev.cookieSource.takeIf { it.isNotBlank() }?.let { " src=$it" }.orEmpty()
    val jwt = if (ev.setCookieHasJwtWeb) " setCookie=JWT" else ""
    val loc = ev.locationHost?.takeIf { it.isNotBlank() }?.let { " loc=$it" }.orEmpty()
    val acct = if (ev.hadPersistedAccount) " hadAccount" else ""
    return "$time ${ev.operation}/${ev.phase}=${ev.outcome}$code$src$jwt$loc$acct"
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 66.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingsLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun BackgroundProtectionCard(
    status: BackgroundRunStatus,
    onAllowBackground: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenNotifications: () -> Unit,
) {
    SettingsCard(Icons.Default.BatteryChargingFull, "后台运行保护", "按当前系统状态完成必要设置") {
        StatusLine(
            label = "电池优化",
            ok = status.batteryOptimizationIgnored,
            okText = "已允许无限制",
            badText = "受系统省电限制",
        )
        StatusLine(
            label = "后台运行",
            ok = !status.isBackgroundRestricted,
            okText = "正常",
            badText = "已被系统限制后台",
        )
        StatusLine(
            label = "通知权限",
            ok = status.notificationsGranted,
            okText = "已开启",
            badText = "未开启",
        )
        Text(
            BackgroundRunGuard.oemSummary(status.brand),
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BackgroundRunGuard.oemChecklist(status.brand).forEach { line ->
                Text(
                    "· $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = onAllowBackground,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(if (status.needsAttention) "允许后台运行" else "检查系统设置")
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onOpenAppDetails, modifier = Modifier.weight(1f)) {
                Text("应用详情")
            }
            if (!status.notificationsGranted) {
                OutlinedButton(onClick = onOpenNotifications, modifier = Modifier.weight(1f)) {
                    Text("通知设置")
                }
            }
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    ok: Boolean,
    okText: String,
    badText: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        StatusPill(
            if (ok) okText else badText,
            if (ok) StatusTone.SUCCESS else StatusTone.WARNING,
        )
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(38.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.padding(top = 12.dp), content = content)
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }
            .heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun StorageOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    recommended: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                if (recommended) StatusPill("推荐", StatusTone.SUCCESS)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
