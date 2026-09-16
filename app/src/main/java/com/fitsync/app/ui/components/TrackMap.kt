package com.fitsync.app.ui.components

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fitsync.app.fit.LatLng
import com.fitsync.app.ui.theme.FitSpacing
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * 轨迹地图：osmdroid + OSM 栅格瓦片，画一条轨迹线并标出起终点。
 *
 * 离线时瓦片加载失败是预期内的——背景保持中性色，轨迹线与标记照常绘制，
 * 所以详情页不需要「无网络就不显示地图」这类兜底分支。
 *
 * [interactive] = false 时地图不吃手势（可在列表里正常滚动），配合 [onTap] 当作点击进全屏的预览。
 * 瓦片缓存与 User-Agent 在 [com.fitsync.app.map.OsMapBootstrap] 统一配置。
 */
@Composable
fun TrackMap(
    points: List<LatLng>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = MaterialTheme.colorScheme
    val lineColor = colors.primary.toArgb()
    val startColor = colors.tertiary.toArgb()
    val endColor = colors.error.toArgb()
    val backgroundColor = colors.surfaceContainerHigh.toArgb()
    val handle = remember { TrackMapHandle() }

    Box(
        modifier = modifier.background(colors.surfaceContainerHigh),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // 新建视图时清掉渲染缓存，保证下面的 update 一定会重画 overlays
                handle.reset()
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setTilesScaledToDpi(true)
                    setMultiTouchControls(interactive)
                    setScrollableAreaLimitDouble(null)
                    minZoomLevel = MIN_ZOOM
                    maxZoomLevel = MAX_ZOOM
                    setBackgroundColor(backgroundColor)
                    isClickable = interactive
                    isFocusable = interactive
                    handle.view = this
                }
            },
            update = { view ->
                // AndroidView 的 factory/update 与 DisposableEffect 的执行顺序不保证，
                // 首帧在这里补 onResume；缺了它 osmdroid 不会去拉瓦片。
                if (!handle.resumed && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    view.onResume()
                    handle.resumed = true
                }
                view.setBackgroundColor(backgroundColor)
                renderTrack(
                    view = view,
                    handle = handle,
                    points = points,
                    lineColor = lineColor,
                    startColor = startColor,
                    endColor = endColor,
                    density = density,
                )
            },
        )

        if (!interactive && onTap != null) {
            // 盖一层透明点击区：地图不吃手势，整块卡片都能点进全屏
            Box(modifier = Modifier.matchParentSize().clickable(onClick = onTap))
        }

        Text(
            text = "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(FitSpacing.xs)
                .background(colors.surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                .padding(horizontal = FitSpacing.xs, vertical = 1.dp),
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    handle.view?.onResume()
                    handle.resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    handle.view?.onPause()
                    handle.resumed = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (handle.resumed) handle.view?.onPause()
            handle.view?.onDetach()
            handle.view = null
        }
    }
}

/** 视图与渲染状态：点或配色没变就不重建 overlays，避免每次重组都重排标记。 */
private class TrackMapHandle {
    var view: MapView? = null
    var resumed = false
    private var renderedPoints: List<LatLng>? = null
    private var renderedLineColor = 0
    private var renderedStartColor = 0
    private var renderedEndColor = 0

    /** 视图被重建（LazyColumn 回收、配置变化）时必须整体重来：overlays 随视图消失，onResume 也要对新视图补一次。 */
    fun reset() {
        renderedPoints = null
        resumed = false
    }

    fun needsRender(points: List<LatLng>, line: Int, start: Int, end: Int): Boolean =
        renderedPoints != points ||
            renderedLineColor != line ||
            renderedStartColor != start ||
            renderedEndColor != end

    fun markRendered(points: List<LatLng>, line: Int, start: Int, end: Int) {
        renderedPoints = points
        renderedLineColor = line
        renderedStartColor = start
        renderedEndColor = end
    }
}

private fun renderTrack(
    view: MapView,
    handle: TrackMapHandle,
    points: List<LatLng>,
    lineColor: Int,
    startColor: Int,
    endColor: Int,
    density: Float,
) {
    if (!handle.needsRender(points, lineColor, startColor, endColor)) return
    handle.markRendered(points, lineColor, startColor, endColor)

    val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
    view.overlays.clear()

    if (geoPoints.size >= 2) {
        view.overlays.add(
            Polyline(view).apply {
                setPoints(geoPoints)
                outlinePaint.color = lineColor
                outlinePaint.strokeWidth = 3f * density
                outlinePaint.isAntiAlias = true
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            },
        )
        view.overlays.add(marker(view, geoPoints.first(), startColor, density))
        view.overlays.add(marker(view, geoPoints.last(), endColor, density))
        val box = BoundingBox.fromGeoPointsSafe(geoPoints).increaseByScale(BOUNDS_PADDING_SCALE)
        // post：等测量完成后再 fit，否则 0 宽高会算出错误的缩放级别
        view.post { view.zoomToBoundingBox(box, false, (24 * density).toInt()) }
    } else if (geoPoints.size == 1) {
        view.controller.setZoom(SINGLE_POINT_ZOOM)
        view.controller.setCenter(geoPoints.first())
    }
}

private fun marker(view: MapView, point: GeoPoint, color: Int, density: Float): Marker =
    Marker(view).apply {
        position = point
        // 图标是圆点而不是水滴，锚点取正中，标记才落在真实位置上
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize((12 * density).toInt(), (12 * density).toInt())
            setColor(color)
            setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
        }
    }

private const val MIN_ZOOM = 3.0
private const val MAX_ZOOM = 19.0
private const val SINGLE_POINT_ZOOM = 15.0
private const val BOUNDS_PADDING_SCALE = 1.18f
