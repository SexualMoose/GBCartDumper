package com.tyler.gbcartdumper.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

/**
 * Shared state that drives the app's accent colour. Every button / chip / dialog
 * action calls [shuffle] to pick a new hue, and the Material theme below reads
 * [hue] every composition to paint the primary / secondary colours.
 *
 * Using a single shared object (instead of per-screen state) means the whole UI
 * re-tints together, not just the widget that was tapped.
 */
class AccentState(initialHue: Float = Random.nextFloat()) {
    var hue: Float by mutableFloatStateOf(initialHue)
        private set

    /** Rotate to a new hue at least 0.25 away from the current one (feels distinct). */
    fun shuffle() {
        var next: Float
        do {
            next = Random.nextFloat()
        } while (kotlin.math.abs(next - hue) < 0.15f)
        hue = next
    }
}

val LocalAccentState = compositionLocalOf { AccentState() }

@Composable
fun rememberAccentState(): AccentState = rememberSaveable(
    saver = androidx.compose.runtime.saveable.Saver(
        save = { it.hue },
        restore = { AccentState(it as Float) },
    )
) { AccentState() }

/** Convert an HSV hue (0..1) to a sRGB Color. */
fun hueToAccent(hue: Float, saturation: Float = 0.60f, value: Float = 0.82f): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf((hue * 360f) % 360f, saturation, value)))
