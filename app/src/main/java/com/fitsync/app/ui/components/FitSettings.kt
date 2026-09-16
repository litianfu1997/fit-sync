package com.fitsync.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitType

/**
 * 设置行：20dp 裸线性图标直接位于文字左侧（不再是彩色圆角方块）。
 * 同一行组件同时覆盖「开关行」「跳转行」「自定义尾部」三种形态。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
) {
    // 开关行走 toggleable + Role.Switch，让整行合并为单个无障碍节点；
    // 若用 clickable 包 Switch，TalkBack 会读成「可点击容器 + 开关」两个控件。
    val isSwitchRow = onCheckedChange != null && checked != null
    val interaction: Modifier = when {
        !enabled -> Modifier
        isSwitchRow -> Modifier.toggleable(
            value = checked!!,
            role = Role.Switch,
            onValueChange = { onCheckedChange!!(it) },
        )
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(interaction)
            .then(if (enabled) Modifier else Modifier.alpha(0.5f))
            .heightIn(min = FitSpacing.rowMinHeight)
            .padding(horizontal = FitSpacing.rowPaddingH, vertical = FitSpacing.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(FitSpacing.iconMd),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
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
        if (checked != null && trailing == null) {
            // onCheckedChange 传 null：切换由整行的 toggleable 承担，开关只作视觉呈现。
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        if (showChevron && onClick != null && onCheckedChange == null) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(FitSpacing.iconMd),
            )
        }
    }
}

/** 设置分组：组标题降级为 11sp 数据标签，行与行之间只用 1dp 分割线。 */
@Composable
fun SettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        title?.let {
            Text(
                it,
                modifier = Modifier.padding(
                    start = FitSpacing.xs,
                    end = FitSpacing.xs,
                    top = FitSpacing.sm,
                    bottom = FitSpacing.sm,
                ),
                style = FitType.dataLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FitCard(contentPadding = PaddingValues(0.dp), content = content)
    }
}

/** 组内分隔线：默认从 16dp 处起画，对齐文本起点。 */
@Composable
fun SettingsDivider(startInset: Dp = FitSpacing.screenGutter) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
