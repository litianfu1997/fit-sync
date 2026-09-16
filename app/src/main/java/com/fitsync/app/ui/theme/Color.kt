package com.fitsync.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

// 品牌主色：保留橙色作为识别点
val FitOrange = Color(0xFFF97316)

/** 深色底上的品牌橙（#F97316 在深底偏暗） */
val FitOrangeBright = Color(0xFFFF8A3D)

/**
 * 亮色主题的「可读档」语义色：小字号文字与实心按钮填充用。
 *
 * 品牌亮色饱和但明度高，白底上只有 2.8:1，低于 WCAG AA 的 4.5:1；
 * 这里统一降一档明度，白底文字 / 白字底都能读到 5:1 以上。
 * 原始亮色只保留给 Hero 渐变、大号数字这类大色块。
 */
val FitOrangeDeep = Color(0xFFC2410C)
val FitGreenDeep = Color(0xFF15803D)
val FitBlueDeep = Color(0xFF1D4ED8)
val FitRedDeep = Color(0xFFB91C1C)

/** 深色底（Hero 渐变、深色主题表面）上的状态色：亮档，不做浅底前景。 */
object FitTonesOnDark {
    val warning = FitOrangeBright
    val success = Color(0xFF4ADE80)
    val info = Color(0xFF60A5FA)
    val error = Color(0xFFF87171)
    val neutral = Color(0xFF97A3B1)
}

/**
 * 色不过三：primary 橙 = 品牌 + 主动作 + 待处理；tertiary 蓝 = 距离/信息；
 * secondary 绿 = 成功；error 红 = 失败；其余一律 onSurfaceVariant。
 */
internal val FitLightColors = lightColorScheme(
    primary = FitOrangeDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDD5),
    onPrimaryContainer = Color(0xFF7C2D12),
    inversePrimary = Color(0xFFFFB067),
    secondary = FitGreenDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCFCE7),
    onSecondaryContainer = Color(0xFF14532D),
    tertiary = FitBlueDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDBEAFE),
    onTertiaryContainer = Color(0xFF1E3A8A),
    error = FitRedDeep,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF1F3F5),
    onBackground = Color(0xFF0F141A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F141A),
    surfaceVariant = Color(0xFFEEF1F4),
    onSurfaceVariant = Color(0xFF5A6673),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2A3138),
    inverseOnSurface = Color(0xFFEEF1F4),
    outline = Color(0xFFC6CDD6),
    outlineVariant = Color(0xFFE3E7EC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDFE4EA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFEEF1F4),
    surfaceContainerHigh = Color(0xFFE6EAEF),
    surfaceContainerHighest = Color(0xFFDFE4EA),
)

internal val FitDarkColors = darkColorScheme(
    primary = FitOrangeBright,
    onPrimary = Color(0xFF4B1D00),
    primaryContainer = Color(0xFF713400),
    onPrimaryContainer = Color(0xFFFFDCC2),
    inversePrimary = FitOrange,
    secondary = Color(0xFF4ADE80),
    onSecondary = Color(0xFF06391A),
    secondaryContainer = Color(0xFF14532D),
    onSecondaryContainer = Color(0xFFBBF7D0),
    tertiary = Color(0xFF60A5FA),
    onTertiary = Color(0xFF0B2A5B),
    tertiaryContainer = Color(0xFF1E3A8A),
    onTertiaryContainer = Color(0xFFDBEAFE),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF0A0E13),
    onBackground = Color(0xFFE8ECF1),
    surface = Color(0xFF121820),
    onSurface = Color(0xFFE8ECF1),
    surfaceVariant = Color(0xFF1C242E),
    onSurfaceVariant = Color(0xFF97A3B1),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE8ECF1),
    inverseOnSurface = Color(0xFF121820),
    outline = Color(0xFF38424F),
    outlineVariant = Color(0xFF212A34),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A3441),
    surfaceDim = Color(0xFF0A0E13),
    surfaceContainerLowest = Color(0xFF0D1218),
    surfaceContainerLow = Color(0xFF171E27),
    surfaceContainer = Color(0xFF1C242E),
    surfaceContainerHigh = Color(0xFF232C37),
    surfaceContainerHighest = Color(0xFF2A3441),
)

/** Hero 区域的渐变色，统一首页与活动详情的硬编码。 */
object FitGradients {
    val hero = listOf(Color(0xFF0E141B), Color(0xFF1A1712), Color(0xFF3A1D07))
    val heroSurface = Color.White.copy(alpha = 0.06f)
    val onHeroPrimary = Color.White
    val onHeroSecondary = Color.White.copy(alpha = 0.62f)
}

/** Hero 容器：渐变底 + 指定圆角。子内容通过 LocalContentColor 自适应前景色。 */
fun Modifier.fitHero(shape: Shape = FitShape.hero): Modifier =
    clip(shape).background(Brush.linearGradient(FitGradients.hero))
