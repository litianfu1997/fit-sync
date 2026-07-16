package com.fitsync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitsync.app.FitSyncApp
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.ImportJobStatus
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.components.EmptyState
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.SectionHeader
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.importStatusText
import com.fitsync.app.ui.theme.FitBlue
import com.fitsync.app.ui.theme.FitOrange
import com.fitsync.app.ui.theme.FitOrangeSoft
import com.fitsync.app.ui.theme.FitRed
import com.fitsync.app.ui.theme.FitTeal
import com.fitsync.app.web.PlatformWebActivity
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onOpenActivities: () -> Unit = {},
    onOpenJobs: (SyncJobFilter) -> Unit = {},
    onOpenPlatforms: () -> Unit = {},
    onOpenRules: () -> Unit = {},
) {
    val db = FitSyncApp.instance.database
    val context = LocalContext.current
    val stats by remember {
        combine(
            db.canonicalActivityDao().observeCount(),
            db.canonicalActivityDao().observeCountByDedup(DedupStatus.NEEDS_REVIEW),
            db.deliveryJobDao().observeCountByStatus(DeliveryJobStatus.SUCCEEDED),
            db.deliveryJobDao().observePendingCount(),
            db.deliveryJobDao().observeCountByStatus(DeliveryJobStatus.FAILED),
        ) { discovered, review, synced, pending, failed -> HomeStats(discovered, synced, pending, review, failed) }
    }.collectAsState(initial = HomeStats())
    val recentImports by db.importJobDao().observeRecent().collectAsState(initial = emptyList())
    val waitingJobs by db.deliveryJobDao().observeWaitingForUser().collectAsState(initial = emptyList())
    val accounts by db.platformAccountDao().observeAll().collectAsState(initial = emptyList())
    val settings by db.appSettingsDao().observe().collectAsState(initial = null)
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "FitSync", subtitle = "本地优先的骑行数据中枢")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SyncHero(
                    autoSyncEnabled = settings?.autoSyncEnabled != false,
                    waitingCount = waitingJobs.size,
                    onSync = { SyncScheduler.enqueueNow(context) },
                )
            }
            item {
                StatsPanel(
                    stats = stats,
                    onOpenActivities = onOpenActivities,
                    onOpenJobs = onOpenJobs,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("快速开始", "两步完成自动同步")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickAction(
                            title = "连接平台",
                            subtitle = "登录并验证账号",
                            icon = Icons.Default.Devices,
                            accent = FitBlue,
                            modifier = Modifier.weight(1f),
                            onClick = onOpenPlatforms,
                        )
                        QuickAction(
                            title = "同步自动化",
                            subtitle = "配置来源与目标",
                            icon = Icons.Default.Route,
                            accent = FitOrange,
                            modifier = Modifier.weight(1f),
                            onClick = onOpenRules,
                        )
                    }
                }
            }
            if (waitingJobs.isNotEmpty()) {
                item { SectionHeader("需要你处理", "登录、验证码或网页上传需要确认") }
                items(waitingJobs.take(4), key = { "waiting-${it.id}" }) { job ->
                    val account = accounts.firstOrNull { it.id == job.destinationAccountId }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(0.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            account?.let { PlatformAvatar(it.platform, 42.dp) }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(account?.platform?.displayName ?: "未知平台", style = MaterialTheme.typography.titleSmall)
                                    StatusPill("需要处理", StatusTone.WARNING)
                                }
                                Text(
                                    job.errorMessage ?: "请进入平台网页继续操作",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (account != null) {
                                Button(onClick = {
                                    context.startActivity(
                                        PlatformWebActivity.createIntent(context, account.platform, job.id, job.userActionUrl),
                                    )
                                }) { Text("继续") }
                            }
                        }
                    }
                }
            }
            item {
                SectionHeader(
                    title = "最近导入",
                    subtitle = if (stats.review > 0) "${stats.review} 条活动需要复核" else "系统分享和平台拉取记录",
                    trailing = if (stats.review > 0) ({ StatusPill("待复核 ${stats.review}", StatusTone.WARNING) }) else null,
                )
            }
            if (recentImports.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有活动",
                        body = "登录平台开始拉取，或从任意骑行 App 将 FIT 文件分享到这里。",
                        icon = Icons.Default.CloudSync,
                    )
                }
            } else {
                items(recentImports.take(6), key = { "import-${it.id}" }) { job ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(0.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier.clip(CircleShape)
                                    .background(importTone(job.status).first.copy(alpha = 0.12f)).padding(10.dp),
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, tint = importTone(job.status).first)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(job.displayName ?: "未命名 FIT", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(
                                    dateFormat.format(Date(job.createdAtEpochMs)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                job.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            }
                            StatusPill(importStatusText(job.status), importTone(job.status).second)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsPanel(
    stats: HomeStats,
    onOpenActivities: () -> Unit,
    onOpenJobs: (SyncJobFilter) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(
            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
        ) {
            Row(Modifier.fillMaxWidth()) {
                StatCell(
                    label = "本地活动",
                    value = stats.discovered.toString(),
                    icon = Icons.Default.FileDownload,
                    accent = FitBlue,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenActivities,
                )
                Box(Modifier.width(1.dp).height(78.dp).background(MaterialTheme.colorScheme.outlineVariant))
                StatCell(
                    label = "同步完成",
                    value = stats.synced.toString(),
                    icon = Icons.Default.CheckCircle,
                    accent = FitTeal,
                    modifier = Modifier.weight(1f),
                ) { onOpenJobs(SyncJobFilter.COMPLETED) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth()) {
                StatCell(
                    label = "等待任务",
                    value = stats.pending.toString(),
                    icon = Icons.Default.History,
                    accent = FitOrange,
                    modifier = Modifier.weight(1f),
                ) { onOpenJobs(SyncJobFilter.WAITING) }
                Box(Modifier.width(1.dp).height(78.dp).background(MaterialTheme.colorScheme.outlineVariant))
                StatCell(
                    label = "同步失败",
                    value = stats.failed.toString(),
                    icon = Icons.Default.ErrorOutline,
                    accent = FitRed,
                    modifier = Modifier.weight(1f),
                ) { onOpenJobs(SyncJobFilter.FAILED) }
            }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(MaterialTheme.shapes.small)
                .background(accent.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
        }
        Column {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .padding(15.dp),
        ) {
            Box(
                modifier = Modifier.size(38.dp).clip(MaterialTheme.shapes.small)
                    .background(accent.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Text(title, modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncHero(autoSyncEnabled: Boolean, waitingCount: Int, onSync: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(Color(0xFF111827), Color(0xFF21170F), Color(0xFF3B1D08))))
            .padding(22.dp),
    ) {
        Box(
            modifier = Modifier.align(Alignment.TopEnd).size(120.dp).clip(CircleShape)
                .background(FitOrange.copy(alpha = 0.16f)),
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(
                    modifier = Modifier.size(30.dp).clip(CircleShape).background(FitOrange.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Sync, contentDescription = null, tint = FitOrangeSoft, modifier = Modifier.size(17.dp)) }
                Text(
                    if (autoSyncEnabled) "自动同步已开启" else "当前为手动同步",
                    color = Color.White.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                "让每一次骑行\n自由流动",
                modifier = Modifier.padding(top = 20.dp),
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                if (waitingCount > 0) "$waitingCount 个任务等待确认，处理后即可继续" else "一处导入，多平台自动补齐，全程只在本机处理。",
                modifier = Modifier.padding(top = 10.dp),
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onSync,
                modifier = Modifier.padding(top = 22.dp).fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FitOrange, contentColor = Color.White),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("立即同步全部", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun importTone(status: ImportJobStatus): Pair<Color, StatusTone> = when (status) {
    ImportJobStatus.READY, ImportJobStatus.DUPLICATE_FILE -> FitTeal to StatusTone.SUCCESS
    ImportJobStatus.INVALID -> FitRed to StatusTone.ERROR
    ImportJobStatus.NEEDS_REVIEW -> FitOrange to StatusTone.WARNING
    else -> FitBlue to StatusTone.INFO
}

private data class HomeStats(
    val discovered: Int = 0,
    val synced: Int = 0,
    val pending: Int = 0,
    val review: Int = 0,
    val failed: Int = 0,
)
