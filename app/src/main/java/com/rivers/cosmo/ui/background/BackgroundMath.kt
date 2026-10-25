package com.rivers.cosmo.ui.background

import com.rivers.cosmo.settings.ColorMath

/**
 * The colors a background is painted in: [color] blended into the theme's [background] by up to
 * [strength], but never so far that [text] drawn on top becomes hard to read (WCAG AA, 4.5:1).
 */
fun readableTint(background: Int, text: Int, color: Int, strength: Float): Int {
    var fraction = strength.coerceIn(0f, 1f)
    var tint = ColorMath.mix(background, color, fraction)
    // Back off in small steps until the theme's text color reads clearly on the tint again.
    while (fraction > 0f && ColorMath.contrastRatio(text, tint) < ColorMath.MIN_READABLE_CONTRAST) {
        fraction = (fraction - 0.05f).coerceAtLeast(0f)
        tint = ColorMath.mix(background, color, fraction)
    }
    return tint
}
