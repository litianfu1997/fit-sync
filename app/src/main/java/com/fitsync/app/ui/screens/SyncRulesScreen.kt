package com.fitsync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitsync.app.FitSyncApp
import com.fitsync.app.connectors.ConnectorCapabilities
import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.components.EmptyStateVariant
import com.fitsync.app.ui.components.FitCard
import com.fitsync.app.ui.components.FitEmptyState
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.PlatformRouteAvatar
import com.fitsync.app.ui.components.SectionHeader
import com.fitsync.app.ui.components.SettingsDivider
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.executionText
import com.fitsync.app.ui.theme.FitSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RouteKey(val source: Platform, val destinationId: Long)

private data class HistoryConfirmation(
    val source: Platform,
    val destinationId: Long,
    val destinationName: String,
)

@Composable
fun SyncRulesScreen(
    onBack: () -> Unit = {},
    onOpenPlatforms: () -> Unit = {},
) {
    val app = FitSyncApp.instance
    val accounts by app.database.platformAccountDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val rules by app.database.syncRuleDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val referencedAccountIds = rules.mapTo(mutableSetOf()) { it.destinationAccountId }
    val referencedSources = rules.mapTo(mutableSetOf()) { it.sourcePlatform }
    val connectedAccounts = accounts.filter {
        it.status == AccountStatus.ACTIVE ||
            it.status == AccountStatus.EXPERIMENTAL ||
            it.id in referencedAccountIds ||
            it.platform in referencedSources
    }
    val sources = (
        listOf(Platform.LOCAL_IMPORT) + connectedAccounts.map { it.platform }.filter { platform ->
            connectedAccounts.firstOrNull { it.platform == platform }.capabilities().let {
                it.canListActivities && it.canDownloadOriginalFit
            }
        }
        ).distinct()
    var selectedSource by rememberSaveable { mutableStateOf(Platform.LOCAL_IMPORT) }
    var busyRoute by remember { mutableStateOf<RouteKey?>(null) }
    var historyConfirmation by remember { mutableStateOf<HistoryConfirmation?>(null) }

    LaunchedEffect(sources) {
        if (selectedSource !in sources) selectedSource = sources.firstOrNull() ?: Platform.LOCAL_IMPORT
    }

    fun updateRule(source: Platform, destinationId: Long, enabled: Boolean, includeHistory: Boolean) {
        val key = RouteKey(source, destinationId)
        busyRoute = key
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.syncCoordinator.setRule(source, destinationId, enabled, includeHistory)
                    SyncScheduler.enqueueNow(context)
                }
            }
            busyRoute = null
            snackbarHostState.showSnackbar(
                result.fold(
                    onSuccess = {
                        when {
                            !enabled -> "自动化已关闭"
                            includeHistory -> "规则已保存，历史活动正在加入队列"
                            else -> "自动化已开启"
                        }
                    },
                    onFailure = { "保存失败：${it.message ?: "请稍后重试"}" },
                ),
            )
        }
    }

    val destinations = connectedAccounts.filter { account ->
        account.platform != selectedSource &&
            account.capabilities().canUploadActivity
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FitTopBar(
                title = "同步自动化",
                subtitle = "选择来源，再决定活动去向",
                onBack = onBack,
            )
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
                if (connectedAccounts.isEmpty()) {
                    item {
                        FitEmptyState(
                            title = "先连接一个平台",
                            body = "连接账号后，就能把本地导入或平台活动自动发送到其他平台。",
                            variant = EmptyStateVariant.FirstRun,
                            icon = Icons.Default.AddLink,
                            steps = listOf("连接平台账号", "选择活动来源", "开启去向并自动补齐"),
                            actionLabel = "去连接平台",
                            onAction = onOpenPlatforms,
                        )
                    }
                } else {
                    item { SectionHeader("活动从哪里来", "一次只配置一个来源，路径更清楚") }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                        ) {
                            sources.forEach { source ->
                                FilterChip(
                                    modifier = Modifier.height(36.dp),
                                    selected = selectedSource == source,
                                    onClick = { selectedSource = source },
                                    label = { Text(source.displayName) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                    item {
                        SourceRouteHeader(
                            source = selectedSource,
                            enabledCount = rules.count { it.sourcePlatform == selectedSource && it.enabled },
                        )
                    }
                    if (destinations.isEmpty()) {
                        item {
                            FitEmptyState(
                                title = "没有可用目标",
                                body = "再连接一个支持上传 FIT 的平台，即可创建自动同步路径。",
                                variant = EmptyStateVariant.FirstRun,
                                icon = Icons.Default.Rule,
                                actionLabel = "管理平台账号",
                                onAction = onOpenPlatforms,
                            )
                        }
                    } else {
                        items(destinations, key = { "${selectedSource.name}-${it.id}" }) { destination ->
                            val rule = rules.firstOrNull {
                                it.sourcePlatform == selectedSource && it.destinationAccountId == destination.id
                            }
                            val key = RouteKey(selectedSource, destination.id)
                            RouteCard(
                                source = selectedSource,
                                destination = destination.platform,
                                enabled = rule != null,
                                includeHistory = rule?.includeHistory == true,
                                uploadMode = destination.capabilities().uploadFitMode,
                                busy = busyRoute == key,
                                onEnabledChange = { enabled ->
                                    updateRule(selectedSource, destination.id, enabled, false)
                                },
                                onHistoryChange = { include ->
                                    if (include) {
                                        historyConfirmation = HistoryConfirmation(
                                            selectedSource,
                                            destination.id,
                                            destination.platform.displayName,
                                        )
                                    } else {
                                        updateRule(selectedSource, destination.id, true, false)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(FitSpacing.lg),
        )
    }

    historyConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { historyConfirmation = null },
            title = { Text("同时补发历史活动？") },
            text = {
                Text("现有的 ${confirmation.source.displayName} 活动会批量加入 ${confirmation.destinationName} 队列。数量较多时需要一些时间。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        historyConfirmation = null
                        updateRule(confirmation.source, confirmation.destinationId, true, true)
                    },
                ) { Text("确认补发") }
            },
            dismissButton = { TextButton(onClick = { historyConfirmation = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SourceRouteHeader(source: Platform, enabledCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
    ) {
        PlatformAvatar(source, 36.dp)
        Column(Modifier.weight(1f)) {
            Text("${source.displayName} 的去向", style = MaterialTheme.typography.titleMedium)
            Text(
                "选择需要自动补齐的平台",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(
            "$enabledCount 已开启",
            if (enabledCount > 0) StatusTone.SUCCESS else StatusTone.NEUTRAL,
        )
    }
}

@Composable
private fun RouteCard(
    source: Platform,
    destination: Platform,
    enabled: Boolean,
    includeHistory: Boolean,
    uploadMode: ConnectorExecution,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onHistoryChange: (Boolean) -> Unit,
) {
    FitCard(
        contentPadding = PaddingValues(0.dp),
        selected = enabled,
        border = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = FitSpacing.rowPaddingH, vertical = FitSpacing.rowPaddingV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            PlatformRouteAvatar(source, destination, 28.dp)
            Column(Modifier.weight(1f).padding(start = FitSpacing.sm)) {
                Text(
                    "${source.displayName} → ${destination.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    "${executionText(uploadMode)}上传",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
        }
        if (enabled) {
            SettingsDivider(startInset = 0.dp)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = FitSpacing.rowPaddingH, vertical = FitSpacing.rowPaddingV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = if (includeHistory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(FitSpacing.iconMd),
                )
                Column(Modifier.weight(1f)) {
                    Text("补发现有历史", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (includeHistory) "历史活动已加入投递队列" else "默认只同步开启后的新活动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = includeHistory, onCheckedChange = onHistoryChange, enabled = !busy)
            }
        }
    }
}

private fun PlatformAccountEntity?.capabilities(): ConnectorCapabilities {
    if (this == null) return ConnectorCapabilities()
    return ConnectorCapabilities.deserialize(capabilitySnapshot)
        ?: PlatformCapabilityRegistry.capabilities(platform)
}
