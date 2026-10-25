package com.rivers.cosmo.ui.banner

import com.rivers.cosmo.settings.BannerStyle
import com.rivers.cosmo.settings.ColorMath
import com.rivers.cosmo.settings.Hsv
import kotlin.math.floor
import kotlin.math.sin

/**
 * The four shades every banner style draws with, all derived from one base color (the theme's
 * accent or the user's pick), as ARGB ints: [deep] for backgrounds up to [glow] for highlights.
 */
data class BannerPalette(val deep: Int, val base: Int, val light: Int, val glow: Int) {
    companion object {
        fun from(baseArgb: Int): BannerPalette {
            val hsv = ColorMath.toHsv(baseArgb)
            return BannerPalette(
                deep = ColorMath.fromHsv(hsv.copy(saturation = (hsv.saturation * 1.1f).coerceAtMost(1f), value = hsv.value * 0.3f)),
                base = baseArgb,
                light = ColorMath.fromHsv(hsv.copy(saturation = hsv.saturation * 0.7f, value = (hsv.value * 1.3f).coerceAtMost(1f))),
                glow = ColorMath.fromHsv(hsv.copy(saturation = hsv.saturation * 0.3f, value = 1f))
            )
        }

        /** Fire shades: the base hue burning from embers to a hotter, lighter hue and white-hot tips. */
        fun fire(baseArgb: Int): BannerPalette {
            val hsv = ColorMath.toHsv(baseArgb)
            val saturation = hsv.saturation.coerceAtLeast(0.6f) // Flames need color even from a grey base.
            return BannerPalette(
                deep = ColorMath.fromHsv(Hsv(hsv.hue, saturation, 0.25f)),
                base = ColorMath.fromHsv(Hsv(hsv.hue, saturation, 0.9f)),
                light = ColorMath.fromHsv(Hsv(hsv.hue + 25f, saturation * 0.8f, 1f)),
                glow = ColorMath.fromHsv(Hsv(hsv.hue + 45f, 0.2f, 1f))
            )
        }
    }
}

/** White or near-black, whichever reads better on the color most of a [style] banner is painted in. */
fun textColorOnBanner(style: BannerStyle, baseArgb: Int): Int {
    val palette = if (style == BannerStyle.Fire) BannerPalette.fire(baseArgb) else BannerPalette.from(baseArgb)
    val background = when (style) {
        BannerStyle.Gradient, BannerStyle.Waves, BannerStyle.Clouds -> palette.base
        else -> palette.deep // Night skies, maps and embers are all drawn mostly in the deep shade.
    }
    val white = 0xFFFFFFFF.toInt()
    val dark = 0xFF111111.toInt()
    return if (ColorMath.contrastRatio(white, background) >= ColorMath.contrastRatio(dark, background)) white else dark
}

/** A cheap, repeatable pseudo-random number in [0, 1) for index [i], so particles keep their identity between frames. */
fun hash01(i: Int, salt: Int = 0): Float {
    val x = sin(i * 12.9898 + salt * 78.233) * 43758.5453
    return (x - floor(x)).toFloat()
}

/**
 * Marching squares: traces where a sampled field crosses [level], as line segments.
 * [values] holds (cols + 1) × (rows + 1) samples, row by row. [segment] receives the end points
 * in grid units (0..cols, 0..rows).
 */
fun contourSegments(values: FloatArray, cols: Int, rows: Int, level: Float, segment: (Float, Float, Float, Float) -> Unit) {
    require(values.size == (cols + 1) * (rows + 1)) { "Expected ${(cols + 1) * (rows + 1)} samples" }
    fun at(x: Int, y: Int) = values[y * (cols + 1) + x]
    fun lerp(a: Float, b: Float) = if (b == a) 0.5f else ((level - a) / (b - a)).coerceIn(0f, 1f)
    for (y in 0 until rows) {
        for (x in 0 until cols) {
            val a = at(x, y)         // top left
            val b = at(x + 1, y)     // top right
            val c = at(x + 1, y + 1) // bottom right
            val d = at(x, y + 1)     // bottom left
            val case = (if (a > level) 8 else 0) or (if (b > level) 4 else 0) or (if (c > level) 2 else 0) or (if (d > level) 1 else 0)
            if (case == 0 || case == 15) continue
            // Where the contour crosses each edge of the cell.
            val topX = x + lerp(a, b); val topY = y.toFloat()
            val rightX = x + 1f; val rightY = y + lerp(b, c)
            val bottomX = x + lerp(d, c); val bottomY = y + 1f
            val leftX = x.toFloat(); val leftY = y + lerp(a, d)
            when (case) {
                1, 14 -> segment(leftX, leftY, bottomX, bottomY)
                2, 13 -> segment(bottomX, bottomY, rightX, rightY)
                3, 12 -> segment(leftX, leftY, rightX, rightY)
                4, 11 -> segment(topX, topY, rightX, rightY)
                6, 9 -> segment(topX, topY, bottomX, bottomY)
                7, 8 -> segment(leftX, leftY, topX, topY)
                5 -> { segment(topX, topY, rightX, rightY); segment(leftX, leftY, bottomX, bottomY) }
                10 -> { segment(leftX, leftY, topX, topY); segment(bottomX, bottomY, rightX, rightY) }
            }
        }
    }
}
