package com.fitsync.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitsync.app.FitSyncApp
import com.fitsync.app.fit.TrackCodec
import com.fitsync.app.ui.components.EmptyStateVariant
import com.fitsync.app.ui.components.FitEmptyState
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.TrackMap
import com.fitsync.app.ui.theme.FitSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏轨迹地图：详情页轨迹卡片的放大版，可缩放拖动、起终点标记。
 * 取点逻辑与详情页一致（高密度优先、回退 21 点摘要）。
 */
@Composable
fun TrackMapScreen(activityId: Long, onBack: () -> Unit) {
    val app = FitSyncApp.instance
    val activity by app.database.canonicalActivityDao().observeById(activityId)
        .collectAsStateWithLifecycle(initialValue = null)
    val variants by app.database.activityVariantDao().observeByCanonical(activityId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }

    val primary = variants.firstOrNull { it.id == activity?.primaryVariantId } ?: variants.firstOrNull()
    val denseTrack = primary?.denseTrackBase64?.takeIf(String::isNotBlank)
        ?: variants.firstNotNullOfOrNull { it.denseTrackBase64?.takeIf(String::isNotBlank) }
    val trackJson = primary?.trackSummaryJson
    val points = remember(denseTrack, trackJson) { TrackCodec.decodeBestTrack(denseTrack, trackJson) }

    Column(Modifier.fillMaxSize()) {
        FitTopBar(
            title = "轨迹地图",
            subtitle = activity?.let {
                "${activitySummaryTitle(it)} · ${dateFormat.format(Date(it.startTimeEpochMs))}"
            },
            onBack = onBack,
        )
        when {
            points.isNotEmpty() -> TrackMap(
                points = points,
                modifier = Modifier.fillMaxSize(),
                interactive = true,
            )
            // 数据还在路上：先留白，避免闪一下空状态
            activity == null -> Unit
            else -> Box(
                modifier = Modifier.fillMaxSize().padding(FitSpacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                FitEmptyState(
                    title = "没有轨迹数据",
                    body = "这条活动没有 GPS 轨迹，无法在地图上展示。",
                    variant = EmptyStateVariant.NoResults,
                )
            }
        }
    }
}
