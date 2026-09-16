package com.fitsync.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 主题只做组装，Token 分别定义在 Color.kt / Type.kt / Shape.kt / Spacing.kt。
 */
@Composable
fun FitSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) FitDarkColors else FitLightColors,
        typography = FitTypography,
        shapes = FitShapes,
        content = content,
    )
}
