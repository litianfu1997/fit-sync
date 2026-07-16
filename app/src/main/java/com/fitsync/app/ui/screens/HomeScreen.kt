package com.fitsync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import com.fitsync.app.ui.components.MetricCard
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.SectionHeader
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.importStatusText
import com.fitsync.app.ui.theme.FitBlue
import com.fitsync.app.ui.theme.FitOrange
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
        AppTopBar(title = "同步中心", subtitle = "所有骑行记录，都在这台设备上流转")
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        "本地活动",
                        stats.discovered.toString(),
                        Icons.Default.FileDownload,
                        FitBlue,
                        Modifier.weight(1f),
                        onOpenActivities,
                    )
                    MetricCard(
                        "同步完成",
                        stats.synced.toString(),
                        Icons.Default.CheckCircle,
                        FitTeal,
                        Modifier.weight(1f),
                    ) { onOpenJobs(SyncJobFilter.COMPLETED) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        "等待任务",
                        stats.pending.toString(),
                        Icons.Default.History,
                        FitOrange,
                        Modifier.weight(1f),
                    ) { onOpenJobs(SyncJobFilter.WAITING) }
                    MetricCard(
                        "失败任务",
                        stats.failed.toString(),
                        Icons.Default.ErrorOutline,
                        FitRed,
                        Modifier.weight(1f),
                    ) { onOpenJobs(SyncJobFilter.FAILED) }
                }
            }
            if (waitingJobs.isNotEmpty()) {
                item { SectionHeader("需要你处理", "登录、验证码或网页上传需要确认") }
                items(waitingJobs.take(4), key = { "waiting-${it.id}" }) { job ->
                    val account = accounts.firstOrNull { it.id == job.destinationAccountId }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(1.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
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
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
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
private fun SyncHero(autoSyncEnabled: Boolean, waitingCount: Int, onSync: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(Color(0xFF315DE8), Color(0xFF1787CE), Color(0xFF0FAF9A))))
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(10.dp),
                ) { Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("准备同步", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (autoSyncEnabled) "后台同步已开启 · 每 15 分钟检查" else "后台同步已暂停 · 仍可手动执行",
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (waitingCount > 0) {
                Text(
                    "$waitingCount 个任务正在等待你的确认",
                    modifier = Modifier.padding(top = 16.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onSync,
                modifier = Modifier.padding(top = 18.dp).fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = FitBlue),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("立即同步", modifier = Modifier.padding(start = 8.dp))
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
