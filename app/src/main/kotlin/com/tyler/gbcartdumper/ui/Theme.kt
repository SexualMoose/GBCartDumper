package com.tyler.gbcartdumper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * OLED-true-black Material 3 theme sized for the S26 Ultra's 6.9" QHD+ panel.
 * Accents use classic Game Boy green over deep charcoal.
 */
private val GBGreen = Color(0xFF9CCC65)
private val GBGreenDim = Color(0xFF7CB342)
private val Oled = Color(0xFF000000)
private val Surface = Color(0xFF0B0E13)
private val SurfaceVariant = Color(0xFF161B22)
private val OnSurface = Color(0xFFE6EAF2)
private val OnSurfaceMuted = Color(0xFF9AA4B2)
private val ErrorRed = Color(0xFFFF6B6B)

private val DarkColors = darkColorScheme(
    primary = GBGreen,
    onPrimary = Color.Black,
    primaryContainer = GBGreenDim,
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFF80DEEA),
    background = Oled,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceMuted,
    error = ErrorRed,
    outline = Color(0xFF2A313C),
)

private val LightColors = lightColorScheme(
    primary = GBGreenDim,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF11151C),
    error = ErrorRed,
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
)

@Composable
fun GBCartDumperTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
