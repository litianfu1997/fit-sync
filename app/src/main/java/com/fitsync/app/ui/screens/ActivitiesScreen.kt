package com.fitsync.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
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
import com.fitsync.app.fit.LatLng
import com.fitsync.app.fit.TrackCodec
import com.fitsync.app.performance.PerformanceTrace
import com.fitsync.app.ui.components.EmptyStateVariant
import com.fitsync.app.ui.components.FitEmptyState
import com.fitsync.app.ui.components.FitListRow
import com.fitsync.app.ui.components.FitPagingFooter
import com.fitsync.app.ui.components.FitSkeletonListRow
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.IconBadge
import com.fitsync.app.ui.components.IconBadgeSize
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.TrackThumbnail
import com.fitsync.app.ui.theme.FitShape
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitType
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
        PerformanceTrace.section("FitSync:activity_list_first_frame") {
            withFrameNanos { }
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

    val showingEverything = debouncedQuery.isBlank() && filter == ActivityFilter.ALL
    val firstLoad = activities.loadState.refresh is LoadState.Loading && activities.itemCount == 0

    Column(Modifier.fillMaxSize()) {
        FitTopBar(title = "运动记录", subtitle = "所有来源已合并去重")
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
            item {
                ActivitySearchField(
                    query = query,
                    onQueryChange = { query = it },
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
                        style = FitType.dataLabel,
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
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                ) {
                    ActivityFilter.entries.forEach { option ->
                        FilterChip(
                            modifier = Modifier.height(36.dp),
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
            when {
                firstLoad -> {
                    items(4, key = { "skeleton-$it" }) {
                        FitSkeletonListRow()
                    }
                }

                activities.loadState.refresh is LoadState.NotLoading && activities.itemCount == 0 -> {
                    item {
                        if (showingEverything) {
                            FitEmptyState(
                                title = "还没有活动",
                                body = "从平台拉取，或把 FIT 文件分享到 FitSync。",
                                variant = EmptyStateVariant.FirstRun,
                                steps = listOf("连接平台账号", "分享或拉取 FIT", "自动补齐到其他平台"),
                                actionLabel = "连接平台",
                                onAction = onOpenPlatforms,
                            )
                        } else {
                            FitEmptyState(
                                title = "没有匹配结果",
                                body = "试试其他关键词或筛选条件。",
                                variant = EmptyStateVariant.NoResults,
                                icon = Icons.Default.Search,
                                actionLabel = "清除筛选",
                                onAction = {
                                    query = ""
                                    onFilterChange(ActivityFilter.ALL)
                                },
                            )
                        }
                    }
                }

                else -> {
                    items(
                        count = activities.itemCount,
                        key = activities.itemKey { it.id },
                        contentType = { "activity" },
                    ) { index ->
                        activities[index]?.let { activity ->
                            ActivityRow(activity, onOpenActivity)
                        }
                    }
                    item(key = "paging-footer") {
                        FitPagingFooter(
                            append = activities.loadState.append,
                            itemCount = activities.itemCount,
                            onRetry = { activities.retry() },
                        )
                    }
                }
            }
        }
    }
}

/** 填充式搜索框：无描边噪音，高度 48（保证清除按钮满足最小触控区）。 */
@Composable
private fun ActivitySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = FitShape.control,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.height(FitSpacing.touchTarget).padding(horizontal = FitSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(FitSpacing.iconSm),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).padding(start = FitSpacing.sm),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                "搜索标题或运动类型",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotBlank()) {
                // 不覆写 size：IconButton 默认保证 48dp 最小触控区。
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "清除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    activity: ActivityListUiModel,
    onOpenActivity: (Long) -> Unit,
) {
    FitListRow(
        title = activity.title,
        subtitle = "${activity.dateText} · ${activity.sportText}",
        leading = {
            // 有 GPS 就画路线形状：一列骑行动态里，形状比运动图标更好认。
            // 无 GPS（骑行台等）退回运动图标，两种行共用同一套尺寸语汇。
            if (activity.trackPoints.size >= 2) {
                TrackThumbnail(activity.trackPoints)
            } else {
                IconBadge(sportIcon(activity.sportType), size = IconBadgeSize.Md)
            }
        },
        trailing = if (activity.dedupStatus == DedupStatus.NEEDS_REVIEW) {
            { StatusPill("待复核", StatusTone.WARNING) }
        } else null,
        meta = {
            Text(
                activity.distanceText,
                style = FitType.numericMd,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            activity.durationText?.let {
                Text(
                    "·",
                    style = MaterialTheme.typography.bodySmall,
                    // 用 outline 会淡到 1.1–1.6:1，两个数据点会糊成一片；
                    // 分隔符取 onSurfaceVariant 的一半，看得见又不抢数字。
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
                Text(
                    it,
                    style = FitType.numericSm,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        onClick = { onOpenActivity(activity.id) },
        showChevron = true,
    )
}

private data class ActivityListUiModel(
    val id: Long,
    val title: String,
    val sportType: SportType,
    val dedupStatus: DedupStatus,
    val dateText: String,
    val distanceText: String,
    val durationText: String?,
    val sportText: String,
    val trackPoints: List<LatLng>,
)

private fun ActivityListItem.toUiModel(dateFormat: SimpleDateFormat): ActivityListUiModel = ActivityListUiModel(
    id = id,
    title = title?.takeIf { it.isNotBlank() } ?: sportLabel(sportType),
    sportType = sportType,
    dedupStatus = dedupStatus,
    dateText = dateFormat.format(Date(startTimeEpochMs)),
    distanceText = distanceMeters?.let { "%.1f km".format(it / 1000) } ?: "--",
    durationText = elapsedSeconds?.let(::formatDuration),
    sportText = sportLabel(sportType),
    trackPoints = TrackCodec.decodeTrack(trackJson),
)

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
    SportType.INDOOR_CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    SportType.CYCLING, SportType.MTB, SportType.GRAVEL -> Icons.AutoMirrored.Filled.DirectionsBike
    SportType.OTHER, SportType.UNKNOWN -> Icons.Default.Route
}

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "%d:%02d".format(h, m) else "%d 分".format(m)
}
