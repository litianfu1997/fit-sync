package com.fitsync.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
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

private enum class ActivityFilter(val label: String) {
    ALL("全部"), CYCLING("骑行"), RUNNING("跑步"), REVIEW("待复核")
}

@Composable
fun ActivitiesScreen(onOpenActivity: (Long) -> Unit = {}) {
    val activities by FitSyncApp.instance.database.canonicalActivityDao().observeAll()
        .collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ActivityFilter.ALL) }
    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }
    val filtered = remember(activities, query, filter) {
        activities.filter { activity ->
            val queryMatch = query.isBlank() ||
                activity.title.orEmpty().contains(query, ignoreCase = true) ||
                sportLabel(activity.sportType).contains(query, ignoreCase = true)
            val filterMatch = when (filter) {
                ActivityFilter.ALL -> true
                ActivityFilter.CYCLING -> activity.sportType in setOf(
                    SportType.CYCLING,
                    SportType.INDOOR_CYCLING,
                    SportType.MTB,
                    SportType.GRAVEL,
                )
                ActivityFilter.RUNNING -> activity.sportType == SportType.RUNNING
                ActivityFilter.REVIEW -> activity.dedupStatus == DedupStatus.NEEDS_REVIEW
            }
            queryMatch && filterMatch
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "活动", subtitle = "${activities.size} 条本地统一活动")
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
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActivityFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    EmptyState(
                        title = if (activities.isEmpty()) "还没有活动" else "没有匹配结果",
                        body = if (activities.isEmpty()) "从平台拉取，或把 FIT 文件分享到 FitSync。" else "试试其他关键词或筛选条件。",
                        icon = Icons.Default.Route,
                    )
                }
            } else {
                items(filtered, key = { it.id }) { activity ->
                    ActivityCard(activity, dateFormat, onOpenActivity)
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activity: CanonicalActivityEntity,
    dateFormat: SimpleDateFormat,
    onOpenActivity: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpenActivity(activity.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(FitBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(sportIcon(activity.sportType), contentDescription = null, tint = FitBlue)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        activity.title?.takeIf { it.isNotBlank() } ?: sportLabel(activity.sportType),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    if (activity.dedupStatus == DedupStatus.NEEDS_REVIEW) {
                        StatusPill("待复核", StatusTone.WARNING)
                    }
                }
                Text(
                    dateFormat.format(Date(activity.startTimeEpochMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ActivityStat(activity.distanceMeters?.let { "%.1f km".format(it / 1000) } ?: "--", "距离")
                    ActivityStat(activity.elapsedSeconds?.let(::formatDuration) ?: "--", "时长")
                    ActivityStat(sportLabel(activity.sportType), "类型")
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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
