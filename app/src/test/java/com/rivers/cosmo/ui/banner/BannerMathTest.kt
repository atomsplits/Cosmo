package com.rivers.cosmo.ui.banner

import com.rivers.cosmo.settings.BannerStyle
import com.rivers.cosmo.settings.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerMathTest {
    @Test
    fun paletteRunsFromDarkToLight() {
        val palette = BannerPalette.from(0xFF4FC3F7.toInt())
        val luminances = listOf(palette.deep, palette.base, palette.light, palette.glow).map(ColorMath::relativeLuminance)
        assertEquals(luminances.sorted(), luminances)
        // Every shade keeps the base color's hue (here, light blue).
        assertEquals(ColorMath.toHsv(palette.base).hue, ColorMath.toHsv(palette.deep).hue, 2f)
    }

    @Test
    fun fireStillBurnsFromAGreyBase() {
        val fire = BannerPalette.fire(0xFF808080.toInt())
        // At least 0.6 before the color is rounded to 8-bit channels.
        assertTrue(ColorMath.toHsv(fire.base).saturation > 0.58f)
        assertTrue(ColorMath.relativeLuminance(fire.glow) > ColorMath.relativeLuminance(fire.deep))
    }

    @Test
    fun hashIsRepeatableAndInRange() {
        assertEquals(hash01(7, 3), hash01(7, 3))
        (0 until 1000).map { hash01(it) }.forEach { assertTrue(it >= 0f && it < 1f) }
        assertTrue(hash01(1) != hash01(2))
    }

    @Test
    fun tracesAStraightContour() {
        // f(x, y) = x on a 4×3 grid: the 1.5 contour is the vertical line x = 1.5.
        val cols = 4
        val rows = 3
        val values = FloatArray((cols + 1) * (rows + 1)) { i -> (i % (cols + 1)).toFloat() }
        val segments = mutableListOf<List<Float>>()
        contourSegments(values, cols, rows, 1.5f) { x1, y1, x2, y2 -> segments += listOf(x1, y1, x2, y2) }
        assertEquals(rows, segments.size)
        segments.forEach { (x1, _, x2, _) ->
            assertEquals(1.5f, x1, 1e-6f)
            assertEquals(1.5f, x2, 1e-6f)
        }
    }

    @Test
    fun tracesAClosedRingAroundAPeak() {
        // A single raised point in the middle: the contour is a small diamond of 4 segments.
        val values = FloatArray(9).also { it[4] = 1f }
        var count = 0
        contourSegments(values, 2, 2, 0.5f) { _, _, _, _ -> count++ }
        assertEquals(4, count)
    }

    @Test
    fun picksReadableTitleText() {
        val white = 0xFFFFFFFF.toInt()
        val dark = 0xFF111111.toInt()
        assertEquals(white, textColorOnBanner(BannerStyle.Stars, 0xFFFFEB3B.toInt())) // dark sky even for yellow
        assertEquals(dark, textColorOnBanner(BannerStyle.Gradient, 0xFFFFEB3B.toInt()))
        assertEquals(white, textColorOnBanner(BannerStyle.Gradient, 0xFF1A237E.toInt()))
    }
}
