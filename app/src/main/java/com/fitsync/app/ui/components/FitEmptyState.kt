package com.fitsync.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fitsync.app.ui.theme.FitSpacing

enum class EmptyStateVariant { FirstRun, NoResults, Error, AllDone }

/**
 * 空状态：不同场景给不同的表达，而不是统一的「居中图标 + 标题 + 灰字」。
 * 首次使用需要「怎么做」，无结果需要「怎么回去」，出错需要「可执行动作」。
 */
@Composable
fun FitEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    variant: EmptyStateVariant = EmptyStateVariant.FirstRun,
    icon: ImageVector? = null,
    steps: List<String> = emptyList(),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    when (variant) {
        EmptyStateVariant.NoResults -> {
            Row(
                modifier = modifier.fillMaxWidth().padding(vertical = FitSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
            ) {
                Icon(
                    icon ?: Icons.Default.SearchOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(FitSpacing.iconSm),
                )
                Text(
                    body.ifBlank { title },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
        }

        EmptyStateVariant.AllDone -> {
            FitCard(modifier = modifier, tone = FitCardTone.Muted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(FitSpacing.iconLg),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            body,
                            modifier = Modifier.padding(top = FitSpacing.xxs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (actionLabel != null && onAction != null) {
                        TextButton(onClick = onAction) { Text(actionLabel) }
                    }
                }
            }
        }

        EmptyStateVariant.Error -> {
            FitCard(modifier = modifier, tone = FitCardTone.Error) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    body,
                    modifier = Modifier.padding(top = FitSpacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (actionLabel != null && onAction != null) {
                    Button(
                        onClick = onAction,
                        modifier = Modifier.padding(top = FitSpacing.md),
                    ) { Text(actionLabel) }
                }
            }
        }

        EmptyStateVariant.FirstRun -> {
            FitCard(modifier = modifier, tone = FitCardTone.Muted) {
                TraceGlyph()
                Text(
                    title,
                    modifier = Modifier.padding(top = FitSpacing.md),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    body,
                    modifier = Modifier.padding(top = FitSpacing.xs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                steps.forEachIndexed { index, step ->
                    Row(
                        modifier = Modifier.padding(top = if (index == 0) FitSpacing.md else FitSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                    ) {
                        Text(
                            numberedMark(index),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (actionLabel != null && onAction != null) {
                    Button(
                        onClick = onAction,
                        modifier = Modifier.padding(top = FitSpacing.lg).fillMaxWidth(),
                    ) { Text(actionLabel) }
                }
            }
        }
    }
}

private fun numberedMark(index: Int): String =
    "①②③④⑤⑥⑦⑧⑨⑩".getOrNull(index)?.toString() ?: "${index + 1}."

/** 极简橙色轨迹折线（复用启动图标母题），不引入插画资源。 */
@Composable
private fun TraceGlyph(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(width = 64.dp, height = 20.dp)) {
        val points = listOf(
            0f to 0.72f,
            0.17f to 0.28f,
            0.38f to 0.56f,
            0.62f to 0.12f,
            0.82f to 0.44f,
            1f to 0.2f,
        )
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            val px = x * size.width
            val py = y * size.height
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(0f, 0.72f * size.height))
        drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(size.width, 0.2f * size.height))
    }
}
