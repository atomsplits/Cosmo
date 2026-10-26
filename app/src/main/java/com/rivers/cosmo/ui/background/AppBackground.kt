package com.rivers.cosmo.ui.background

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.BackgroundSettings
import com.rivers.cosmo.settings.BackgroundStyle
import kotlin.math.max

/** The two colors a background is drawn with, from the theme or the user's picks. */
fun backgroundColors(settings: BackgroundSettings, scheme: ColorScheme): Pair<Color, Color> {
    if (!settings.matchTheme) return Color(settings.colorA) to Color(settings.colorB)
    // Some themes use one accent for everything; then pair it with its container shade instead.
    val second = if (scheme.tertiary != scheme.primary) scheme.tertiary else scheme.primaryContainer
    return scheme.primary to second
}

/**
 * The app-wide background drawn behind every screen, in the user's chosen [BackgroundSettings].
 * It's static (no animation), and its colors are held back where needed to keep text readable.
 */
@Composable
fun AppBackground(settings: BackgroundSettings, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (a, b) = backgroundColors(settings, scheme)
    BackgroundCanvas(settings.style, scheme.background, scheme.onBackground, a, b, settings.strength, modifier)
}

/** Draws a background style; also used for the small previews in Settings. */
@Composable
fun BackgroundCanvas(
    style: BackgroundStyle,
    background: Color,
    text: Color,
    colorA: Color,
    colorB: Color,
    strength: Float,
    modifier: Modifier = Modifier
) {
    val tintA = Color(readableTint(background.toArgb(), text.toArgb(), colorA.toArgb(), strength))
    val tintB = Color(readableTint(background.toArgb(), text.toArgb(), colorB.toArgb(), strength))
    Spacer(modifier.drawWithCache { onDrawBehind { drawAppBackground(style, background, tintA, tintB) } })
}

/** Paints a background [style] over the whole draw area, using colors already toned for readability. */
fun DrawScope.drawAppBackground(style: BackgroundStyle, background: Color, tintA: Color, tintB: Color) {
    val w = size.width
    val h = size.height
    val spacing = 22.dp.toPx()
    drawRect(background)
    when (style) {
        BackgroundStyle.Plain -> Unit
        BackgroundStyle.Gradient -> drawRect(Brush.linearGradient(listOf(tintA, tintB), start = Offset.Zero, end = Offset(w, h)))
        BackgroundStyle.Fade -> drawRect(Brush.verticalGradient(listOf(tintA, lerp(tintA, tintB, 0.5f), background), endY = h * 0.7f))
        BackgroundStyle.PolkaDots -> {
            // Dots that change color and grow along the diagonal, on a faint wash of the same gradient.
            drawRect(Brush.linearGradient(listOf(lerp(background, tintA, 0.35f), lerp(background, tintB, 0.35f)), end = Offset(w, h)))
            var y = spacing / 2
            var row = 0
            while (y < h + spacing) {
                var x = if (row % 2 == 0) spacing / 2 else spacing
                while (x < w + spacing) {
                    val t = ((x / w + y / h) / 2f).coerceIn(0f, 1f)
                    drawCircle(lerp(tintA, tintB, t), radius = 1.5.dp.toPx() + 3.dp.toPx() * t, center = Offset(x, y))
                    x += spacing
                }
                y += spacing * 0.87f // rows a little closer than columns, for a hexagonal pattern
                row++
            }
        }
        BackgroundStyle.Glow -> {
            val radius = max(w, h) * 0.8f
            drawCircle(Brush.radialGradient(listOf(tintA, Color.Transparent), center = Offset(0f, 0f), radius = radius), radius, Offset(0f, 0f))
            drawCircle(Brush.radialGradient(listOf(tintB, Color.Transparent), center = Offset(w, h), radius = radius), radius, Offset(w, h))
        }
        BackgroundStyle.Mesh -> {
            // Soft overlapping blobs, like a mesh gradient.
            listOf(
                Triple(tintA, Offset(w * 0.15f, h * 0.15f), 0.75f),
                Triple(tintB, Offset(w * 0.9f, h * 0.45f), 0.7f),
                Triple(lerp(tintA, tintB, 0.5f), Offset(w * 0.3f, h * 0.9f), 0.8f)
            ).forEach { (color, center, size) ->
                val radius = max(w, h) * size
                drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius), radius, center)
            }
        }
        BackgroundStyle.Stripes -> {
            // Wide diagonal stripes shading from one color to the other.
            val gap = spacing * 1.6f
            var offset = -h
            while (offset < w) {
                val t = ((offset + h) / (w + h)).coerceIn(0f, 1f)
                // Drawn at about half strength: full-strength stripes are too busy behind text.
            drawLine(lerp(background, lerp(tintA, tintB, t), 0.55f), Offset(offset, h), Offset(offset + h, 0f), strokeWidth = gap * 0.45f)
                offset += gap
            }
        }
    }
}
