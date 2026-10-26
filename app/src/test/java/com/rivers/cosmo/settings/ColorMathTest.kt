package com.rivers.cosmo.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorMathTest {
    private val red = 0xFFFF0000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun convertsToAndFromHsv() {
        assertEquals(Hsv(0f, 1f, 1f), ColorMath.toHsv(red))
        assertEquals(Hsv(120f, 1f, 1f), ColorMath.toHsv(0xFF00FF00.toInt()))
        assertEquals(Hsv(240f, 1f, 1f), ColorMath.toHsv(0xFF0000FF.toInt()))
        assertEquals(red, ColorMath.fromHsv(Hsv(0f, 1f, 1f)))
        assertEquals(white, ColorMath.fromHsv(Hsv(200f, 0f, 1f)))
        assertEquals(black, ColorMath.fromHsv(Hsv(200f, 1f, 0f)))
    }

    @Test
    fun hsvRoundTripsEveryPresetLikeColor() {
        listOf(0xFF101418, 0xFFE8EAED, 0xFF4FC3F7, 0xFFD71921, 0xFF7C3AED, 0xFF93A1A1).forEach {
            val argb = it.toInt()
            assertEquals(ColorMath.toHex(argb), ColorMath.toHex(ColorMath.fromHsv(ColorMath.toHsv(argb))))
        }
    }

    @Test
    fun formatsAndParsesHex() {
        assertEquals("#D71921", ColorMath.toHex(0xFFD71921.toInt()))
        assertEquals(0xFFD71921.toInt(), ColorMath.parseHex("#d71921"))
        assertEquals(0xFFD71921.toInt(), ColorMath.parseHex("D71921"))
        assertNull(ColorMath.parseHex("#D7192"))
        assertNull(ColorMath.parseHex("#GGGGGG"))
    }

    @Test
    fun computesContrast() {
        assertEquals(21.0, ColorMath.contrastRatio(black, white), 0.01)
        assertEquals(1.0, ColorMath.contrastRatio(red, red), 0.01)
        assertEquals(4.0, ColorMath.contrastRatio(red, white), 0.01)
    }

    @Test
    fun mixesColors() {
        assertEquals(black, ColorMath.mix(black, white, 0f))
        assertEquals(white, ColorMath.mix(black, white, 1f))
        assertEquals(0xFF808080.toInt(), ColorMath.mix(black, white, 0.5f))
        assertEquals(white, ColorMath.mix(black, white, 7f)) // clamped
    }
}
