package com.fitsync.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.fit.LatLng
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitSyncTheme
import kotlin.math.cos
import kotlin.math.sin

// 只预览组件，不预览 Screen：Screen 依赖 FitSyncApp.instance 与数据库。

@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun FitCardPreview() {
    FitSyncTheme {
        Column(
            modifier = Modifier.padding(FitSpacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            FitCard { Text("Surface：默认卡片") }
            FitCard(tone = FitCardTone.Muted) { Text("Muted：次级区块") }
            FitCard(tone = FitCardTone.Primary) { Text("Primary：待确认") }
            FitCard(tone = FitCardTone.Error) { Text("Error：需要注意") }
            FitCard(selected = true) { Text("Selected：已选中") }
        }
    }
}

@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun IconBadgePreview() {
    FitSyncTheme {
        Column(
            modifier = Modifier.padding(FitSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            IconBadge(Icons.Default.Description, size = IconBadgeSize.Sm)
            IconBadge(Icons.Default.Description, size = IconBadgeSize.Md)
            IconBadge(Icons.Default.Description, size = IconBadgeSize.Lg)
            IconBadge(Icons.Default.CheckCircle, size = IconBadgeSize.Md, tone = IconBadgeTone.Success)
            IconBadge(Icons.Default.DeleteSweep, size = IconBadgeSize.Md, tone = IconBadgeTone.Error)
            IconBadge(Icons.Default.Refresh, size = IconBadgeSize.Md, tone = IconBadgeTone.Brand)
        }
    }
}

@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun StatPreview() {
    FitSyncTheme {
        Column(
            modifier = Modifier.padding(FitSpacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            StatTile(value = "1,284", label = "本地活动", size = StatSize.Hero)
            StatTile(value = "42.3", unit = "km", label = "距离", size = StatSize.Xl)
            StatTile(value = "1:24", label = "时长", tone = StatTone.Success)
            StatGrid(
                stats = listOf(
                    StatTileSpec("discovered", "1,284", "本地活动"),
                    StatTileSpec("synced", "1,201", "成功投递"),
                    StatTileSpec("pending", "31", "等待任务", tone = StatTone.Warning),
                    StatTileSpec("failed", "4", "同步失败", tone = StatTone.Error),
                ),
            )
        }
    }
}

@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun FitListRowPreview() {
    FitSyncTheme {
        Column(
            modifier = Modifier.padding(FitSpacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            FitListRow(
                title = "晨骑",
                subtitle = "09-10 06:12 · 骑行",
                meta = {
                    Text("42.3 km")
                    Text("·")
                    Text("1:24")
                },
                leading = { IconBadge(Icons.Default.Description) },
                trailing = { StatusPill("待复核", StatusTone.WARNING) },
                showChevron = true,
                onClick = {},
                onLongClick = {},
            )
            FitListRow(
                title = "行者 · 09-10 14:20",
                subtitle = "同步完成",
                leading = { PlatformAvatar(Platform.XINGZHE, 32.dp) },
                trailing = { StatusPill("已同步", StatusTone.SUCCESS) },
                selected = true,
                onClick = {},
            )
        }
    }
}

@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun FitSettingsPreview() {
    FitSyncTheme {
        Column(
            modifier = Modifier.padding(FitSpacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            SettingsGroup("同步") {
                SettingsRow(
                    title = "自动同步",
                    subtitle = "由系统在后台增量拉取与投递",
                    checked = true,
                    onCheckedChange = {},
                )
                SettingsDivider()
                SettingsRow(
                    title = "平台账号",
                    subtitle = "2 个账号已连接",
                    onClick = {},
                    trailing = { StatusPill("正常", StatusTone.SUCCESS) },
                )
            }
        }
    }
}

@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun FitEmptyStatePreview() {
    FitSyncTheme {
        Column(
            modifier = Modifier.padding(FitSpacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            FitEmptyState(
                title = "还没有活动",
                body = "登录平台开始拉取，或从任意骑行 App 将 FIT 文件分享到这里。",
                variant = EmptyStateVariant.FirstRun,
                steps = listOf("连接平台账号", "分享或拉取 FIT", "自动补齐到其他平台"),
                actionLabel = "连接平台",
                onAction = {},
            )
            FitEmptyState(
                title = "没有匹配结果",
                body = "试试其他关键词或筛选条件。",
                variant = EmptyStateVariant.NoResults,
                actionLabel = "清除筛选",
                onAction = {},
            )
            FitEmptyState(
                title = "同步失败",
                body = "平台登录已失效，重新登录后会自动继续。",
                variant = EmptyStateVariant.Error,
                actionLabel = "重试",
                onAction = {},
            )
            FitEmptyState(
                title = "全部同步完成",
                body = "没有等待处理的任务。",
                variant = EmptyStateVariant.AllDone,
                actionLabel = "查看已同步",
                onAction = {},
            )
        }
    }
}

@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun FitStatusPreview() {
    FitSyncTheme {
        Column(
            modifier = Modifier.padding(FitSpacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.sm),
        ) {
            StatusPill("信息", StatusTone.INFO)
            StatusPill("成功", StatusTone.SUCCESS)
            StatusPill("待处理", StatusTone.WARNING)
            StatusPill("失败", StatusTone.ERROR)
            StatusPill("中性", StatusTone.NEUTRAL)
            StatusPill("紧凑", StatusTone.SUCCESS, compact = true)
            PlatformRouteAvatar(Platform.ONELAP, Platform.XINGZHE)
            PlatformRouteAvatar(Platform.GARMIN, Platform.IGPSPORT, size = 36.dp)
        }
    }
}

@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun FitLoadingPreview() {
    FitSyncTheme {
        Column(
            modifier = Modifier.padding(FitSpacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            FitSkeleton(Modifier.size(80.dp, 16.dp))
            FitSkeletonListRow()
            FitPagingFooter(append = androidx.paging.LoadState.Loading, itemCount = 0, onRetry = {})
        }
    }
}

@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun FitTopBarPreview() {
    FitSyncTheme {
        FitTopBar(
            title = "活动详情",
            subtitle = "2026年09月10日 06:12",
            onBack = {},
            actions = { TextButton(onClick = {}) { Text("保存") } },
        )
    }
}

@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun SettingsSwitchPreview() {
    FitSyncTheme {
        SettingsGroup("系统") {
            SettingsRow(title = "操作提醒", subtitle = "登录失效、验证码或网页操作时通知", checked = false, onCheckedChange = {})
            SettingsDivider()
            SettingsRow(title = "自定义尾部", subtitle = "右侧插槽", trailing = { Switch(checked = true, onCheckedChange = null) }, onClick = {})
        }
    }
}

@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun HeroStatStripPreview() {
    FitSyncTheme {
        Column(modifier = Modifier.padding(FitSpacing.lg).fillMaxWidth()) {
            HeroStatStrip(
                stats = listOf(
                    StatTileSpec("duration", "1:24", "时长"),
                    StatTileSpec("speed", "30.2", "均速", unit = "km/h"),
                    StatTileSpec("elevation", "320", "爬升", unit = "m"),
                ),
            )
        }
    }
}


@Preview(name = "浅色", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = 0x20)
@Composable
private fun FitTrackPreview() {
    val track = remember {
        (0..60).map { i ->
            val angle = i / 60.0 * 2.0 * Math.PI
            LatLng(39.9 + 0.02 * sin(angle), 116.4 + 0.03 * cos(angle))
        }
    }
    val speed = remember { (0..80).map { i -> 24f + 9f * sin(i / 9.0).toFloat() } }
    val altitude = remember { (0..80).map { i -> 40f + 55f * sin(i / 14.0).toFloat() } }
    FitSyncTheme {
        Column(
            modifier = Modifier.padding(FitSpacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(FitSpacing.md)) {
                TrackThumbnail(track)
                TrackThumbnail(track, size = 36.dp)
            }
            TrackPreview(
                points = track,
                modifier = Modifier.fillMaxWidth().height(160.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
            SeriesChart(
                values = speed,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                color = MaterialTheme.colorScheme.tertiary,
            )
            SeriesChart(
                values = altitude,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
