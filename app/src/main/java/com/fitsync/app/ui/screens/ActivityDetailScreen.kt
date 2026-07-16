package com.fitsync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.components.EmptyState
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.SectionHeader
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.deliveryStatusText
import com.fitsync.app.ui.components.deliveryStatusTone
import com.fitsync.app.ui.theme.FitOrange
import com.fitsync.app.ui.theme.FitOrangeSoft
import com.fitsync.app.web.PlatformWebActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityDetailScreen(activityId: Long, onBack: () -> Unit) {
    val app = FitSyncApp.instance
    val variants by app.database.activityVariantDao().observeByCanonical(activityId).collectAsState(initial = emptyList())
    val copies by app.database.platformCopyDao().observeByCanonical(activityId).collectAsState(initial = emptyList())
    val jobs by app.database.deliveryJobDao().observeByCanonical(activityId).collectAsState(initial = emptyList())
    val accounts by app.database.platformAccountDao().observeAll().collectAsState(initial = emptyList())
    var activity by remember { mutableStateOf<CanonicalActivityEntity?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }

    LaunchedEffect(activityId) {
        activity = withContext(Dispatchers.IO) { app.database.canonicalActivityDao().getById(activityId) }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "活动详情",
            subtitle = activity?.title ?: activity?.sportType?.let(::sportLabel),
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
            activity?.let { current ->
                item { ActivitySummary(current, dateFormat) }
            }
            item { SectionHeader("平台副本", "同一活动在各平台中的记录") }
            if (copies.isEmpty()) {
                item {
                    EmptyState(
                        title = "尚未同步到平台",
                        body = "从下方选择目标平台进行单次补发。",
                        icon = Icons.Default.CloudDone,
                    )
                }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            copies.forEach { copy ->
                                val account = accounts.firstOrNull { it.id == copy.platformAccountId }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    account?.let { PlatformAvatar(it.platform, 40.dp) }
                                    Column(Modifier.weight(1f)) {
                                        Text(account?.platform?.displayName ?: "未知平台", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "远端 ID · ${copy.remoteActivityId.take(24)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    StatusPill(if (copy.createdBySync) "FitSync 创建" else "来源记录", StatusTone.SUCCESS)
                                }
                            }
                        }
                    }
                }
            }
            item { SectionHeader("单次补发", "不会修改全局同步规则") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val activeAccounts = accounts.filter { it.status != AccountStatus.DISABLED }
                        if (activeAccounts.isEmpty()) {
                            Text("还没有可用的平台账号", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        activeAccounts.forEach { account ->
                            val copyExists = copies.any { it.platformAccountId == account.id }
                            val existingJob = jobs.firstOrNull { it.destinationAccountId == account.id }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PlatformAvatar(account.platform, 40.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(account.platform.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        when {
                                            copyExists -> "平台已有该活动"
                                            existingJob != null -> deliveryStatusText(existingJob.status)
                                            else -> "可创建一次投递任务"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    enabled = !copyExists && existingJob == null,
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            val result = runCatching {
                                                val id = app.syncCoordinator.planner.enqueueManual(activityId, account.id)
                                                SyncScheduler.enqueueNow(context)
                                                id
                                            }
                                            withContext(Dispatchers.Main) {
                                                actionMessage = result.fold(
                                                    onSuccess = { id ->
                                                        if (id > 0) "已加入 ${account.platform.displayName} 队列" else "该平台已有副本或任务"
                                                    },
                                                    onFailure = { "补发失败：${it.message ?: "请稍后重试"}" },
                                                )
                                            }
                                        }
                                    },
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text("补发", modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                        actionMessage?.let { StatusPill(it, StatusTone.INFO) }
                    }
                }
            }
            item { SectionHeader("本地 FIT", "原始文件与采集来源") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        variants.forEach { variant ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    modifier = Modifier.size(40.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(variant.sourcePlatform?.displayName ?: "未知来源", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${formatFileSize(variant.fileSize)} · 质量 ${variant.qualityScore ?: "--"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                StatusPill(if (variant.validationStatus.name.startsWith("VALID")) "校验通过" else "${variant.validationStatus}")
                            }
                        }
                    }
                }
            }
            item { SectionHeader("投递记录", "每个目标平台只会创建一个幂等任务") }
            if (jobs.isEmpty()) {
                item { Text("暂无投递记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            jobs.forEach { job ->
                                val account = accounts.firstOrNull { it.id == job.destinationAccountId }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    account?.let { PlatformAvatar(it.platform, 38.dp) }
                                    Column(Modifier.weight(1f)) {
                                        Text(account?.platform?.displayName ?: "未知平台", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            job.errorMessage ?: "尝试 ${job.attemptCount} 次",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (job.status == DeliveryJobStatus.FAILED) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        StatusPill(deliveryStatusText(job.status), deliveryStatusTone(job.status))
                                        when (job.status) {
                                            DeliveryJobStatus.WAITING_USER -> if (account != null) {
                                                OutlinedButton(
                                                    onClick = {
                                                        context.startActivity(
                                                            PlatformWebActivity.createIntent(
                                                                context,
                                                                account.platform,
                                                                job.id,
                                                                job.userActionUrl,
                                                            ),
                                                        )
                                                    },
                                                    modifier = Modifier.padding(top = 6.dp),
                                                ) { Text("继续处理") }
                                            }
                                            DeliveryJobStatus.FAILED, DeliveryJobStatus.RETRY_WAIT -> {
                                                OutlinedButton(
                                                    onClick = {
                                                        scope.launch(Dispatchers.IO) {
                                                            val result = runCatching {
                                                                app.syncCoordinator.retryJob(job.id)
                                                                SyncScheduler.enqueueNow(context)
                                                            }
                                                            withContext(Dispatchers.Main) {
                                                                actionMessage = result.fold(
                                                                    onSuccess = { "已重新加入同步队列" },
                                                                    onFailure = { "重试失败：${it.message ?: "请稍后重试"}" },
                                                                )
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.padding(top = 6.dp),
                                                ) {
                                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Text("重试", modifier = Modifier.padding(start = 4.dp))
                                                }
                                            }
                                            else -> Unit
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

@Composable
private fun ActivitySummary(activity: CanonicalActivityEntity, dateFormat: SimpleDateFormat) {
    Box(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(Color(0xFF111827), Color(0xFF28180D), Color(0xFF48220A))))
            .padding(22.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.medium)
                        .background(FitOrange.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(sportIcon(activity.sportType), contentDescription = null, tint = FitOrangeSoft) }
                Column {
                    Text(
                        activity.title?.takeIf { it.isNotBlank() } ?: sportLabel(activity.sportType),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Text(dateFormat.format(Date(activity.startTimeEpochMs)), color = Color.White.copy(alpha = 0.8f))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SummaryStat("距离", activity.distanceMeters?.let { "%.1f km".format(it / 1000) } ?: "--")
                SummaryStat("时长", activity.elapsedSeconds?.let(::formatDuration) ?: "--")
                SummaryStat("均心率", activity.avgHeartRate?.let { "$it bpm" } ?: "--")
                SummaryStat("爬升", activity.elevationGain?.let { "%.0f m".format(it) } ?: "--")
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(label, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
