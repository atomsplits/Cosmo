package com.rivers.cosmo.ui.banner

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.BannerStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

private class BannerColors(palette: BannerPalette) {
    val deep = Color(palette.deep)
    val base = Color(palette.base)
    val light = Color(palette.light)
    val glow = Color(palette.glow)
    /** Darker than [deep], for night skies. */
    val night = lerp(Color.Black, deep, 0.5f)
}

/**
 * An animated, decorative banner drawn in one of several [BannerStyle]s, colored from [baseColor].
 * Only the draw phase repeats each frame, so animating doesn't recompose anything. It shows a still
 * frame when [animated] is off or the system's "Remove animations" setting is on.
 */
@Composable
fun AnimatedBanner(
    style: BannerStyle,
    baseColor: Color,
    animated: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val colors = remember(baseColor, style) {
        BannerColors(if (style == BannerStyle.Fire) BannerPalette.fire(baseColor.toArgb()) else BannerPalette.from(baseColor.toArgb()))
    }
    val time = rememberBannerTime(animated && systemAnimationsEnabled())
    val path = remember { Path() }
    val field = remember { FloatArray((TOPO_COLS + 1) * (TOPO_ROWS + 1)) }

    Box(modifier.clipToBounds()) {
        Canvas(Modifier.matchParentSize()) {
            val t = time.floatValue
            when (style) {
                BannerStyle.Off -> Unit
                BannerStyle.Gradient -> gradient(colors, t)
                BannerStyle.Topographic -> topographic(colors, t, field, path)
                BannerStyle.Clouds -> clouds(colors, t)
                BannerStyle.Fire -> fire(colors, t, path)
                BannerStyle.Aurora -> aurora(colors, t, path)
                BannerStyle.Waves -> waves(colors, t, path)
                BannerStyle.Stars -> stars(colors, t)
            }
        }
        content()
    }
}

/** Seconds since the banner appeared, ticking once per frame while [animated]. */
@Composable
private fun rememberBannerTime(animated: Boolean): FloatState {
    val time = remember { mutableFloatStateOf(STILL_FRAME_TIME) }
    if (animated) {
        LaunchedEffect(Unit) {
            val offset = time.floatValue
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { time.floatValue = offset + (it - start) / 1_000_000_000f }
            }
        }
    }
    return time
}

@Composable
private fun systemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

private const val STILL_FRAME_TIME = 12f
private const val TOPO_COLS = 48
private const val TOPO_ROWS = 16

private fun DrawScope.gradient(c: BannerColors, t: Float) {
    val w = size.width
    // A mirrored gradient sliding sideways; one cycle (2w) wraps back seamlessly.
    val shift = (t * 0.08f) % 2f
    drawRect(
        Brush.linearGradient(
            listOf(c.deep, c.base, c.light, c.base, c.deep),
            start = Offset(-w + shift * w, 0f),
            end = Offset(shift * w, size.height),
            tileMode = TileMode.Mirror
        )
    )
}

private fun DrawScope.topographic(c: BannerColors, t: Float, field: FloatArray, path: Path) {
    drawRect(c.deep)
    val aspect = size.height / size.width
    // A slowly shifting landscape built from overlapping sine "hills".
    for (y in 0..TOPO_ROWS) {
        for (x in 0..TOPO_COLS) {
            val wx = x.toFloat() / TOPO_COLS * 6f
            val wy = y.toFloat() / TOPO_ROWS * 6f * aspect
            field[y * (TOPO_COLS + 1) + x] = sin(wx * 1.1f + t * 0.25f) + sin(wy * 1.7f - t * 0.2f) +
                sin((wx + wy) * 0.7f + t * 0.15f) + 0.6f * sin(hypot(wx - 3f, wy - 1f) * 1.6f - t * 0.3f)
        }
    }
    val cellW = size.width / TOPO_COLS
    val cellH = size.height / TOPO_ROWS
    val thin = Stroke(width = 1.dp.toPx())
    val thick = Stroke(width = 2.dp.toPx())
    for (i in 0..12) {
        val level = -3f + i * 0.5f
        path.reset()
        contourSegments(field, TOPO_COLS, TOPO_ROWS, level) { x1, y1, x2, y2 ->
            path.moveTo(x1 * cellW, y1 * cellH)
            path.lineTo(x2 * cellW, y2 * cellH)
        }
        // Every fourth line is an "index contour", drawn bolder like on a real map.
        val index = i % 4 == 0
        drawPath(path, if (index) c.glow.copy(alpha = 0.8f) else c.light.copy(alpha = 0.45f), style = if (index) thick else thin)
    }
}

private fun DrawScope.clouds(c: BannerColors, t: Float) {
    drawRect(Brush.verticalGradient(listOf(c.base, c.light)))
    // Far clouds first, so nearer (bigger, faster, whiter) ones drift over them.
    (0 until 7).sortedBy { hash01(it, 1) }.forEach { i ->
        val depth = hash01(i, 1)
        val cloudWidth = size.width * (0.25f + depth * 0.25f)
        val span = size.width + cloudWidth * 2
        val x = (hash01(i, 2) * span + t * (8f + depth * 22f).dp.toPx()) % span - cloudWidth
        val y = size.height * (0.2f + hash01(i, 3) * 0.55f)
        cloud(x, y, cloudWidth, lerp(c.light, c.glow, 0.4f + depth * 0.6f))
    }
}

private fun DrawScope.cloud(x: Float, y: Float, width: Float, color: Color) {
    val puffs = floatArrayOf(0.2f, 0f, 0.2f, 0.42f, -0.14f, 0.28f, 0.66f, -0.05f, 0.24f, 0.86f, 0.04f, 0.16f)
    for (p in puffs.indices step 3) {
        drawCircle(color, radius = puffs[p + 2] * width * 0.6f, center = Offset(x + puffs[p] * width, y + puffs[p + 1] * width * 0.6f))
    }
    drawRoundRect(
        color,
        topLeft = Offset(x + width * 0.05f, y),
        size = Size(width * 0.9f, width * 0.14f),
        cornerRadius = CornerRadius(width * 0.07f)
    )
}

private fun DrawScope.fire(c: BannerColors, t: Float, path: Path) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(c.night, c.deep)))
    // Heat glowing along the bottom.
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.base.copy(alpha = 0.35f)), startY = h * 0.4f, endY = h))
    val tongues = 14
    val spacing = w / tongues
    for (i in 0 until tongues) {
        // Each tongue flickers in height and sways at its own pace.
        val flicker = 0.5f + 0.3f * sin(t * (2.3f + hash01(i, 14) * 2f) + i * 1.7f) + 0.2f * sin(t * 5.1f + i * 0.9f)
        val height = h * (0.45f + 0.45f * flicker) * (0.75f + 0.25f * hash01(i, 15))
        val centerX = (i + 0.5f) * spacing + sin(t * 1.3f + i) * spacing * 0.15f
        val sway = sin(t * 3f + i * 2.1f) * spacing * 0.5f
        // Outer flame, then a smaller, hotter core inside it, added together so they glow.
        listOf(Triple(c.base, 1f, 1.7f), Triple(c.light.copy(alpha = 0.8f), 0.72f, 1.1f), Triple(c.glow.copy(alpha = 0.6f), 0.42f, 0.6f)).forEach { (color, heightScale, widthScale) ->
            val tongueHeight = height * heightScale
            val halfWidth = spacing * widthScale * 0.5f
            val tipX = centerX + sway * heightScale
            path.reset()
            path.moveTo(centerX - halfWidth, h)
            path.cubicTo(centerX - halfWidth, h - tongueHeight * 0.45f, tipX - halfWidth * 0.2f, h - tongueHeight * 0.75f, tipX, h - tongueHeight)
            path.cubicTo(tipX + halfWidth * 0.2f, h - tongueHeight * 0.75f, centerX + halfWidth, h - tongueHeight * 0.45f, centerX + halfWidth, h)
            path.close()
            drawPath(
                path,
                Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = color.alpha * 0.55f), color), startY = h - tongueHeight, endY = h),
                blendMode = BlendMode.Plus
            )
        }
    }
    // Sparks drifting up and fading out.
    for (i in 0 until 28) {
        val life = (t * (0.25f + hash01(i, 4) * 0.25f) + hash01(i, 5)) % 1f
        val x = hash01(i, 6) * w + sin(t * 2f + i) * 8.dp.toPx()
        val y = h - life * h * 1.1f
        drawCircle(c.glow.copy(alpha = 1f - life), radius = (2.dp.toPx() * (1f - life)).coerceAtLeast(0.5f), center = Offset(x, y))
    }
}

private fun DrawScope.aurora(c: BannerColors, t: Float, @Suppress("UNUSED_PARAMETER") path: Path) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(c.night, c.deep)))
    for (i in 0 until 40) {
        drawCircle(Color.White.copy(alpha = 0.3f + hash01(i, 11) * 0.5f), 0.8.dp.toPx(), Offset(hash01(i, 12) * w, hash01(i, 13) * h * 0.8f))
    }
    // Curtains of light: rays rising from a bright, wavy lower edge and fading upwards, like a real aurora.
    val rayWidth = 2.dp.toPx()
    listOf(c.glow, c.light, c.base).forEachIndexed { r, color ->
        var x = 0f
        var ray = 0
        while (x <= w) {
            val u = x / w
            val edge = h * (0.5f + r * 0.14f) + sin(u * 4f + t * 0.35f + r * 2f) * h * 0.12f + sin(u * 11f - t * 0.6f + r) * h * 0.04f
            // Rays brighten and stretch in slow pulses travelling along the curtain; each ray also
            // gets its own brightness so the curtain looks streaky rather than evenly striped.
            val pulse = 0.5f + 0.5f * sin(u * 23f + t * 1.4f + r * 1.3f) * sin(u * 7f - t * 0.5f)
            val streak = 0.55f + 0.45f * hash01(ray, 20 + r)
            val length = h * (0.25f + 0.35f * pulse)
            val bottom = edge + h * 0.06f // fades out just below the bright edge
            drawLine(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.85f to color.copy(alpha = (0.2f + 0.4f * pulse) * streak),
                    1f to Color.Transparent,
                    startY = edge - length,
                    endY = bottom
                ),
                start = Offset(x, edge - length),
                end = Offset(x, bottom),
                strokeWidth = rayWidth,
                blendMode = BlendMode.Plus
            )
            x += rayWidth * 0.75f
            ray++
        }
    }
}

private fun DrawScope.waves(c: BannerColors, t: Float, path: Path) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(c.light, c.base)))
    for (i in 0 until 4) {
        val baseline = h * (0.4f + i * 0.16f)
        val amplitude = h * (0.08f - i * 0.012f)
        val direction = if (i % 2 == 0) 1f else -1f
        val speed = (0.6f + i * 0.35f) * direction
        path.reset()
        path.moveTo(0f, h)
        val steps = 48
        for (s in 0..steps) {
            val x = s.toFloat() / steps
            path.lineTo(x * w, baseline + sin(x * (5f + i) + t * speed + i) * amplitude + sin(x * 13f - t * speed * 0.7f) * amplitude * 0.3f)
        }
        path.lineTo(w, h)
        path.close()
        drawPath(path, lerp(c.base, c.deep, i / 3f).copy(alpha = 0.85f))
    }
}

private fun DrawScope.stars(c: BannerColors, t: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(c.night, c.deep)))
    // A faint nebula in the theme color.
    drawCircle(
        Brush.radialGradient(listOf(c.base.copy(alpha = 0.35f), Color.Transparent), center = Offset(w * 0.7f, h * 0.4f), radius = w * 0.45f),
        radius = w * 0.45f,
        center = Offset(w * 0.7f, h * 0.4f)
    )
    for (i in 0 until 90) {
        val depth = hash01(i, 9)
        val x = (hash01(i, 7) * w + t * (2f + depth * 6f).dp.toPx()) % w
        val y = hash01(i, 8) * h
        val twinkle = 0.55f + 0.45f * sin(t * (1f + hash01(i, 10) * 3f) + i)
        drawCircle(c.glow.copy(alpha = twinkle * (0.35f + 0.65f * depth)), radius = (0.6f + depth * 1.4f).dp.toPx(), center = Offset(x, y))
    }
    // A shooting star every seven seconds.
    val phase = (t % 7f) / 7f
    if (phase < 0.12f) {
        val progress = phase / 0.12f
        val head = Offset(w * (0.9f - progress * 0.5f), h * (0.1f + progress * 0.5f))
        val tail = head + Offset(w * 0.12f, -h * 0.12f)
        drawLine(
            Brush.linearGradient(listOf(Color.White.copy(alpha = 1f - progress), Color.Transparent), start = head, end = tail),
            start = head,
            end = tail,
            strokeWidth = 1.5.dp.toPx()
        )
    }
}
