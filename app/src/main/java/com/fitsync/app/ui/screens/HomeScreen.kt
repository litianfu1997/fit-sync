package com.fitsync.app.ui.screens

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.fitsync.app.FitSyncApp
import com.fitsync.app.connectors.ConnectorCapabilities
import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.data.db.entity.AppSettingsEntity
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.ImportJobEntity
import com.fitsync.app.data.db.entity.SyncRuleEntity
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.ImportJobStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.sync.BackgroundRunGuard
import com.fitsync.app.sync.ManualSyncSelection
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.sync.syncErrorMessage
import com.fitsync.app.sync.toSyncWorkResult
import com.fitsync.app.ui.components.EmptyStateVariant
import com.fitsync.app.ui.components.FitCard
import com.fitsync.app.ui.components.FitCardTone
import com.fitsync.app.ui.components.FitEmptyState
import com.fitsync.app.ui.components.FitListRow
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.HeroStatStrip
import com.fitsync.app.ui.components.IconBadge
import com.fitsync.app.ui.components.IconBadgeSize
import com.fitsync.app.ui.components.IconBadgeTone
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.SectionHeader
import com.fitsync.app.ui.components.StatGrid
import com.fitsync.app.ui.components.StatTileSpec
import com.fitsync.app.ui.components.StatTone
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.accountStatusText
import com.fitsync.app.ui.components.accountStatusTone
import com.fitsync.app.ui.components.executionText
import com.fitsync.app.ui.components.importStatusText
import com.fitsync.app.ui.theme.FitGradients
import com.fitsync.app.ui.theme.FitOrange
import com.fitsync.app.ui.theme.FitShape
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitType
import com.fitsync.app.ui.theme.fitHero
import com.fitsync.app.web.PlatformWebActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenActivities: () -> Unit = {},
    onOpenJobs: (SyncJobFilter) -> Unit = {},
    onOpenPlatforms: () -> Unit = {},
) {
    val db = FitSyncApp.instance.database
    val context = LocalContext.current
    val homeViewModel: HomeViewModel = viewModel(
        factory = remember(db) { HomeViewModel.factory(db) },
    )
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val stats = homeState.stats
    val recentImports = homeState.recentImports
    val waitingJobs = homeState.waitingJobs
    val accounts = homeState.accounts
    val rules = homeState.rules
    val settings = homeState.settings
    val manualWorks by produceState(initialValue = emptyList<WorkInfo>(), context) {
        // 至少让出一个渲染帧，WorkManager 的 SQLite/调度器初始化不进入首屏关键路径。
        withFrameNanos { }
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkLiveData(SyncScheduler.MANUAL_NAME)
            .asFlow()
            .collect { value = it }
    }
    val syncState = remember(manualWorks) { manualSyncUiState(manualWorks.lastOrNull()) }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val guard = remember(context) { BackgroundRunGuard(context.applicationContext) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var showManualSyncSheet by remember { mutableStateOf(false) }
    val enabledRuleCount = rules.count { it.enabled }
    val activeImportCount = recentImports.count {
        it.status in setOf(
            ImportJobStatus.RECEIVED,
            ImportJobStatus.COPYING,
            ImportJobStatus.VALIDATING,
            ImportJobStatus.PARSING,
            ImportJobStatus.DEDUPLICATING,
        )
    }
    val duplicateImportCount = recentImports.count { it.status == ImportJobStatus.DUPLICATE_FILE }
    val visibleRecentImports = recentImports.filterNot { it.status == ImportJobStatus.DUPLICATE_FILE }.take(6)
    val autoSyncEnabled = settings?.autoSyncEnabled != false
    val backgroundStatus = remember(resumeTick, autoSyncEnabled) {
        guard.evaluate(autoSyncEnabled)
    }
    val showRestrictionBanner = remember(resumeTick, backgroundStatus, autoSyncEnabled) {
        autoSyncEnabled &&
            backgroundStatus.needsAttention &&
            !guard.isBannerDismissedRecently()
    }
    val lastSyncAt = remember(accounts) {
        accounts.mapNotNull { it.lastSyncAtEpochMs }.maxOrNull()
    }
    val lastSyncLabel = lastSyncAt?.let { "上次同步 ${dateFormat.format(Date(it))}" }
        ?: "尚未完成过自动同步"
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FitTopBar(title = "概览", subtitle = "原始 FIT 只保存在本机")
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
                    SyncHero(
                        autoSyncEnabled = autoSyncEnabled,
                        backgroundRestricted = backgroundStatus.needsAttention,
                        lastSyncLabel = lastSyncLabel,
                        stats = stats,
                        waitingCount = waitingJobs.size,
                        enabledRuleCount = enabledRuleCount,
                        syncState = syncState,
                        onSync = { showManualSyncSheet = true },
                    )
                }
                if (showRestrictionBanner) {
                    item {
                        BackgroundRestrictionBanner(
                            summary = BackgroundRunGuard.oemSummary(backgroundStatus.brand),
                            notificationsMissing = !backgroundStatus.notificationsGranted,
                            onOpenSettings = {
                                if (!backgroundStatus.notificationsGranted &&
                                    backgroundStatus.batteryOptimizationIgnored &&
                                    !backgroundStatus.isBackgroundRestricted
                                ) {
                                    guard.openNotificationSettings()
                                } else {
                                    guard.openBackgroundSettings(preferBatteryRequest = true)
                                }
                                resumeTick++
                            },
                            onDismiss = {
                                guard.dismissBanner()
                                resumeTick++
                            },
                        )
                    }
                }
                item {
                    StatsPanel(
                        stats = stats,
                        onOpenActivities = onOpenActivities,
                        onOpenJobs = onOpenJobs,
                    )
                }
                if (waitingJobs.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "需要你处理",
                            subtitle = "登录、验证码或前台网页流程需要确认",
                            trailing = { StatusPill("${waitingJobs.size}", StatusTone.WARNING, compact = true) },
                        )
                    }
                    items(waitingJobs.take(4), key = { "waiting-${it.id}" }) { job ->
                        val account = accounts.firstOrNull { it.id == job.destinationAccountId }
                        val platform = account?.platform
                        FitListRow(
                            title = platform?.displayName ?: "未知平台",
                            subtitle = job.errorMessage ?: "请进入平台网页继续操作",
                            leading = if (platform != null) {
                                { PlatformAvatar(platform, 32.dp) }
                            } else null,
                            trailing = if (account != null && platform != null) {
                                {
                                    Button(onClick = {
                                        context.startActivity(
                                            PlatformWebActivity.createIntent(
                                                context,
                                                platform,
                                                job.id,
                                                job.userActionUrl,
                                            ),
                                        )
                                    }) { Text(if (platform == Platform.GARMIN) "登录" else "继续") }
                                }
                            } else null,
                        )
                    }
                }
                item {
                    SectionHeader(
                        title = "最近导入",
                        subtitle = if (stats.review > 0) "${stats.review} 条活动需要复核" else "从平台拉取或分享 FIT 后，记录会出现在这里",
                        trailing = if (stats.review > 0) {
                            { StatusPill("待复核 ${stats.review}", StatusTone.WARNING) }
                        } else null,
                    )
                }
                if (recentImports.isEmpty()) {
                    item {
                        FitEmptyState(
                            title = "还没有活动",
                            body = "登录平台开始拉取，或从任意骑行 App 将 FIT 文件分享到这里。",
                            variant = EmptyStateVariant.FirstRun,
                            steps = listOf("连接平台账号", "分享或拉取 FIT", "自动补齐到其他平台"),
                            actionLabel = "连接平台",
                            onAction = onOpenPlatforms,
                        )
                    }
                } else {
                    if (activeImportCount > 0 || duplicateImportCount > 0) {
                        item(key = "import-inline-status") {
                            InlineStatusBar(
                                activeCount = activeImportCount,
                                duplicateCount = duplicateImportCount,
                            )
                        }
                    }
                    items(visibleRecentImports, key = { "import-${it.id}" }) { job ->
                        FitListRow(
                            title = job.displayName ?: "未命名 FIT",
                            subtitle = dateFormat.format(Date(job.createdAtEpochMs)),
                            leading = {
                                IconBadge(
                                    Icons.Default.Description,
                                    size = IconBadgeSize.Sm,
                                    tone = IconBadgeTone.Neutral,
                                )
                            },
                            trailing = { StatusPill(importStatusText(job.status), importTone(job.status), compact = true) },
                            supporting = job.errorMessage,
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

    if (showManualSyncSheet) {
        ManualSyncSheet(
            accounts = accounts,
            historyDays = settings?.initialHistoryDays ?: 90,
            onDismiss = { showManualSyncSheet = false },
            onOpenPlatforms = {
                showManualSyncSheet = false
                onOpenPlatforms()
            },
            onStart = { selection ->
                showManualSyncSheet = false
                SyncScheduler.enqueueNow(context, selection)
                val destination = accounts.firstOrNull { it.id == selection.destinationAccountId }
                val route = "${selection.sourcePlatform.displayName} → ${destination?.platform?.displayName ?: "目标平台"}"
                val typeText = manualSyncTypeLabel(selection.sportTypes)
                scope.launch {
                    snackbarHostState.showSnackbar("已开始：$route · $typeText")
                }
            },
        )
    }
}

/** 处理中的导入提示：一条低强调信息条，而不是两张卡片。 */
@Composable
private fun InlineStatusBar(activeCount: Int, duplicateCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(FitShape.control)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = FitSpacing.md, vertical = FitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
    ) {
        if (activeCount > 0) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("正在处理 $activeCount 个 FIT 文件", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "$duplicateCount 个文件已存在，已合并显示",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ManualSyncSheet(
    accounts: List<PlatformAccountEntity>,
    historyDays: Int,
    onDismiss: () -> Unit,
    onOpenPlatforms: () -> Unit,
    onStart: (ManualSyncSelection) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sourcePlatforms = remember(accounts) {
        (
            listOf(Platform.LOCAL_IMPORT) + accounts.filter { account ->
                val capabilities = account.manualSyncCapabilities()
                account.isReadyForManualSync() &&
                    capabilities.listActivitiesMode == ConnectorExecution.BACKGROUND &&
                    capabilities.downloadFitMode == ConnectorExecution.BACKGROUND
            }.map { it.platform }
            ).distinct()
    }
    var selectedSource by remember(sourcePlatforms) { mutableStateOf(sourcePlatforms.first()) }
    var selectedDestinationId by remember { mutableStateOf<Long?>(null) }
    var pendingSelection by remember { mutableStateOf<ManualSyncSelection?>(null) }
    var selectedCategories by remember {
        mutableStateOf(ManualSyncActivityCategory.entries.toSet())
    }
    val destinationOptions = remember(accounts, selectedSource) {
        accounts.filter { account ->
            account.status != AccountStatus.DISABLED &&
                account.platform != selectedSource &&
                account.manualSyncCapabilities().canUploadActivity
        }
    }
    val selectedDestination = destinationOptions.firstOrNull {
        it.id == selectedDestinationId && it.isReadyForManualSync()
    }
    val selectedSportTypes = selectedCategories.flatMapTo(mutableSetOf()) { it.sportTypes }
    val canStart = selectedDestination != null && selectedSportTypes.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = FitShape.sheet,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
        ) {
            Column(Modifier.padding(horizontal = FitSpacing.xl, vertical = FitSpacing.xs)) {
                Text("立即同步", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "先确认本次任务的来源、目标和活动类型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(FitSpacing.xl, FitSpacing.lg, FitSpacing.xl, FitSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(FitSpacing.xl),
            ) {
                item {
                    ManualSyncSectionTitle("从哪里同步", "仅显示可在后台读取原始 FIT 的来源")
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                    ) {
                        sourcePlatforms.forEach { platform ->
                            FilterChip(
                                modifier = Modifier.height(36.dp),
                                selected = selectedSource == platform,
                                onClick = {
                                    selectedSource = platform
                                    selectedDestinationId = null
                                },
                                label = { Text(platform.displayName) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                    Text(
                        if (selectedSource == Platform.LOCAL_IMPORT) {
                            "从已经导入 FitSync 的本机活动中筛选。"
                        } else {
                            "先拉取 ${selectedSource.displayName} 最近 $historyDays 天活动，再按类型投递。"
                        },
                        modifier = Modifier.padding(top = FitSpacing.sm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                item {
                    ManualSyncSectionTitle("同步到哪里", "一次选择一个目标，避免路径含糊")
                }
                if (destinationOptions.isEmpty()) {
                    item {
                        ManualSyncMissingDestination(onOpenPlatforms)
                    }
                } else {
                    items(destinationOptions, key = { "manual-destination-${it.id}" }) { account ->
                        val ready = account.isReadyForManualSync()
                        ManualSyncDestinationRow(
                            account = account,
                            selected = selectedDestination?.id == account.id,
                            enabled = ready,
                            onClick = { selectedDestinationId = account.id },
                        )
                    }
                    if (destinationOptions.none { it.isReadyForManualSync() }) {
                        item {
                            TextButton(onClick = onOpenPlatforms) { Text("处理平台账号") }
                        }
                    }
                }

                item {
                    ManualSyncSectionTitle("同步哪些活动", "可以同时选择多种类型")
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                    ) {
                        ManualSyncActivityCategory.entries.forEach { category ->
                            FilterChip(
                                modifier = Modifier.height(36.dp),
                                selected = category in selectedCategories,
                                onClick = {
                                    selectedCategories = if (category in selectedCategories) {
                                        selectedCategories - category
                                    } else {
                                        selectedCategories + category
                                    }
                                },
                                label = { Text(category.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                    if (selectedCategories.isEmpty()) {
                        Text(
                            "请至少选择一种活动类型",
                            modifier = Modifier.padding(top = FitSpacing.sm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                item {
                    ManualSyncSummary(
                        source = selectedSource,
                        destination = selectedDestination,
                        typeLabel = manualSyncTypeLabel(selectedSportTypes),
                        historyDays = historyDays,
                    )
                    Text(
                        "只为尚未存在于目标平台的匹配活动创建任务，不会修改自动化规则。",
                        modifier = Modifier.padding(top = FitSpacing.sm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = {
                    val destination = selectedDestination ?: return@Button
                    pendingSelection =
                        ManualSyncSelection(
                            sourcePlatform = selectedSource,
                            destinationAccountId = destination.id,
                            sportTypes = selectedSportTypes,
                        )
                },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().padding(horizontal = FitSpacing.xl, vertical = FitSpacing.md)
                    .height(48.dp),
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(FitSpacing.iconSm))
                Text("开始同步", modifier = Modifier.padding(start = FitSpacing.sm))
            }
        }
    }

    pendingSelection?.let { selection ->
        val destination = accounts.firstOrNull { it.id == selection.destinationAccountId }
        AlertDialog(
            onDismissRequest = { pendingSelection = null },
            title = { Text("确认开始同步？") },
            text = {
                Text(
                    "${selection.sourcePlatform.displayName} → ${destination?.platform?.displayName ?: "目标平台"}\n" +
                        "${manualSyncTypeLabel(selection.sportTypes)} · 最近 $historyDays 天\n\n" +
                        "系统会检查范围内的活动，只为目标平台尚不存在的活动创建任务。",
                )
            },
            confirmButton = {
                Button(onClick = { onStart(selection) }) { Text("确认同步") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSelection = null }) { Text("返回修改") }
            },
        )
    }
}

@Composable
private fun ManualSyncSectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = FitType.dataLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            subtitle,
            modifier = Modifier.padding(top = FitSpacing.xxs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManualSyncMissingDestination(onOpenPlatforms: () -> Unit) {
    FitCard(tone = FitCardTone.Muted, contentPadding = PaddingValues(FitSpacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            Text(
                "还没有可用目标平台",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenPlatforms) { Text("管理账号") }
        }
    }
}

@Composable
private fun ManualSyncDestinationRow(
    account: PlatformAccountEntity,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val capabilities = account.manualSyncCapabilities()
    FitCard(
        modifier = Modifier.fillMaxWidth(),
        tone = if (selected) FitCardTone.Primary else FitCardTone.Surface,
        selected = selected,
        contentPadding = PaddingValues(FitSpacing.md),
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            PlatformAvatar(account.platform, 32.dp)
            Column(Modifier.weight(1f)) {
                Text(account.platform.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (enabled) "${executionText(capabilities.uploadFitMode)}上传"
                    else "账号${accountStatusText(account.status)}，暂不可选择",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(accountStatusText(account.status), accountStatusTone(account.status))
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable
private fun ManualSyncSummary(
    source: Platform,
    destination: PlatformAccountEntity?,
    typeLabel: String,
    historyDays: Int,
) {
    FitCard(tone = FitCardTone.Muted, contentPadding = PaddingValues(FitSpacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
        ) {
            PlatformAvatar(source, 28.dp)
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "同步到",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(FitSpacing.iconSm),
            )
            destination?.let { PlatformAvatar(it.platform, 28.dp) }
            Column(Modifier.weight(1f)) {
                Text(
                    "${source.displayName} → ${destination?.platform?.displayName ?: "请选择目标"}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "$typeLabel · 最近 $historyDays 天",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class ManualSyncActivityCategory(
    val label: String,
    val sportTypes: Set<SportType>,
) {
    CYCLING(
        "骑行",
        setOf(SportType.CYCLING, SportType.INDOOR_CYCLING, SportType.MTB, SportType.GRAVEL),
    ),
    RUNNING("跑步", setOf(SportType.RUNNING)),
    OTHER("其他", setOf(SportType.OTHER, SportType.UNKNOWN)),
}

private fun PlatformAccountEntity.isReadyForManualSync(): Boolean =
    status == AccountStatus.ACTIVE || status == AccountStatus.EXPERIMENTAL

private fun PlatformAccountEntity.manualSyncCapabilities(): ConnectorCapabilities =
    ConnectorCapabilities.deserialize(capabilitySnapshot)
        ?: PlatformCapabilityRegistry.capabilities(platform)

private fun manualSyncTypeLabel(sportTypes: Set<SportType>): String =
    ManualSyncActivityCategory.entries
        .filter { category -> category.sportTypes.any(sportTypes::contains) }
        .joinToString("、") { it.label }
        .ifBlank { "未选择活动类型" }

@Composable
private fun StatsPanel(
    stats: HomeStats,
    onOpenActivities: () -> Unit,
    onOpenJobs: (SyncJobFilter) -> Unit,
) {
    val tiles = listOf(
        StatTileSpec("discovered", stats.discovered.toString(), "本地活动"),
        StatTileSpec("synced", stats.synced.toString(), "成功投递"),
        StatTileSpec(
            "pending",
            stats.pending.toString(),
            "等待任务",
            tone = if (stats.pending > 0) StatTone.Warning else StatTone.Default,
        ),
        StatTileSpec(
            "failed",
            stats.failed.toString(),
            "同步失败",
            tone = if (stats.failed > 0) StatTone.Error else StatTone.Default,
        ),
    )
    StatGrid(
        stats = tiles,
        columns = 2,
        onStatClick = { spec ->
            when (spec.key) {
                "discovered" -> onOpenActivities()
                "synced" -> onOpenJobs(SyncJobFilter.COMPLETED)
                "pending" -> onOpenJobs(SyncJobFilter.WAITING)
                "failed" -> onOpenJobs(SyncJobFilter.FAILED)
            }
        },
    )
}

@Composable
private fun BackgroundRestrictionBanner(
    summary: String,
    notificationsMissing: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    FitCard(tone = FitCardTone.Error) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
        ) {
            Icon(
                Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(FitSpacing.iconMd),
            )
            Column(Modifier.weight(1f)) {
                Text("后台同步可能被系统限制", style = MaterialTheme.typography.titleSmall)
                Text(
                    summary,
                    modifier = Modifier.padding(top = FitSpacing.xxs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (notificationsMissing) {
                    Text(
                        "通知权限未开启时，同步进度与操作提醒可能无法显示。",
                        modifier = Modifier.padding(top = FitSpacing.xs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = FitSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onOpenSettings) { Text("去设置") }
            TextButton(onClick = onDismiss) { Text("暂不") }
        }
    }
}

@Composable
private fun SyncHero(
    autoSyncEnabled: Boolean,
    backgroundRestricted: Boolean,
    lastSyncLabel: String,
    stats: HomeStats,
    waitingCount: Int,
    enabledRuleCount: Int,
    syncState: ManualSyncUiState,
    onSync: () -> Unit,
) {
    val statusText = when {
        !autoSyncEnabled -> "手动同步"
        backgroundRestricted -> "自动同步需要检查"
        enabledRuleCount > 0 -> "自动同步正常"
        else -> "自动同步已开启"
    }
    val statusColor = when {
        !autoSyncEnabled -> FitGradients.onHeroSecondary
        backgroundRestricted -> FitOrange
        enabledRuleCount > 0 -> MaterialTheme.colorScheme.secondary
        else -> FitOrange
    }
    // 首帧不播放滚动动画，避免从 0 滚到初始值。
    var animateStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animateStarted = true }
    val animatedCount by animateIntAsState(
        targetValue = stats.discovered,
        animationSpec = if (animateStarted) tween(durationMillis = 600) else snap(),
        label = "home-discovered-count",
    )

    CompositionLocalProvider(LocalContentColor provides FitGradients.onHeroPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().fitHero().padding(FitSpacing.xl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                Text(statusText, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (waitingCount > 0) {
                    StatusPill("待处理 $waitingCount", StatusTone.WARNING)
                }
            }
            Text(
                "本地活动",
                modifier = Modifier.padding(top = FitSpacing.xl),
                style = FitType.dataLabel,
                color = FitGradients.onHeroSecondary,
            )
            Text(
                "%,d".format(animatedCount),
                style = FitType.numericHero,
            )
            HeroStatStrip(
                stats = listOf(
                    StatTileSpec("synced", "%,d".format(stats.synced), "已同步"),
                    StatTileSpec("pending", stats.pending.toString(), "待处理"),
                    StatTileSpec("failed", stats.failed.toString(), "失败"),
                ),
                modifier = Modifier.padding(top = FitSpacing.sm),
            )
            Row(
                modifier = Modifier.padding(top = FitSpacing.xl).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSync,
                    enabled = !syncState.isActive,
                    modifier = Modifier.height(44.dp),
                ) {
                    if (syncState.isActive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(FitSpacing.iconSm))
                    }
                    Text(
                        when (syncState) {
                            ManualSyncUiState.Running -> "正在后台同步"
                            is ManualSyncUiState.Queued -> "等待后台执行"
                            else -> "立即同步"
                        },
                        modifier = Modifier.padding(start = FitSpacing.sm),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    lastSyncLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = FitGradients.onHeroSecondary,
                )
            }
            if (syncState !is ManualSyncUiState.Idle) {
                Text(
                    when (syncState) {
                        is ManualSyncUiState.Queued -> syncState.message
                        ManualSyncUiState.Running -> "正在后台检查平台，切换应用不会中断"
                        is ManualSyncUiState.Finished -> syncState.message
                        is ManualSyncUiState.Failed -> syncState.message
                        ManualSyncUiState.Idle -> ""
                    },
                    modifier = Modifier.padding(top = FitSpacing.md),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncState is ManualSyncUiState.Failed) MaterialTheme.colorScheme.error
                    else FitGradients.onHeroSecondary,
                )
            }
        }
    }
}

private sealed class ManualSyncUiState(val isActive: Boolean) {
    data object Idle : ManualSyncUiState(false)
    data class Queued(val message: String) : ManualSyncUiState(true)
    data object Running : ManualSyncUiState(true)
    data class Finished(val message: String) : ManualSyncUiState(false)
    data class Failed(val message: String) : ManualSyncUiState(false)
}

private fun manualSyncUiState(workInfo: WorkInfo?): ManualSyncUiState = when (workInfo?.state) {
    null -> ManualSyncUiState.Idle
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ManualSyncUiState.Queued(
        if (workInfo.runAttemptCount > 0) "同步暂时未完成，等待系统重试" else "已进入后台同步队列，等待网络条件",
    )
    WorkInfo.State.RUNNING -> ManualSyncUiState.Running
    WorkInfo.State.SUCCEEDED -> ManualSyncUiState.Finished(
        workInfo.outputData.toSyncWorkResult()?.userMessage() ?: "上次同步已完成",
    )
    WorkInfo.State.FAILED -> ManualSyncUiState.Failed(
        workInfo.outputData.syncErrorMessage()?.let { "同步失败：$it" } ?: "同步失败，请稍后重试",
    )
    WorkInfo.State.CANCELLED -> ManualSyncUiState.Failed("同步已取消，可重新发起")
}

private fun importTone(status: ImportJobStatus): StatusTone = when (status) {
    ImportJobStatus.READY, ImportJobStatus.DUPLICATE_FILE -> StatusTone.SUCCESS
    ImportJobStatus.INVALID -> StatusTone.ERROR
    ImportJobStatus.NEEDS_REVIEW -> StatusTone.WARNING
    else -> StatusTone.INFO
}

private class HomeViewModel(db: FitSyncDatabase) : ViewModel() {
    private val content = combine(
        db.canonicalActivityDao().observeHomeSnapshot(),
        db.importJobDao().observeRecent(5),
        db.deliveryJobDao().observeWaitingForUser(4),
        db.platformAccountDao().observeAll(),
        db.syncRuleDao().observeAll(),
    ) { snapshot, imports, waiting, accounts, rules ->
        HomeUiState(
            stats = HomeStats(
                discovered = snapshot.discovered,
                synced = snapshot.synced,
                pending = snapshot.pending,
                review = snapshot.review,
                failed = snapshot.failed,
            ),
            recentImports = imports,
            waitingJobs = waiting,
            accounts = accounts,
            rules = rules,
        )
    }

    val state: StateFlow<HomeUiState> = combine(
        content,
        db.appSettingsDao().observe(),
    ) { contentState, settings ->
        contentState.copy(settings = settings)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    companion object {
        fun factory(db: FitSyncDatabase): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(db) as T
        }
    }
}

private data class HomeUiState(
    val stats: HomeStats = HomeStats(),
    val recentImports: List<ImportJobEntity> = emptyList(),
    val waitingJobs: List<DeliveryJobEntity> = emptyList(),
    val accounts: List<PlatformAccountEntity> = emptyList(),
    val rules: List<SyncRuleEntity> = emptyList(),
    val settings: AppSettingsEntity? = null,
)

private data class HomeStats(
    val discovered: Int = 0,
    val synced: Int = 0,
    val pending: Int = 0,
    val review: Int = 0,
    val failed: Int = 0,
)
