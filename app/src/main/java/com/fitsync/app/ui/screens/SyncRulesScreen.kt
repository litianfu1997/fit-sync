package com.fitsync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitsync.app.FitSyncApp
import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.components.EmptyState
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.executionText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RouteKey(val source: Platform, val destinationId: Long)

@Composable
fun SyncRulesScreen() {
    val app = FitSyncApp.instance
    val accounts by app.database.platformAccountDao().observeAll().collectAsState(initial = emptyList())
    val rules by app.database.syncRuleDao().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val connectedAccounts = accounts.filter { it.status != AccountStatus.DISABLED }
    val sources = listOf(Platform.LOCAL_IMPORT) + connectedAccounts.map { it.platform }
    var busyRoute by remember { mutableStateOf<RouteKey?>(null) }

    fun updateRule(source: Platform, destinationId: Long, enabled: Boolean, includeHistory: Boolean) {
        val key = RouteKey(source, destinationId)
        busyRoute = key
        scope.launch(Dispatchers.IO) {
            runCatching {
                app.syncCoordinator.planner.setRule(source, destinationId, enabled, includeHistory)
                SyncScheduler.enqueueNow(context)
            }
            withContext(Dispatchers.Main) { busyRoute = null }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "同步规则", subtitle = "决定活动从哪里来、要发送到哪里")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(42.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) }
                        Column(Modifier.weight(1f)) {
                            Text("启用规则即授权自动上传", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "默认只发送规则开启后的新活动；历史活动需单独开启回填。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        StatusPill("已启用 ${rules.count { it.enabled }}", StatusTone.SUCCESS)
                    }
                }
            }
            if (connectedAccounts.isEmpty()) {
                item {
                    EmptyState(
                        title = "先连接一个平台",
                        body = "连接账号后，就能配置“来源 → 目标”的自动同步通道。",
                        icon = Icons.Default.Link,
                    )
                }
            } else {
                items(sources.distinct(), key = { "source-${it.name}" }) { source ->
                    val destinations = connectedAccounts.filter { account ->
                        account.platform != source && PlatformCapabilityRegistry.capabilities(account.platform).canUploadActivity
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PlatformAvatar(source, 46.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(source.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text("活动来源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val enabledCount = rules.count { it.sourcePlatform == source && it.enabled }
                                StatusPill("$enabledCount 个目标", if (enabledCount > 0) StatusTone.INFO else StatusTone.NEUTRAL)
                            }
                            if (destinations.isEmpty()) {
                                Text("没有其他可上传的平台", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            destinations.forEach { destination ->
                                val rule = rules.firstOrNull {
                                    it.sourcePlatform == source && it.destinationAccountId == destination.id
                                }
                                val key = RouteKey(source, destination.id)
                                val isBusy = busyRoute == key
                                Column(
                                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        PlatformAvatar(destination.platform, 36.dp)
                                        Column(Modifier.weight(1f)) {
                                            Text(destination.platform.displayName, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "上传方式：${executionText(PlatformCapabilityRegistry.capabilities(destination.platform).uploadFitMode)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (isBusy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                        else Switch(
                                            checked = rule != null,
                                            onCheckedChange = { enabled ->
                                                updateRule(source, destination.id, enabled, rule?.includeHistory ?: false)
                                            },
                                        )
                                    }
                                    if (rule != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Icon(
                                                Icons.Default.History,
                                                contentDescription = null,
                                                tint = if (rule.includeHistory) MaterialTheme.colorScheme.tertiary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text("回填已有历史", style = MaterialTheme.typography.labelLarge)
                                                Text(
                                                    if (rule.includeHistory) "已加入历史活动投递任务" else "关闭时只处理以后出现的新活动",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Switch(
                                                checked = rule.includeHistory,
                                                onCheckedChange = { include -> updateRule(source, destination.id, true, include) },
                                                enabled = !isBusy,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
