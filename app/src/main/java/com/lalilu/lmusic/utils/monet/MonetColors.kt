package com.lalilu.lmusic.utils.monet

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.max
import kotlin.math.min

/**
 * A small Material-You / Monet style palette derived from the current artwork.
 *
 * The source swatch supplies the hue, while saturation and lightness are harmonized for the
 * active theme. That keeps the result readable and avoids the muddy, oversaturated gradients
 * that raw `Palette.getDarkVibrantColor()` tends to produce.
 */
data class MonetColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val surface: Color,
    val onSurface: Color,
    val flow: List<Color>,
)

@Composable
fun rememberMonetColors(palette: Palette?): MonetColors {
    val dark = !MaterialTheme.colors.isLight
    val key = palette?.let {
        "${it.dominantSwatch?.rgb}:${it.vibrantSwatch?.rgb}:${it.mutedSwatch?.rgb}:$dark"
    }
    return remember(key) { buildMonetColors(palette, dark) }
}

fun buildMonetColors(palette: Palette?, dark: Boolean): MonetColors {
    val seed = palette?.vibrantSwatch?.rgb
        ?: palette?.dominantSwatch?.rgb
        ?: palette?.mutedSwatch?.rgb
        ?: 0xFF7A6CC9.toInt()

    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed, hsl)
    // Monochrome artwork otherwise produces a lifeless grey glass. Give it a tasteful
    // violet/blue Monet accent so the flowing text still has a visible dye.
    if (hsl[1] < 0.08f) {
        hsl[0] = 264f
        hsl[1] = 0.42f
    }
    val hue = hsl[0]

    val primary = harmonized(hsl, dark, saturation = 0.52f, lightness = if (dark) 0.74f else 0.42f)
    val secondary = harmonized(
        floatArrayOf((hue + 22f) % 360f, hsl[1], hsl[2]),
        dark,
        saturation = 0.42f,
        lightness = if (dark) 0.66f else 0.48f
    )
    val tertiary = harmonized(
        floatArrayOf((hue + 320f) % 360f, hsl[1], hsl[2]),
        dark,
        saturation = 0.48f,
        lightness = if (dark) 0.70f else 0.46f
    )
    val surface = if (dark) {
        blend(Color(0xFF121216), primary, 0.18f)
    } else {
        blend(Color(0xFFF7F5FA), primary, 0.12f)
    }
    val onSurface = if (dark) Color(0xFFF3F1F6) else Color(0xFF1B1B1F)

    val flow = listOf(
        primary.copy(alpha = 0.95f),
        tertiary.copy(alpha = 0.95f),
        secondary.copy(alpha = 0.95f),
        primary.copy(alpha = 0.95f),
    )
    return MonetColors(primary, secondary, tertiary, surface, onSurface, flow)
}

/** Palette-tinted colors which stay readable on a dark frosted-glass surface. */
val MonetColors.lightFlow: List<Color>
    get() = listOf(
        primary,
        lerp(tertiary, Color.White, 0.12f),
        Color.White.copy(alpha = 0.98f),
        lerp(secondary, Color.White, 0.10f),
        primary,
    )

fun MonetColors.readableOn(color: Color): Color {
    return if (color.luminance() > 0.5f) Color(0xFF15151A) else Color.White
}

private fun harmonized(
    hslSource: FloatArray,
    dark: Boolean,
    saturation: Float,
    lightness: Float,
): Color {
    val hsl = hslSource.copyOf()
    hsl[1] = min(max(hsl[1], saturation * 0.55f), saturation)
    hsl[2] = if (dark) {
        max(hsl[2].coerceIn(0.55f, 0.9f), lightness)
    } else {
        min(hsl[2].coerceIn(0.24f, 0.55f), lightness)
    }
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun blend(base: Color, overlay: Color, fraction: Float): Color {
    return Color(
        red = base.red + (overlay.red - base.red) * fraction,
        green = base.green + (overlay.green - base.green) * fraction,
        blue = base.blue + (overlay.blue - base.blue) * fraction,
        alpha = 1f,
    )
}
