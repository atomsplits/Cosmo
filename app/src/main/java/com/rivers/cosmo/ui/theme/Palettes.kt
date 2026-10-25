package com.rivers.cosmo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.rivers.cosmo.settings.CustomColors
import com.rivers.cosmo.settings.ThemeMode

/** The three colors a theme is built from; everything else in the color scheme is derived from them. */
data class Palette(val background: Color, val text: Color, val accent: Color) {
    val isDark: Boolean get() = background.luminance() < 0.5f
}

/** The palette for a fixed theme, or null for the themes that follow Material You wallpaper colors. */
fun ThemeMode.palette(custom: CustomColors): Palette? = when (this) {
    ThemeMode.System, ThemeMode.Light, ThemeMode.Dark -> null
    ThemeMode.Amoled -> Palette(Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF8AB4F8))
    ThemeMode.NothingRed -> Palette(Color(0xFF000000), Color(0xFFF2F2F2), Color(0xFFD71921))
    ThemeMode.Paper -> Palette(Color(0xFFF4F3EE), Color(0xFF1A1A1A), Color(0xFFD71921))
    ThemeMode.Midnight -> Palette(Color(0xFF0F172A), Color(0xFFE2E8F0), Color(0xFF38BDF8))
    ThemeMode.Stellar -> Palette(Color(0xFF120B2E), Color(0xFFEDE9FE), Color(0xFFA78BFA))
    ThemeMode.Ocean -> Palette(Color(0xFF06283D), Color(0xFFDFF6FF), Color(0xFF47B5FF))
    ThemeMode.Forest -> Palette(Color(0xFF14241A), Color(0xFFE8F5E9), Color(0xFF81C784))
    ThemeMode.Dracula -> Palette(Color(0xFF282A36), Color(0xFFF8F8F2), Color(0xFFBD93F9))
    ThemeMode.Solarized -> Palette(Color(0xFF002B36), Color(0xFFEEE8D5), Color(0xFFB58900))
    ThemeMode.Sunset -> Palette(Color(0xFFFFF4E6), Color(0xFF3D1F0F), Color(0xFFC2410C))
    ThemeMode.Rose -> Palette(Color(0xFFFFF0F3), Color(0xFF4A1526), Color(0xFFE11D48))
    ThemeMode.Lavender -> Palette(Color(0xFFF5EEFF), Color(0xFF2E1065), Color(0xFF7C3AED))
    ThemeMode.Custom -> Palette(Color(custom.background), Color(custom.text), Color(custom.accent))
}

/** Colors for the theme picker's preview tiles, including approximations of the Material You themes. */
fun ThemeMode.previewPalette(custom: CustomColors, systemDark: Boolean): Palette =
    palette(custom) ?: when {
        this == ThemeMode.Dark || (this == ThemeMode.System && systemDark) ->
            Palette(Color(0xFF1C1B1F), Color(0xFFE6E1E5), Purple80)
        else -> Palette(Color(0xFFFFFBFE), Color(0xFF1C1B1F), Purple40)
    }

fun Palette.toColorScheme(): ColorScheme {
    // Surfaces are the background shifted slightly towards the text color, so cards and the
    // navigation bar stand out in both light and dark themes.
    fun towardsText(fraction: Float) = lerp(background, text, fraction)
    // Black and white have equal contrast against a color whose luminance is about 0.18.
    val onAccent = if (accent.luminance() > 0.18f) Color.Black else Color.White
    val accentContainer = lerp(background, accent, 0.3f)
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = text,
        inversePrimary = accent,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentContainer,
        onSecondaryContainer = text,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentContainer,
        onTertiaryContainer = text,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = towardsText(0.10f),
        onSurfaceVariant = towardsText(0.75f),
        surfaceTint = accent,
        inverseSurface = text,
        inverseOnSurface = background,
        outline = towardsText(0.45f),
        outlineVariant = towardsText(0.20f),
        surfaceBright = towardsText(0.12f),
        surfaceDim = background,
        surfaceContainerLowest = background,
        surfaceContainerLow = towardsText(0.04f),
        surfaceContainer = towardsText(0.06f),
        surfaceContainerHigh = towardsText(0.09f),
        surfaceContainerHighest = towardsText(0.12f)
    )
}
