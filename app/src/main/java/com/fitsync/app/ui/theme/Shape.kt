package com.fitsync.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 硬朗几何：小圆角 + 1dp 描边 + 零阴影。
 *
 * 圆角是「消费级」视觉编码，直角与细线是「仪表/工具」编码。
 * 因此整体圆角从 10/14/20/28/36 收敛到 2/4/8/12/16。
 */
object FitShape {
    /** 状态徽标、数据条 */
    val indicator = RoundedCornerShape(2.dp)

    /** 输入框、chip、IconBadge、PlatformAvatar */
    val control = RoundedCornerShape(4.dp)
    val badge = control

    /** 卡片 */
    val card = RoundedCornerShape(8.dp)

    /** Hero、底部导航 */
    val hero = RoundedCornerShape(12.dp)

    /** BottomSheet、Dialog */
    val sheet = RoundedCornerShape(16.dp)

    val pill = RoundedCornerShape(percent = 50)
}

internal val FitShapes = Shapes(
    extraSmall = FitShape.indicator,
    small = FitShape.control,
    medium = FitShape.card,
    large = FitShape.hero,
    extraLarge = FitShape.sheet,
)
