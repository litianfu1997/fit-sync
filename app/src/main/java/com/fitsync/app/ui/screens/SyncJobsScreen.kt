package com.fitsync.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.dao.SyncJobListItem
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.components.EmptyStateVariant
import com.fitsync.app.ui.components.FitEmptyState
import com.fitsync.app.ui.components.FitListRow
import com.fitsync.app.ui.components.FitPagingFooter
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.deliveryStatusText
import com.fitsync.app.ui.components.deliveryStatusTone
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

enum class SyncJobFilter(
    val routeValue: String,
    val title: String,
    val emptyTitle: String,
    val emptyBody: String,
) {
    WAITING(
        "waiting",
        "等待任务",
        "没有等待中的任务",
        "新建的同步任务和自动重试任务会显示在这里。",
    ),
    COMPLETED(
        "completed",
        "同步完成",
        "还没有完成的同步",
        "成功同步到目标平台的活动会显示在这里。",
    ),
    FAILED(
        "failed",
        "失败任务",
        "没有失败任务",
        "同步失败的原因会显示在这里，可进入活动详情重试。",
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
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // 批量操作的选择状态
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 进入选择模式时自动滚回顶部，确保「全选」行立即可见
    LaunchedEffect(selectionMode) {
        if (selectionMode) listState.scrollToItem(0)
    }

    // 全选作用于当前已加载的任务（Paging maxSize=150；滚动加载更多后会自动重算）
    val loadedIds = remember(jobs.itemCount) {
        (0 until jobs.itemCount).mapNotNull { jobs[it]?.id }.distinct()
    }
    val selectAllState = when {
        selectedIds.isEmpty() -> ToggleableState.Off
        loadedIds.isNotEmpty() && selectedIds.containsAll(loadedIds) -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    fun toggleSelectAll() {
        if (selectAllState == ToggleableState.On) {
            selectedIds.clear()
        } else {
            selectedIds.clear()
            selectedIds.addAll(loadedIds)
        }
    }

    Column(Modifier.fillMaxSize()) {
        FitTopBar(
            title = if (selectionMode) "已选中 ${selectedIds.size} 项" else filter.title,
            subtitle = if (selectionMode) null else "${jobs.itemCount} 条已加载",
            onBack = onBack,
            actions = {
                if (selectionMode) {
                    IconButton(onClick = {
                        selectionMode = false
                        selectedIds.clear()
                    }) { Icon(Icons.Default.Close, contentDescription = "取消") }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "批量删除")
                    }
                    if (filter != SyncJobFilter.COMPLETED) {
                        IconButton(onClick = {
                            if (selectedIds.isNotEmpty()) {
                                coroutineScope.launch {
                                    FitSyncApp.instance.syncCoordinator.retryJobs(selectedIds.toList())
                                    SyncScheduler.enqueueDeliveryNow(context)
                                    selectionMode = false
                                    selectedIds.clear()
                                }
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "批量重试")
                        }
                    }
                } else {
                    IconButton(onClick = { selectionMode = true }) {
                        Icon(Icons.Default.Checklist, contentDescription = "多选")
                    }
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = FitSpacing.screenGutter,
                top = FitSpacing.sm,
                end = FitSpacing.screenGutter,
                bottom = FitSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            if (jobs.loadState.refresh is LoadState.NotLoading && jobs.itemCount == 0) {
                item {
                    FitEmptyState(
                        title = filter.emptyTitle,
                        body = filter.emptyBody,
                        variant = if (filter == SyncJobFilter.COMPLETED) {
                            EmptyStateVariant.FirstRun
                        } else {
                            EmptyStateVariant.AllDone
                        },
                    )
                }
            } else {
                if (selectionMode && jobs.itemCount > 0) {
                    item(key = "select-all") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = FitSpacing.sm, end = FitSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
                        ) {
                            TriStateCheckbox(
                                state = selectAllState,
                                onClick = { toggleSelectAll() },
                            )
                            Text("全选", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "已选 ${selectedIds.size}/${jobs.itemCount}",
                                style = FitType.dataLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(
                    count = jobs.itemCount,
                    key = jobs.itemKey { it.id },
                    contentType = { "sync-job" },
                ) { index ->
                    jobs[index]?.let { job ->
                        SyncJobRow(
                            job = job,
                            dateFormat = dateFormat,
                            onOpenActivity = onOpenActivity,
                            isSelectionMode = selectionMode,
                            selectedIds = selectedIds,
                            onToggleSelect = { id ->
                                if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                            },
                            onLongSelect = { id ->
                                selectionMode = true
                                if (!selectedIds.contains(id)) selectedIds.add(id)
                            },
                        )
                    }
                }
                item(key = "paging-footer") {
                    FitPagingFooter(
                        append = jobs.loadState.append,
                        itemCount = jobs.itemCount,
                        onRetry = { jobs.retry() },
                    )
                }
            }
        }

        if (showDeleteDialog && selectedIds.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("删除选中任务？") },
                text = {
                    Text("确定要删除选中的 ${selectedIds.size} 个同步任务吗？此操作只会删除这些同步任务记录，不会删除已上传到平台的文件，也不会删除本地活动。")
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
private fun SyncJobRow(
    job: SyncJobListItem,
    dateFormat: SimpleDateFormat,
    onOpenActivity: (Long) -> Unit,
    isSelectionMode: Boolean = false,
    selectedIds: Collection<Long> = emptySet(),
    onToggleSelect: (Long) -> Unit = {},
    onLongSelect: (Long) -> Unit = {},
) {
    val title = job.activityTitle?.takeIf { it.isNotBlank() }
        ?: job.activitySportType?.let(::sportLabel)
        ?: "未命名骑行"
    FitListRow(
        title = title,
        subtitle = "${job.destinationPlatform?.displayName ?: "未知平台"} · ${dateFormat.format(Date(job.updatedAtEpochMs))}",
        supporting = deliveryJobDetail(job.errorCode, job.errorMessage, job.attemptCount),
        leading = {
            if (isSelectionMode) {
                Checkbox(
                    checked = selectedIds.contains(job.id),
                    onCheckedChange = { onToggleSelect(job.id) },
                )
            } else {
                job.destinationPlatform?.let { PlatformAvatar(it, 32.dp) }
            }
        },
        trailing = { StatusPill(deliveryStatusText(job.status), deliveryStatusTone(job.status), compact = true) },
        onClick = {
            if (isSelectionMode) onToggleSelect(job.id) else onOpenActivity(job.canonicalActivityId)
        },
        onLongClick = { onLongSelect(job.id) },
        selected = isSelectionMode && selectedIds.contains(job.id),
        showChevron = !isSelectionMode,
    )
}

internal val problemDeliveryStatuses = setOf(
    DeliveryJobStatus.FAILED,
    DeliveryJobStatus.WAITING_USER,
    DeliveryJobStatus.RETRY_WAIT,
)

/** 投递失败原因：先说「发生了什么」，再给「你能做什么」，错误码不再占据主位。 */
internal fun deliveryJobDetail(job: DeliveryJobEntity): String {
    return deliveryJobDetail(job.errorCode, job.errorMessage, job.attemptCount)
}

internal fun deliveryJobDetail(errorCode: String?, errorMessage: String?, attemptCount: Int): String {
    val code = errorCode?.takeIf { it.isNotBlank() }
    val message = errorMessage?.takeIf { it.isNotBlank() }
    return when {
        code == "AUTH_EXPIRED" -> "登录已失效，请重新登录"
        code != null && code.contains("429") -> "平台暂时限流，稍后会自动重试"
        message != null -> message
        code != null -> "同步遇到问题，稍后会自动重试"
        attemptCount > 0 -> "已自动重试 $attemptCount 次"
        else -> "等待系统自动处理"
    }
}

/** 次级信息：自动重试次数。 */
internal fun deliveryJobRetryNote(attemptCount: Int): String? =
    if (attemptCount > 0) "已自动重试 $attemptCount 次" else null
