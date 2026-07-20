package com.fitsync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitsync.app.FitSyncApp
import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.connectors.ConnectorCapabilities
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.components.EmptyState
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.SectionHeader
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.executionText
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
    val accounts by app.database.platformAccountDao().observeAll().collectAsState(initial = emptyList())
    val rules by app.database.syncRuleDao().observeAll().collectAsState(initial = emptyList())
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
            AppTopBar(
                title = "同步自动化",
                subtitle = "选择来源，再决定活动去向",
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
                if (connectedAccounts.isEmpty()) {
                    item {
                        EmptyState(
                            title = "先连接一个平台",
                            body = "连接账号后，就能把本地导入或平台活动自动发送到其他平台。",
                            icon = Icons.Default.Link,
                            actionLabel = "去连接平台",
                            onAction = onOpenPlatforms,
                        )
                    }
                } else {
                    item { SectionHeader("活动从哪里来", "一次只配置一个来源，路径更清楚") }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            sources.forEach { source ->
                                FilterChip(
                                    modifier = Modifier.height(44.dp),
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
                            EmptyState(
                                title = "没有可用目标",
                                body = "再连接一个支持上传 FIT 的平台，即可创建自动同步路径。",
                                icon = Icons.Default.Route,
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
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
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
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlatformAvatar(source, 48.dp)
        Column(Modifier.weight(1f)) {
            Text("${source.displayName} 的去向", style = MaterialTheme.typography.titleMedium)
            Text("选择需要自动补齐的平台", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        com.fitsync.app.ui.components.StatusPill(
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
    uploadMode: com.fitsync.app.domain.model.ConnectorExecution,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onHistoryChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .border(
                    1.dp,
                    if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.outlineVariant,
                    MaterialTheme.shapes.large,
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlatformAvatar(source, 32.dp)
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                PlatformAvatar(destination, 38.dp)
                Column(Modifier.weight(1f)) {
                    Text(destination.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${executionText(uploadMode)}上传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = if (includeHistory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("补发现有历史", style = MaterialTheme.typography.labelLarge)
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
}

private fun PlatformAccountEntity?.capabilities(): ConnectorCapabilities {
    if (this == null) return ConnectorCapabilities()
    return ConnectorCapabilities.deserialize(capabilitySnapshot)
        ?: PlatformCapabilityRegistry.capabilities(platform)
}
