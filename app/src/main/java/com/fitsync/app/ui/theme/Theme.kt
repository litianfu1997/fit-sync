package com.fitsync.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

val FitBlue = Color(0xFF3568F5)
val FitTeal = Color(0xFF0FAF9A)
val FitOrange = Color(0xFFF28B32)
val FitRed = Color(0xFFDC4C64)
val FitInk = Color(0xFF172033)
val FitMuted = Color(0xFF647089)

private val LightColors = lightColorScheme(
    primary = FitBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EAFF),
    onPrimaryContainer = Color(0xFF102C74),
    secondary = FitTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F6F0),
    onSecondaryContainer = Color(0xFF004D43),
    tertiary = FitOrange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE6D0),
    onTertiaryContainer = Color(0xFF6A3400),
    error = FitRed,
    background = Color(0xFFF6F8FC),
    onBackground = FitInk,
    surface = Color.White,
    onSurface = FitInk,
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = FitMuted,
    outline = Color(0xFFD5DBE7),
    outlineVariant = Color(0xFFE6EAF1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CB5FF),
    onPrimary = Color(0xFF082765),
    primaryContainer = Color(0xFF274AAB),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFF70DACA),
    onSecondary = Color(0xFF00372F),
    secondaryContainer = Color(0xFF005F53),
    onSecondaryContainer = Color(0xFFAEF2E7),
    tertiary = Color(0xFFFFB779),
    background = Color(0xFF0D1422),
    onBackground = Color(0xFFE7EAF1),
    surface = Color(0xFF151E2E),
    onSurface = Color(0xFFE7EAF1),
    surfaceVariant = Color(0xFF222D40),
    onSurfaceVariant = Color(0xFFB7C0D1),
    outline = Color(0xFF3B475C),
    outlineVariant = Color(0xFF273246),
)

private val FitTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val FitShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun FitSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = FitTypography,
        shapes = FitShapes,
        content = content,
    )
}
