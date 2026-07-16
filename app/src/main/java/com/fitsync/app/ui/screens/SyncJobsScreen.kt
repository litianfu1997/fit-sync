package com.fitsync.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.components.EmptyState
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.deliveryStatusText
import com.fitsync.app.ui.components.deliveryStatusTone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SyncJobFilter(
    val routeValue: String,
    val title: String,
    val emptyTitle: String,
    val emptyBody: String,
    val icon: ImageVector,
) {
    WAITING(
        "waiting",
        "等待任务",
        "没有等待中的任务",
        "新建的同步任务和自动重试任务会显示在这里。",
        Icons.Default.History,
    ),
    COMPLETED(
        "completed",
        "同步完成",
        "还没有完成的同步",
        "成功同步到目标平台的活动会显示在这里。",
        Icons.Default.CloudDone,
    ),
    FAILED(
        "failed",
        "失败任务",
        "没有失败任务",
        "同步失败的原因会显示在这里，可进入活动详情重试。",
        Icons.Default.ErrorOutline,
    );

    fun includes(status: DeliveryJobStatus): Boolean = when (this) {
        WAITING -> status in WAITING_STATUSES
        COMPLETED -> status == DeliveryJobStatus.SUCCEEDED
        FAILED -> status == DeliveryJobStatus.FAILED
    }

    companion object {
        private val WAITING_STATUSES = setOf(
            DeliveryJobStatus.PENDING,
            DeliveryJobStatus.UPLOADING,
            DeliveryJobStatus.WAITING_USER,
            DeliveryJobStatus.WAITING_REMOTE_PROCESS,
            DeliveryJobStatus.RETRY_WAIT,
        )

        fun fromRoute(value: String?): SyncJobFilter = entries.firstOrNull { it.routeValue == value } ?: WAITING
    }
}

@Composable
fun SyncJobsScreen(
    filter: SyncJobFilter,
    onBack: () -> Unit,
    onOpenActivity: (Long) -> Unit,
) {
    val db = FitSyncApp.instance.database
    val allJobs by db.deliveryJobDao().observeAll().collectAsState(initial = emptyList())
    val activities by db.canonicalActivityDao().observeAll().collectAsState(initial = emptyList())
    val accounts by db.platformAccountDao().observeAll().collectAsState(initial = emptyList())
    val jobs = remember(allJobs, filter) { allJobs.filter { filter.includes(it.status) } }
    val activityById = remember(activities) { activities.associateBy { it.id } }
    val accountById = remember(accounts) { accounts.associateBy { it.id } }
    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = filter.title,
            subtitle = "${jobs.size} 条投递记录",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (jobs.isEmpty()) {
                item {
                    EmptyState(
                        title = filter.emptyTitle,
                        body = filter.emptyBody,
                        icon = filter.icon,
                    )
                }
            } else {
                items(jobs, key = { it.id }) { job ->
                    SyncJobCard(
                        job = job,
                        activity = activityById[job.canonicalActivityId],
                        account = accountById[job.destinationAccountId],
                        dateFormat = dateFormat,
                        onOpenActivity = onOpenActivity,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncJobCard(
    job: DeliveryJobEntity,
    activity: CanonicalActivityEntity?,
    account: PlatformAccountEntity?,
    dateFormat: SimpleDateFormat,
    onOpenActivity: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpenActivity(job.canonicalActivityId) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            account?.let { PlatformAvatar(it.platform, 44.dp) }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        activity?.title?.takeIf { it.isNotBlank() }
                            ?: activity?.sportType?.let(::sportLabel)
                            ?: "本地活动 #${job.canonicalActivityId}",
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    StatusPill(deliveryStatusText(job.status), deliveryStatusTone(job.status))
                }
                Text(
                    "目标 · ${account?.platform?.displayName ?: "未知平台"}  ·  ${dateFormat.format(Date(job.updatedAtEpochMs))}",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    job.errorMessage ?: if (job.attemptCount > 0) "已尝试 ${job.attemptCount} 次" else "等待系统自动处理",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.status == DeliveryJobStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "查看活动详情",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
