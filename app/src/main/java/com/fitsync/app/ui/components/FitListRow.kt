package com.fitsync.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.fitsync.app.ui.theme.FitSpacing

/**
 * 列表行：活动卡 / 任务卡 / 平台卡的同质化骨架。
 * 数据条 [meta] 用 ` · ` 连接的次级信息，而不是等宽 label-value 表格。
 */
@Composable
fun FitListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    meta: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = FitSpacing.rowMinHeight,
) {
    val haptic = LocalHapticFeedback.current
    FitCard(
        modifier = modifier.then(if (enabled) Modifier else Modifier.alpha(0.5f)),
        selected = selected,
        contentPadding = PaddingValues(FitSpacing.rowPaddingH, FitSpacing.rowPaddingV),
        onClick = onClick?.takeIf { enabled },
        onLongClick = onLongClick?.takeIf { enabled }?.let {
            {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                it()
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight - FitSpacing.rowPaddingV * 2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                ) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    trailing?.invoke()
                }
                subtitle?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = FitSpacing.xxs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                meta?.let {
                    Row(
                        modifier = Modifier.padding(top = FitSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                        content = it,
                    )
                }
                supporting?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = FitSpacing.xs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showChevron) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(FitSpacing.iconMd),
                )
            }
        }
    }
}
