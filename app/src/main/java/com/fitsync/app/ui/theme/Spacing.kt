package com.fitsync.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 全局间距标尺。
 *
 * 刻意使用顶层 object 而非 CompositionLocal：间距不在组件树中变化，
 * 引入「可覆盖」能力只会制造新的不一致。屏级布局统一使用语义别名。
 */
object FitSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp

    // 语义别名：屏级布局只用别名
    val screenGutter = lg
    val sectionGap = xxl
    val cardPadding = lg
    val cardPaddingCompact = md
    val rowPaddingH = lg
    val rowPaddingV = md
    val rowMinHeight = 64.dp
    val touchTarget = 48.dp
    val iconSm = 18.dp
    val iconMd = 20.dp
    val iconLg = 24.dp
}
