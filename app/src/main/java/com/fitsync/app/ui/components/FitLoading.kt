package com.fitsync.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import com.fitsync.app.ui.theme.FitShape
import com.fitsync.app.ui.theme.FitSpacing

/** 呼吸骨架块：alpha 0.35 → 0.6，不引入第三方 shimmer 库。 */
@Composable
fun FitSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = FitShape.control,
) {
    val transition = rememberInfiniteTransition(label = "fit-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fit-skeleton-alpha",
    )
    Box(
        modifier.clip(shape).alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** 列表行骨架：与 FitListRow 相同的骨架节奏，用于首屏加载。 */
@Composable
fun FitSkeletonListRow(modifier: Modifier = Modifier) {
    FitCard(
        modifier = modifier,
        contentPadding = PaddingValues(FitSpacing.rowPaddingH, FitSpacing.rowPaddingV),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            FitSkeleton(Modifier.size(36.dp), FitShape.control)
            Column(Modifier.weight(1f)) {
                FitSkeleton(Modifier.fillMaxWidth(0.46f).height(14.dp))
                FitSkeleton(
                    Modifier.padding(top = FitSpacing.sm).fillMaxWidth(0.72f).height(12.dp),
                )
            }
        }
    }
}

/**
 * 分页尾部：加载中显示骨架行，失败显示重试，末尾显示已加载全部。
 */
@Composable
fun FitPagingFooter(
    append: LoadState,
    itemCount: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        append is LoadState.Loading -> FitSkeletonListRow(modifier)
        append is LoadState.Error -> {
            Row(
                modifier = modifier.fillMaxWidth().padding(vertical = FitSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "加载失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
        itemCount > 0 -> {
            Box(
                modifier = modifier.fillMaxWidth().padding(vertical = FitSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "已加载全部 $itemCount 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 小尺寸加载指示：用于按需出现的局部等待。 */
@Composable
fun FitInlineLoading(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
    ) {
        FitSkeleton(Modifier.width(18.dp).height(18.dp), FitShape.indicator)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
