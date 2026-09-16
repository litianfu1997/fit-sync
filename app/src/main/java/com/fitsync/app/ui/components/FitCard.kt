package com.fitsync.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.fitsync.app.ui.theme.FitShape
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitType

/** 卡片色调。除 Primary / Error 这类语义状态外，一律使用中性表面。 */
enum class FitCardTone { Surface, Muted, Primary, Error }

/**
 * 统一卡片：基于 Surface 实现，保证形状与描边同源，
 * 结构上杜绝「卡片圆角与描边圆角不一致」。刻意不暴露 elevation 参数（零阴影）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FitCard(
    modifier: Modifier = Modifier,
    tone: FitCardTone = FitCardTone.Surface,
    shape: Shape = FitShape.card,
    contentPadding: PaddingValues = PaddingValues(FitSpacing.cardPadding),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    border: Boolean = true,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container = when (tone) {
        FitCardTone.Surface -> colors.surface
        FitCardTone.Muted -> colors.surfaceContainerLow
        FitCardTone.Primary -> colors.primaryContainer.copy(alpha = 0.38f)
        FitCardTone.Error -> colors.errorContainer.copy(alpha = 0.45f)
    }
    val borderColor = when {
        selected -> colors.primary
        tone == FitCardTone.Error -> colors.error.copy(alpha = 0.35f)
        tone == FitCardTone.Primary -> colors.primary.copy(alpha = 0.45f)
        else -> colors.outlineVariant
    }
    // clip 必须在 clickable 之前，保证点击反馈有界。
    val interaction = modifier
        .then(if (enabled) Modifier else Modifier.alpha(0.5f))
        .clip(shape)
        .let { base ->
            when {
                !enabled -> base
                onClick != null && onLongClick != null ->
                    base.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                onClick != null -> base.combinedClickable(onClick = onClick)
                onLongClick != null -> base.combinedClickable(onClick = {}, onLongClick = onLongClick)
                else -> base
            }
        }
    Surface(
        modifier = interaction,
        shape = shape,
        color = container,
        contentColor = if (tone == FitCardTone.Error) colors.onErrorContainer else colors.onSurface,
        border = if (border) BorderStroke(1.dp, borderColor) else null,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 区块标题：11sp 数据标签（加字距、中性色），让数据成为主角。
 * 页面主标题仍使用大字号，二者不再竞争。
 */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.sm, bottom = FitSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = FitType.dataLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            subtitle?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = FitSpacing.xxs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
