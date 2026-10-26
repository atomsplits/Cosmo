package com.rivers.cosmo.settings

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A color as hue (0–360°), saturation (0–1) and value/brightness (0–1): the color wheel's coordinates. */
data class Hsv(val hue: Float, val saturation: Float, val value: Float)

/** Helpers for colors stored as opaque ARGB ints (e.g. `0xFF1A2B3C.toInt()`). */
object ColorMath {

    fun toHsv(argb: Int): Hsv {
        val r = red(argb) / 255f
        val g = green(argb) / 255f
        val b = blue(argb) / 255f
        val maxC = max(r, max(g, b))
        val delta = maxC - min(r, min(g, b))
        val hue = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta).mod(6f))
            maxC == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return Hsv(hue, if (maxC == 0f) 0f else delta / maxC, maxC)
    }

    fun fromHsv(hsv: Hsv): Int {
        val h = hsv.hue.mod(360f)
        val s = hsv.saturation.coerceIn(0f, 1f)
        val v = hsv.value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - abs((h / 60f).mod(2f) - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    /** Blends [from] towards [to] by [fraction] (0 = all [from], 1 = all [to]), channel by channel. */
    fun mix(from: Int, to: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        fun channel(a: Int, b: Int) = (a + (b - a) * t).roundToInt()
        return argb(channel(red(from), red(to)), channel(green(from), green(to)), channel(blue(from), blue(to)))
    }

    /** e.g. `#1A2B3C`. */
    fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** Parses `#1A2B3C` or `1A2B3C`, or returns null. */
    fun parseHex(text: String): Int? {
        val digits = text.trim().removePrefix("#")
        if (digits.length != 6 || digits.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        return (0xFF000000.toInt()) or digits.toInt(16)
    }

    /** WCAG contrast ratio between two colors, from 1 (identical) to 21 (black on white). */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** WCAG relative luminance, from 0 (black) to 1 (white). */
    fun relativeLuminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(red(argb)) + 0.7152 * channel(green(argb)) + 0.0722 * channel(blue(argb))
    }

    /** Below this, text is hard to read (WCAG AA for normal text). */
    const val MIN_READABLE_CONTRAST = 4.5

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
