package com.rivers.cosmo.settings

import android.content.Context
import com.rivers.cosmo.wallet.StellarNetwork

enum class ThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
    Amoled("AMOLED Black"),
    NothingRed("Nothing"),
    Paper("Paper"),
    Midnight("Midnight"),
    Stellar("Stellar"),
    Ocean("Ocean"),
    Forest("Forest"),
    Dracula("Dracula"),
    Solarized("Solarized"),
    Sunset("Sunset"),
    Rose("Rose"),
    Lavender("Lavender"),
    Custom("Custom")
}

enum class AppFont(val label: String) {
    Default("Default"),
    Inter("Inter (Helvetica-style)"),
    Arimo("Arimo (Helvetica-compatible)"),
    Montserrat("Montserrat"),
    Poppins("Poppins"),
    SpaceGrotesk("Space Grotesk"),
    Condensed("Condensed"),
    Serif("Serif"),
    Lora("Lora"),
    PlayfairDisplay("Playfair Display"),
    Monospace("Monospace"),
    JetBrainsMono("JetBrains Mono"),
    Doto("Doto (dot matrix)"),
    Casual("Casual"),
    Cursive("Cursive")
}

/** The animated banner at the top of the Wallet tab. */
enum class BannerStyle(val label: String) {
    Off("Off"),
    Gradient("Gradient"),
    Topographic("Topographic"),
    Clouds("Clouds"),
    Fire("Fire"),
    Aurora("Aurora"),
    Waves("Waves"),
    Stars("Stars")
}

data class BannerSettings(
    val style: BannerStyle = BannerStyle.Aurora,
    /** True to color the banner from the current theme's accent; false to use [color]. */
    val matchTheme: Boolean = true,
    val color: Int = 0xFF7C4DFF.toInt(),
    val animated: Boolean = true
)

/** The colors of the user's custom theme, as opaque ARGB ints. */
data class CustomColors(
    val background: Int = 0xFF101418.toInt(),
    val text: Int = 0xFFE8EAED.toInt(),
    val accent: Int = 0xFF4FC3F7.toInt()
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val font: AppFont = AppFont.Default,
    val customColors: CustomColors = CustomColors(),
    val chatServerUrl: String = DEFAULT_CHAT_SERVER_URL,
    // The test network until the user deliberately switches to real money.
    val stellarNetwork: StellarNetwork = StellarNetwork.Testnet,
    val banner: BannerSettings = BannerSettings()
)

/** The Android emulator's address for the computer running it, where server/ runs by default. */
const val DEFAULT_CHAT_SERVER_URL = "ws://10.0.2.2:8080/chat"

/** Saves the user's settings so they survive app restarts. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val defaults = CustomColors()
        return AppSettings(
            themeMode = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.System),
            font = enumOrDefault(prefs.getString(KEY_FONT, null), AppFont.Default),
            customColors = CustomColors(
                background = prefs.getInt(KEY_CUSTOM_BACKGROUND, defaults.background),
                text = prefs.getInt(KEY_CUSTOM_TEXT, defaults.text),
                accent = prefs.getInt(KEY_CUSTOM_ACCENT, defaults.accent)
            ),
            chatServerUrl = prefs.getString(KEY_CHAT_SERVER_URL, null) ?: DEFAULT_CHAT_SERVER_URL,
            stellarNetwork = enumOrDefault(prefs.getString(KEY_STELLAR_NETWORK, null), StellarNetwork.Testnet),
            banner = BannerSettings().let { default ->
                BannerSettings(
                    style = enumOrDefault(prefs.getString(KEY_BANNER_STYLE, null), default.style),
                    matchTheme = prefs.getBoolean(KEY_BANNER_MATCH_THEME, default.matchTheme),
                    color = prefs.getInt(KEY_BANNER_COLOR, default.color),
                    animated = prefs.getBoolean(KEY_BANNER_ANIMATED, default.animated)
                )
            }
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.themeMode.name)
            .putString(KEY_FONT, settings.font.name)
            .putInt(KEY_CUSTOM_BACKGROUND, settings.customColors.background)
            .putInt(KEY_CUSTOM_TEXT, settings.customColors.text)
            .putInt(KEY_CUSTOM_ACCENT, settings.customColors.accent)
            .putString(KEY_CHAT_SERVER_URL, settings.chatServerUrl)
            .putString(KEY_STELLAR_NETWORK, settings.stellarNetwork.name)
            .putString(KEY_BANNER_STYLE, settings.banner.style.name)
            .putBoolean(KEY_BANNER_MATCH_THEME, settings.banner.matchTheme)
            .putInt(KEY_BANNER_COLOR, settings.banner.color)
            .putBoolean(KEY_BANNER_ANIMATED, settings.banner.animated)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "cosmo_settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_FONT = "font"
        const val KEY_CUSTOM_BACKGROUND = "custom_background"
        const val KEY_CUSTOM_TEXT = "custom_text"
        const val KEY_CUSTOM_ACCENT = "custom_accent"
        const val KEY_CHAT_SERVER_URL = "chat_server_url"
        const val KEY_STELLAR_NETWORK = "stellar_network"
        const val KEY_BANNER_STYLE = "banner_style"
        const val KEY_BANNER_MATCH_THEME = "banner_match_theme"
        const val KEY_BANNER_COLOR = "banner_color"
        const val KEY_BANNER_ANIMATED = "banner_animated"

        inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
