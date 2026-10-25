package com.rivers.cosmo.ui.background

import com.rivers.cosmo.settings.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundMathTest {
    private val darkBackground = 0xFF121212.toInt()
    private val lightText = 0xFFE6E1E5.toInt()

    @Test
    fun tintsByTheRequestedStrengthWhenTextStaysReadable() {
        val purple = 0xFF7C4DFF.toInt()
        assertEquals(ColorMath.mix(darkBackground, purple, 0.35f), readableTint(darkBackground, lightText, purple, 0.35f))
    }

    @Test
    fun holdsBackBrightColorsThatWouldWashOutText() {
        val yellow = 0xFFFFEB3B.toInt()
        val tint = readableTint(darkBackground, lightText, yellow, 0.7f)
        assertTrue(ColorMath.contrastRatio(lightText, tint) >= ColorMath.MIN_READABLE_CONTRAST)
        assertTrue(tint != darkBackground) // still tinted, just less
        // Same on a light theme with a dark color.
        val white = 0xFFFFFBFE.toInt()
        val darkText = 0xFF1C1B1F.toInt()
        val navy = 0xFF0D1B4C.toInt()
        assertTrue(ColorMath.contrastRatio(darkText, readableTint(white, darkText, navy, 0.7f)) >= ColorMath.MIN_READABLE_CONTRAST)
    }
}
