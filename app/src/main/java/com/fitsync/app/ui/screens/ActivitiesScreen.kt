package com.fitsync.app.ui.screens

import android.os.Trace
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.map
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.dao.ActivityListItem
import com.fitsync.app.data.db.dao.ActivityListQuery
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.components.EmptyState
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.theme.FitBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

enum class ActivityFilter(val label: String) {
    ALL("全部"), CYCLING("骑行"), RUNNING("跑步"), REVIEW("待复核")
}

/** 活动列表按活动开始时间排序。默认倒序（最新在前）。 */
private enum class ActivityTimeSort(val label: String) {
    NEWEST_FIRST("时间倒序"),
    OLDEST_FIRST("时间正序"),
}

@Composable
fun ActivitiesScreen(
    filter: ActivityFilter = ActivityFilter.ALL,
    onFilterChange: (ActivityFilter) -> Unit = {},
    onOpenActivity: (Long) -> Unit = {},
    onOpenPlatforms: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    var timeSort by rememberSaveable { mutableStateOf(ActivityTimeSort.NEWEST_FIRST) }
    val dateFormat = remember { SimpleDateFormat("MM月dd日 · HH:mm", Locale.getDefault()) }

    LaunchedEffect(query) {
        delay(250)
        debouncedQuery = query.trim()
    }
    LaunchedEffect(Unit) {
        Trace.beginSection("FitSync:activity_list_first_frame")
        try {
            withFrameNanos { }
        } finally {
            Trace.endSection()
        }
    }
    val listQuery = remember(debouncedQuery, filter, timeSort) {
        ActivityListQuery(
            searchText = debouncedQuery,
            filter = filter.name,
            oldestFirst = timeSort == ActivityTimeSort.OLDEST_FIRST,
        )
    }
    val pager = remember(listQuery) {
        Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 15,
                enablePlaceholders = false,
                maxSize = 200,
            ),
            pagingSourceFactory = {
                FitSyncApp.instance.database.canonicalActivityDao().pagingSource(
                    query = listQuery.searchText,
                    filter = listQuery.filter,
                    oldestFirst = listQuery.oldestFirst,
                )
            },
        )
    }
    val formattedActivities = remember(pager, dateFormat) {
        pager.flow.map { pagingData ->
            pagingData.map { it.toUiModel(dateFormat) }
        }
    }
    val activities = formattedActivities.collectAsLazyPagingItems()

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "运动记录", subtitle = "所有来源已合并去重")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索标题或运动类型") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotBlank()) {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "清除") } }
                    } else null,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${activities.itemCount} 条已加载",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilterChip(
                        modifier = Modifier.height(36.dp),
                        selected = true,
                        onClick = {
                            timeSort = when (timeSort) {
                                ActivityTimeSort.NEWEST_FIRST -> ActivityTimeSort.OLDEST_FIRST
                                ActivityTimeSort.OLDEST_FIRST -> ActivityTimeSort.NEWEST_FIRST
                            }
                        },
                        label = { Text(timeSort.label) },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "切换时间排序",
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActivityFilter.entries.forEach { option ->
                        FilterChip(
                            modifier = Modifier.height(44.dp),
                            selected = filter == option,
                            onClick = { onFilterChange(option) },
                            label = { Text(option.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
            if (activities.loadState.refresh is LoadState.NotLoading && activities.itemCount == 0) {
                item {
                    EmptyState(
                        title = if (debouncedQuery.isBlank() && filter == ActivityFilter.ALL) "还没有活动" else "没有匹配结果",
                        body = if (debouncedQuery.isBlank() && filter == ActivityFilter.ALL) {
                            "从平台拉取，或把 FIT 文件分享到 FitSync。"
                        } else {
                            "试试其他关键词或筛选条件。"
                        },
                        icon = Icons.Default.Route,
                        actionLabel = if (debouncedQuery.isBlank() && filter == ActivityFilter.ALL) "连接平台" else null,
                        onAction = if (debouncedQuery.isBlank() && filter == ActivityFilter.ALL) onOpenPlatforms else null,
                    )
                }
            } else {
                items(
                    count = activities.itemCount,
                    key = activities.itemKey { it.id },
                    contentType = { "activity" },
                ) { index ->
                    activities[index]?.let { activity ->
                        ActivityCard(activity, onOpenActivity)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activity: ActivityListUiModel,
    onOpenActivity: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpenActivity(activity.id) },
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
            Box(
                modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.medium)
                    .background(FitBlue.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(sportIcon(activity.sportType), contentDescription = null, tint = FitBlue)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        activity.title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    if (activity.dedupStatus == DedupStatus.NEEDS_REVIEW) {
                        StatusPill("待复核", StatusTone.WARNING)
                    }
                }
                Text(
                    activity.dateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ActivityStat(activity.distanceText, "距离")
                    ActivityStat(activity.durationText, "时长")
                    ActivityStat(activity.sportText, "运动")
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class ActivityListUiModel(
    val id: Long,
    val title: String,
    val sportType: SportType,
    val dedupStatus: DedupStatus,
    val dateText: String,
    val distanceText: String,
    val durationText: String,
    val sportText: String,
)

private fun ActivityListItem.toUiModel(dateFormat: SimpleDateFormat): ActivityListUiModel = ActivityListUiModel(
    id = id,
    title = title?.takeIf { it.isNotBlank() } ?: sportLabel(sportType),
    sportType = sportType,
    dedupStatus = dedupStatus,
    dateText = dateFormat.format(Date(startTimeEpochMs)),
    distanceText = distanceMeters?.let { "%.1f km".format(it / 1000) } ?: "--",
    durationText = elapsedSeconds?.let(::formatDuration) ?: "--",
    sportText = sportLabel(sportType),
)

@Composable
private fun ActivityStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.labelLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun sportLabel(sport: SportType): String = when (sport) {
    SportType.CYCLING -> "骑行"
    SportType.INDOOR_CYCLING -> "室内骑行"
    SportType.MTB -> "山地骑行"
    SportType.GRAVEL -> "砾石骑行"
    SportType.RUNNING -> "跑步"
    SportType.OTHER -> "其他运动"
    SportType.UNKNOWN -> "未知运动"
}

fun sportIcon(sport: SportType): ImageVector = when (sport) {
    SportType.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    SportType.INDOOR_CYCLING -> Icons.Default.FitnessCenter
    SportType.CYCLING, SportType.MTB, SportType.GRAVEL -> Icons.AutoMirrored.Filled.DirectionsBike
    SportType.OTHER, SportType.UNKNOWN -> Icons.Default.Route
}

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "%d:%02d".format(h, m) else "%d 分".format(m)
}
