package com.rivers.cosmo.settings

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A theme to share: the look of the app (theme and custom colors, font, banner and background),
 * nothing personal. Fonts the user added aren't included, as the file wouldn't fit in a QR code;
 * a theme using one falls back to its built-in font.
 */
data class SharedTheme(
    val themeMode: ThemeMode,
    val customColors: CustomColors,
    val font: AppFont,
    val banner: BannerSettings,
    val background: BackgroundSettings
) {
    fun applyTo(settings: AppSettings): AppSettings = settings.copy(
        themeMode = themeMode,
        customColors = customColors,
        font = font,
        customFont = null,
        banner = banner,
        background = background
    )

    companion object {
        fun from(settings: AppSettings) = SharedTheme(settings.themeMode, settings.customColors, settings.font, settings.banner, settings.background)
    }
}

/**
 * Turns a theme into a short code for QR codes and back, e.g.
 * `COSMO-THEME:1:{"m":"Custom","c":["#101418","#E8EAED","#4FC3F7"],"f":"Inter",…}`.
 * Codes are untrusted input (anyone can make a QR code), so reading one checks every value.
 */
object ThemeCode {
    private const val PREFIX = "COSMO-THEME:"
    private const val VERSION = 1
    /** Comfortably more than a real theme needs, and still a scannable QR code. */
    private const val MAX_LENGTH = 1000

    fun encode(theme: SharedTheme): String {
        val json = JSONObject()
            .put("m", theme.themeMode.name)
            .put("c", JSONArray(listOf(theme.customColors.background, theme.customColors.text, theme.customColors.accent).map(ColorMath::toHex)))
            .put("f", theme.font.name)
            .put("b", JSONArray().put(theme.banner.style.name).put(theme.banner.matchTheme).put(ColorMath.toHex(theme.banner.color)).put(theme.banner.animated))
            .put(
                "g",
                JSONArray().put(theme.background.style.name).put(theme.background.matchTheme)
                    .put(ColorMath.toHex(theme.background.colorA)).put(ColorMath.toHex(theme.background.colorB))
                    // Two decimals is plenty, and keeps the code (and QR code) small.
                    .put(Math.round(theme.background.strength * 100) / 100.0)
            )
        return "$PREFIX$VERSION:$json"
    }

    /** The theme in [code], or null if it isn't a Cosmo theme code this version can read. */
    fun decode(code: String): SharedTheme? {
        val text = code.trim()
        if (!text.startsWith(PREFIX) || text.length > MAX_LENGTH) return null
        val versionAndBody = text.removePrefix(PREFIX)
        val separator = versionAndBody.indexOf(':')
        if (separator < 0 || versionAndBody.substring(0, separator).toIntOrNull() != VERSION) return null
        return try {
            val json = JSONObject(versionAndBody.substring(separator + 1))
            val defaults = AppSettings()
            val colors = json.getJSONArray("c")
            val banner = json.getJSONArray("b")
            val background = json.getJSONArray("g")
            SharedTheme(
                themeMode = enumOrNull<ThemeMode>(json.getString("m")) ?: return null,
                customColors = CustomColors(color(colors, 0), color(colors, 1), color(colors, 2)),
                // Unknown fonts (e.g. from a newer app) fall back to the default rather than failing.
                font = enumOrNull<AppFont>(json.optString("f")) ?: defaults.font,
                banner = BannerSettings(
                    style = enumOrNull<BannerStyle>(banner.getString(0)) ?: defaults.banner.style,
                    matchTheme = banner.getBoolean(1),
                    color = color(banner, 2),
                    animated = banner.getBoolean(3)
                ),
                background = BackgroundSettings(
                    style = enumOrNull<BackgroundStyle>(background.getString(0)) ?: defaults.background.style,
                    matchTheme = background.getBoolean(1),
                    colorA = color(background, 2),
                    colorB = color(background, 3),
                    strength = background.getDouble(4).toFloat().takeIf { it.isFinite() }?.coerceIn(0.1f, BackgroundSettings.MAX_STRENGTH)
                        ?: BackgroundSettings.DEFAULT_STRENGTH
                )
            )
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun color(array: JSONArray, index: Int): Int =
        ColorMath.parseHex(array.getString(index)) ?: throw IllegalArgumentException("Invalid color")

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }
}
