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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

val FitOrange = Color(0xFFF97316)
val FitOrangeSoft = Color(0xFFFFB067)
val FitTeal = Color(0xFF22C55E)
val FitBlue = Color(0xFF38BDF8)
val FitRed = Color(0xFFFB5B67)
val FitInk = Color(0xFF111827)
val FitMuted = Color(0xFF667085)
val FitNight = Color(0xFF0B0F14)
val FitNightRaised = Color(0xFF151A22)

private val LightColors = lightColorScheme(
    primary = FitOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE8D5),
    onPrimaryContainer = Color(0xFF5B2500),
    secondary = FitTeal,
    onSecondary = Color(0xFF052E16),
    secondaryContainer = Color(0xFFDCFCE7),
    onSecondaryContainer = Color(0xFF14532D),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0F2FE),
    onTertiaryContainer = Color(0xFF0C4A6E),
    error = FitRed,
    background = Color(0xFFF7F5F2),
    onBackground = FitInk,
    surface = Color(0xFFFFFCF9),
    onSurface = FitInk,
    surfaceVariant = Color(0xFFF0ECE7),
    onSurfaceVariant = FitMuted,
    outline = Color(0xFFD6D0C9),
    outlineVariant = Color(0xFFE8E2DC),
)

private val DarkColors = darkColorScheme(
    primary = FitOrangeSoft,
    onPrimary = Color(0xFF4B1D00),
    primaryContainer = Color(0xFF713400),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFF6EE7A0),
    onSecondary = Color(0xFF06391A),
    secondaryContainer = Color(0xFF155B2C),
    onSecondaryContainer = Color(0xFFB8F5CB),
    tertiary = Color(0xFF7DD3FC),
    onTertiary = Color(0xFF06374B),
    tertiaryContainer = Color(0xFF075A79),
    onTertiaryContainer = Color(0xFFC8EEFF),
    error = Color(0xFFFF8A93),
    background = FitNight,
    onBackground = Color(0xFFF3F4F6),
    surface = FitNightRaised,
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF202630),
    onSurfaceVariant = Color(0xFFB8C0CC),
    outline = Color(0xFF3A424E),
    outlineVariant = Color(0xFF282F38),
)

private val FitTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 32.sp,
        lineHeight = 37.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 25.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
)

private val FitShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
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
