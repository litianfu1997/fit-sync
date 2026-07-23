package com.fitsync.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

import androidx.compose.material3.AlertDialog
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.dao.SyncJobListItem
import com.fitsync.app.data.db.entity.DeliveryJobEntity
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
import kotlinx.coroutines.launch

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
        COMPLETED -> status == DeliveryJobStatus.SUCCEEDED || status == DeliveryJobStatus.DUPLICATE_REMOTE
        FAILED -> status in PROBLEM_STATUSES
    }

    companion object {
        private val WAITING_STATUSES = setOf(
            DeliveryJobStatus.PENDING,
            DeliveryJobStatus.UPLOADING,
            DeliveryJobStatus.WAITING_REMOTE_PROCESS,
        )
        private val PROBLEM_STATUSES = setOf(
            DeliveryJobStatus.FAILED,
            DeliveryJobStatus.WAITING_USER,
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
    val pager = remember(filter) {
        Pager(
            config = PagingConfig(
                pageSize = 30,
                prefetchDistance = 10,
                enablePlaceholders = false,
                maxSize = 150,
            ),
            pagingSourceFactory = { db.deliveryJobDao().pagingSource(filter.routeValue) },
        )
    }
    val jobs = pager.flow.collectAsLazyPagingItems()
    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }

    // 批量操作的选择状态
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = if (selectionMode) "已选中 ${selectedIds.size} 项" else filter.title,
            subtitle = if (selectionMode) null else "${jobs.itemCount} 条已加载",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                if (selectionMode) {
                    Row {
                        TextButton(onClick = {
                            selectionMode = false
                            selectedIds.clear()
                        }) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { showDeleteDialog = true }) {
                            Text("批量删除")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            if (selectedIds.isNotEmpty()) {
                                coroutineScope.launch {
                                    FitSyncApp.instance.syncCoordinator.retryJobs(selectedIds.toList())
                                    selectionMode = false
                                    selectedIds.clear()
                                }
                            }
                        }) {
                            Text("批量重试")
                        }
                    }
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (jobs.loadState.refresh is LoadState.NotLoading && jobs.itemCount == 0) {
                item {
                    EmptyState(
                        title = filter.emptyTitle,
                        body = filter.emptyBody,
                        icon = filter.icon,
                    )
                }
            } else {
                items(
                    count = jobs.itemCount,
                    key = jobs.itemKey { it.id },
                    contentType = { "sync-job" },
                ) { index ->
                    jobs[index]?.let { job ->
                        SyncJobCard(
                            job = job,
                            dateFormat = dateFormat,
                            onOpenActivity = onOpenActivity,
                            isSelectionMode = selectionMode,
                            selectedIds = selectedIds,
                            onToggleSelect = { id ->
                                if (selectionMode) {
                                    if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                                } else {
                                    onOpenActivity(job.canonicalActivityId)
                                }
                            },
                        )
                    }
                }
            }
        }

        if (showDeleteDialog && selectedIds.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("删除选中任务？") },
                text = {
                    Text("确定要删除 ${selectedIds.size} 个同步任务吗？此操作不可恢复，会同时清除关联的平台拷贝和导入记录。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            FitSyncApp.instance.syncCoordinator.deleteJobs(selectedIds.toList())
                            showDeleteDialog = false
                            selectionMode = false
                            selectedIds.clear()
                        }
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun SyncJobCard(
    job: SyncJobListItem,
    dateFormat: SimpleDateFormat,
    onOpenActivity: (Long) -> Unit,
    isSelectionMode: Boolean = false,
    selectedIds: Collection<Long> = emptySet(),
    onToggleSelect: (Long) -> Unit = {},
) {
    val cardModifier = if (isSelectionMode) {
        Modifier.fillMaxWidth().clickable { onToggleSelect(job.canonicalActivityId) }
    } else {
        Modifier.fillMaxWidth().clickable { onOpenActivity(job.canonicalActivityId) }
    }

    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = selectedIds.contains(job.canonicalActivityId),
                    onCheckedChange = { onToggleSelect(job.canonicalActivityId) },
                    modifier = Modifier.padding(start = 8.dp, end = 12.dp),
                )
            } else {
                job.destinationPlatform?.let { PlatformAvatar(it, 44.dp) }
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        job.activityTitle?.takeIf { it.isNotBlank() }
                            ?: job.activitySportType?.let(::sportLabel)
                            ?: "本地活动 #${job.canonicalActivityId}",
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    StatusPill(deliveryStatusText(job.status), deliveryStatusTone(job.status))
                }
                Text(
                    "目标 · ${job.destinationPlatform?.displayName ?: "未知平台"}  ·  ${dateFormat.format(Date(job.updatedAtEpochMs))}",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    deliveryJobDetail(job.errorCode, job.errorMessage, job.attemptCount),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.status in problemDeliveryStatuses) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                )
            }
            if (!isSelectionMode) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "查看活动详情",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val problemDeliveryStatuses = setOf(
    DeliveryJobStatus.FAILED,
    DeliveryJobStatus.WAITING_USER,
    DeliveryJobStatus.RETRY_WAIT,
)

internal fun deliveryJobDetail(job: DeliveryJobEntity): String {
    return deliveryJobDetail(job.errorCode, job.errorMessage, job.attemptCount)
}

private fun deliveryJobDetail(errorCode: String?, errorMessage: String?, attemptCount: Int): String {
    val reason = listOfNotNull(
        errorCode?.takeIf { it.isNotBlank() }?.let { "错误码 $it" },
        errorMessage?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    return reason.ifBlank {
        if (attemptCount > 0) "已尝试 $attemptCount 次" else "等待系统自动处理"
    }
}
