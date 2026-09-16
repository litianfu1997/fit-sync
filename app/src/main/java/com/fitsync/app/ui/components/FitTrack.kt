package com.fitsync.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitsync.app.fit.LatLng
import com.fitsync.app.ui.theme.FitShape
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 轨迹预览：把经纬度归一化后画成一条折线。
 *
 * 刻意不引入地图 SDK——这里要回答的是「这条路线长什么样」，
 * 不是地理精度；仪表风格里一条等宽细线比一块彩色地图更贴合。
 * 点数不足 2 个时不画（调用方负责兜底成运动图标）。
 */
@Composable
fun TrackPreview(
    points: List<LatLng>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    strokeWidth: Dp = 1.5.dp,
) {
    Canvas(modifier) {
        val path = trackPath(points, size) ?: return@Canvas
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/**
 * 列表行的轨迹缩略图容器：与 IconBadge 共用尺寸语汇（control 圆角 + 中性底），
 * 保证同一列里「有轨迹」和「只有图标」两种行看起来仍是一套东西。
 */
@Composable
fun TrackThumbnail(
    points: List<LatLng>,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(FitShape.control)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        TrackPreview(
            points = points,
            modifier = Modifier.fillMaxSize().padding(6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 曲线图（速度 / 海拔）：面积 + 折线，无坐标轴、无刻度。
 * 数据按时间等距抽样，横轴直接用下标，标签由调用方在下方用文字给。
 */
@Composable
fun SeriesChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 1.5.dp,
) {
    Canvas(modifier) {
        if (values.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        values.forEach {
            lo = min(lo, it)
            hi = max(hi, it)
        }
        val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        val inset = size.height * 0.08f
        val usable = size.height - inset * 2f
        val stepX = size.width / (values.size - 1)

        val line = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = inset + (1f - (value - lo) / span) * usable
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }

        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path = area, color = color.copy(alpha = 0.12f))
        drawPath(
            path = line,
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/**
 * 经纬度 → 画布路径。
 *
 * 1. 按 cos(纬度) 修正经度：同样的经度差在高纬度对应的实际距离更短，
 *    不修正的话轨迹会被横向拉长。
 * 2. 等比缩放并居中，留 10% 余量，避免贴边。
 * 3. 正北朝上：纬度越大 y 越小。
 */
private fun trackPath(points: List<LatLng>, size: Size): Path? {
    if (points.size < 2 || size.width <= 0f || size.height <= 0f) return null
    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE
    points.forEach {
        minLat = min(minLat, it.lat)
        maxLat = max(maxLat, it.lat)
        minLon = min(minLon, it.lon)
        maxLon = max(maxLon, it.lon)
    }
    val midLat = (minLat + maxLat) / 2.0
    val lonScale = cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
    val spanX = ((maxLon - minLon) * lonScale).coerceAtLeast(1e-9)
    val spanY = (maxLat - minLat).coerceAtLeast(1e-9)
    val scale = min(size.width / spanX, size.height / spanY) * 0.9
    val offsetX = (size.width - spanX * scale) / 2.0
    val offsetY = (size.height - spanY * scale) / 2.0

    return Path().apply {
        points.forEachIndexed { index, p ->
            val x = (offsetX + (p.lon - minLon) * lonScale * scale).toFloat()
            val y = (offsetY + (maxLat - p.lat) * scale).toFloat()
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
}
