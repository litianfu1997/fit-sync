package com.fitsync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fitsync.app.ui.theme.FitGradients
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitType

enum class StatSize { Hero, Xl, Md, Sm }

enum class StatTone { Default, Success, Warning, Error }

data class StatTileSpec(
    val key: String,
    val value: String,
    val label: String,
    val unit: String? = null,
    val tone: StatTone = StatTone.Default,
)

private fun valueStyle(size: StatSize): TextStyle = when (size) {
    StatSize.Hero -> FitType.numericHero
    StatSize.Xl -> FitType.numericXl
    StatSize.Md -> FitType.numericMd
    StatSize.Sm -> FitType.numericSm
}

@Composable
private fun unitStyle(size: StatSize): TextStyle = when (size) {
    StatSize.Hero -> FitType.numericMd
    StatSize.Xl, StatSize.Md -> FitType.numericSm
    StatSize.Sm -> MaterialTheme.typography.labelMedium
}

/** SpanStyle 不接受 TextStyle，这里做一次显式转换。 */
private fun TextStyle.asSpan(color: Color): SpanStyle = SpanStyle(
    color = color,
    fontSize = fontSize,
    fontWeight = fontWeight,
    fontStyle = fontStyle,
    fontFamily = fontFamily,
    letterSpacing = letterSpacing,
    fontFeatureSettings = fontFeatureSettings,
)

@Composable
private fun toneColor(tone: StatTone): Color = when (tone) {
    StatTone.Default -> MaterialTheme.colorScheme.onSurface
    StatTone.Success -> MaterialTheme.colorScheme.secondary
    StatTone.Warning -> MaterialTheme.colorScheme.primary
    StatTone.Error -> MaterialTheme.colorScheme.error
}

/** 数字与单位在同一 Text 内用 AnnotatedString 混排，保证基线与换行正确。 */
@Composable
private fun statValueText(
    value: String,
    unit: String?,
    size: StatSize,
    color: Color,
): AnnotatedString {
    val unitColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueSpan = valueStyle(size).asSpan(color)
    val unitSpan = unitStyle(size).asSpan(unitColor)
    return buildAnnotatedString {
        withStyle(valueSpan) { append(value) }
        unit?.takeIf { it.isNotBlank() }?.let {
            withStyle(unitSpan) {
                append(" ")
                append(it)
            }
        }
    }
}

/**
 * 统计块：默认无图标，value 用等宽数字，label 用数据标签。
 * 需要点击入口时传入 onClick，不再用图标暗示可点。
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    size: StatSize = StatSize.Md,
    tone: StatTone = StatTone.Default,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = FitSpacing.lg, vertical = FitSpacing.md),
    ) {
        Text(
            text = statValueText(value, unit, size, toneColor(tone)),
            maxLines = 1,
        )
        Text(
            label,
            modifier = Modifier.padding(top = FitSpacing.xs),
            style = FitType.dataLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 数据面板：无图标、仅 1dp 分隔线的网格。 */
@Composable
fun StatGrid(
    stats: List<StatTileSpec>,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    onStatClick: ((StatTileSpec) -> Unit)? = null,
) {
    if (stats.isEmpty()) return
    FitCard(modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        stats.chunked(columns).forEachIndexed { rowIndex, rowStats ->
            if (rowIndex > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowStats.forEachIndexed { index, spec ->
                    if (index > 0) {
                        Box(
                            Modifier.width(1.dp).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                    StatTile(
                        value = spec.value,
                        label = spec.label,
                        unit = spec.unit,
                        size = StatSize.Md,
                        tone = spec.tone,
                        modifier = Modifier.weight(1f),
                        onClick = onStatClick?.let { click -> { click(spec) } },
                    )
                }
                repeat(columns - rowStats.size) {
                    Box(
                        Modifier.width(1.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Hero 内的一行次级数据：`时长 1:24 · 均速 30.2 km/h`，整行可换行。 */
@Composable
fun HeroStatStrip(
    stats: List<StatTileSpec>,
    modifier: Modifier = Modifier,
) {
    if (stats.isEmpty()) return
    val labelSpan = FitType.dataLabel.asSpan(FitGradients.onHeroSecondary)
    val valueSpan = FitType.numericSm.asSpan(FitGradients.onHeroPrimary)
    val text = buildAnnotatedString {
        stats.forEachIndexed { index, spec ->
            if (index > 0) {
                withStyle(labelSpan) { append("  ·  ") }
            }
            withStyle(labelSpan) {
                append(spec.label)
                append(" ")
            }
            val value = buildString {
                append(spec.value)
                spec.unit?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
            }
            withStyle(valueSpan) { append(value) }
        }
    }
    Text(text, modifier = modifier, style = FitType.numericSm)
}
