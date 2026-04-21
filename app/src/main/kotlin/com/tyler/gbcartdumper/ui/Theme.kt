package com.tyler.gbcartdumper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * OLED-true-black Material 3 theme. The primary/secondary colours are derived
 * from a live [AccentState] hue so every interaction randomises the accent.
 */
private val Oled = Color(0xFF000000)
private val Surface = Color(0xFF0B0E13)
private val SurfaceVariant = Color(0xFF161B22)
private val OnSurface = Color(0xFFE6EAF2)
private val OnSurfaceMuted = Color(0xFF9AA4B2)
private val ErrorRed = Color(0xFFFF6B6B)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
)

private fun pickOnColor(base: Color): Color =
    if (base.luminance() < 0.4f) Color.White else Color.Black

@Composable
fun GBCartDumperTheme(
    accentState: AccentState = rememberAccentState(),
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val primary = hueToAccent(accentState.hue)
    // Secondary is shifted ~0.33 around the wheel from primary for a complementary feel.
    val secondary = hueToAccent((accentState.hue + 0.33f) % 1f, saturation = 0.50f, value = 0.78f)
    val primaryContainer = hueToAccent(accentState.hue, saturation = 0.40f, value = 0.40f)

    val scheme = if (dark) darkColorScheme(
        primary = primary,
        onPrimary = pickOnColor(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = pickOnColor(primaryContainer),
        secondary = secondary,
        onSecondary = pickOnColor(secondary),
        background = Oled,
        onBackground = OnSurface,
        surface = Surface,
        onSurface = OnSurface,
        surfaceVariant = SurfaceVariant,
        onSurfaceVariant = OnSurfaceMuted,
        error = ErrorRed,
        outline = Color(0xFF2A313C),
    ) else lightColorScheme(
        primary = primary,
        onPrimary = pickOnColor(primary),
        secondary = secondary,
        background = Color.White,
        onBackground = Color.Black,
        surface = Color(0xFFF7F8FA),
        onSurface = Color(0xFF11151C),
        error = ErrorRed,
    )

    CompositionLocalProvider(LocalAccentState provides accentState) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content
        )
    }
}
