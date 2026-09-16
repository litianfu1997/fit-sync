package com.fitsync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitsync.app.ui.theme.FitShape

/** 图标容器三档尺寸（替代原先 34/36/38/40/48 五套散装尺寸）。 */
enum class IconBadgeSize(val container: Dp, val icon: Dp) {
    Sm(28.dp, 16.dp),
    Md(36.dp, 20.dp),
    Lg(44.dp, 22.dp),
}

/** 默认中性色，抑制「每个图标一个糖果色」的装饰倾向。 */
enum class IconBadgeTone { Neutral, Brand, Info, Success, Warning, Error }

@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: IconBadgeSize = IconBadgeSize.Md,
    tone: IconBadgeTone = IconBadgeTone.Neutral,
    contentDescription: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val accent: Color = when (tone) {
        IconBadgeTone.Neutral -> colors.onSurfaceVariant
        IconBadgeTone.Brand -> colors.primary
        IconBadgeTone.Info -> colors.tertiary
        IconBadgeTone.Success -> colors.secondary
        IconBadgeTone.Warning -> colors.primary
        IconBadgeTone.Error -> colors.error
    }
    val container = if (tone == IconBadgeTone.Neutral) colors.surfaceContainerHigh else accent.copy(alpha = 0.12f)
    Box(
        modifier = modifier.size(size.container).clip(FitShape.control).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = accent, modifier = Modifier.size(size.icon))
    }
}
