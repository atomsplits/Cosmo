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

/** The pattern drawn behind every screen. */
enum class BackgroundStyle(val label: String) {
    Plain("Plain"),
    Gradient("Gradient"),
    Fade("Fade"),
    PolkaDots("Polka dots"),
    Glow("Glow"),
    Mesh("Mesh"),
    Stripes("Stripes")
}

data class BackgroundSettings(
    val style: BackgroundStyle = BackgroundStyle.Plain,
    /** True to take the colors from the current theme; false to use [colorA] and [colorB]. */
    val matchTheme: Boolean = true,
    val colorA: Int = 0xFF7C4DFF.toInt(),
    val colorB: Int = 0xFF00BFA5.toInt(),
    /** How strongly the colors show over the theme's background, 0–1. Kept moderate so text stays readable. */
    val strength: Float = DEFAULT_STRENGTH
) {
    companion object {
        const val DEFAULT_STRENGTH = 0.35f
        const val MAX_STRENGTH = 0.7f
    }
}

/** How elements appear: fading in while sliding from a direction, zooming, or not at all. */
enum class EntranceStyle(val label: String) {
    FadeUp("Fade up"),
    FadeDown("Fade down"),
    FadeLeft("Fade left"),
    FadeRight("Fade right"),
    Fade("Fade"),
    Zoom("Zoom"),
    None("None")
}

enum class MotionSpeed(val label: String, val factor: Float) {
    Slow("Slow", 1.6f),
    Normal("Normal", 1f),
    Fast("Fast", 0.6f)
}

data class MotionSettings(
    /** Master switch for all of the app's own animations. */
    val enabled: Boolean = true,
    val entrance: EntranceStyle = EntranceStyle.FadeUp,
    val speed: MotionSpeed = MotionSpeed.Normal
)

/**
 * The anchors (businesses that connect Stellar to banks) the user works with, by domain, for each
 * network. They're used for cashing out, identity verification and finding tokens to trade.
 */
data class AnchorSettings(
    val testnet: List<String> = DEFAULT_TESTNET,
    val public: List<String> = emptyList()
) {
    fun forNetwork(network: StellarNetwork): List<String> = if (network == StellarNetwork.Public) public else testnet

    fun with(network: StellarNetwork, domains: List<String>): AnchorSettings =
        if (network == StellarNetwork.Public) copy(public = domains) else copy(testnet = domains)

    companion object {
        /** Stellar's own reference anchor: fake money, but every SEP works against it. */
        val DEFAULT_TESTNET = listOf("testanchor.stellar.org")
    }
}

/** The colors of the user's custom theme, as opaque ARGB ints. */
data class CustomColors(
    val background: Int = 0xFF101418.toInt(),
    val text: Int = 0xFFE8EAED.toInt(),
    val accent: Int = 0xFF4FC3F7.toInt()
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val font: AppFont = AppFont.Default,
    /** A font the user added, by id; used instead of [font] when set. */
    val customFont: String? = null,
    val customColors: CustomColors = CustomColors(),
    val chatServerUrl: String = DEFAULT_CHAT_SERVER_URL,
    // The test network until the user deliberately switches to real money.
    val stellarNetwork: StellarNetwork = StellarNetwork.Testnet,
    val banner: BannerSettings = BannerSettings(),
    val background: BackgroundSettings = BackgroundSettings(),
    val motion: MotionSettings = MotionSettings(),
    val anchors: AnchorSettings = AnchorSettings(),
    /**
     * Whether screenshots and screen recordings may show the recovery phrase screens (sign up and
     * log in). Off protects the phrase from screen-capturing apps; [SettingsStore] turns it on by
     * default only in debug builds, for recording demos and bug reports.
     */
    val allowScreenCapture: Boolean = false
)

/** The Android emulator's address for the computer running it, where server/ runs by default. */
const val DEFAULT_CHAT_SERVER_URL = "ws://10.0.2.2:8080/chat"

/** A phone connected by USB, after `adb reverse tcp:8080 tcp:8080` forwards its port 8080 to the computer. */
const val USB_CHAT_SERVER_URL = "ws://localhost:8080/chat"

/** Saves the user's settings so they survive app restarts. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val debugBuild = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun load(): AppSettings {
        val defaults = CustomColors()
        return AppSettings(
            themeMode = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.System),
            font = enumOrDefault(prefs.getString(KEY_FONT, null), AppFont.Default),
            customFont = prefs.getString(KEY_CUSTOM_FONT, null),
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
            },
            background = BackgroundSettings().let { default ->
                BackgroundSettings(
                    style = enumOrDefault(prefs.getString(KEY_BACKGROUND_STYLE, null), default.style),
                    matchTheme = prefs.getBoolean(KEY_BACKGROUND_MATCH_THEME, default.matchTheme),
                    colorA = prefs.getInt(KEY_BACKGROUND_COLOR_A, default.colorA),
                    colorB = prefs.getInt(KEY_BACKGROUND_COLOR_B, default.colorB),
                    strength = prefs.getFloat(KEY_BACKGROUND_STRENGTH, default.strength)
                )
            },
            motion = MotionSettings().let { default ->
                MotionSettings(
                    enabled = prefs.getBoolean(KEY_MOTION_ENABLED, default.enabled),
                    entrance = enumOrDefault(prefs.getString(KEY_MOTION_ENTRANCE, null), default.entrance),
                    speed = enumOrDefault(prefs.getString(KEY_MOTION_SPEED, null), default.speed)
                )
            },
            allowScreenCapture = prefs.getBoolean(KEY_ALLOW_SCREEN_CAPTURE, debugBuild),
            anchors = AnchorSettings(
                testnet = prefs.getString(KEY_ANCHORS_TESTNET, null)?.let(::domainList) ?: AnchorSettings.DEFAULT_TESTNET,
                public = prefs.getString(KEY_ANCHORS_PUBLIC, null)?.let(::domainList) ?: emptyList()
            )
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.themeMode.name)
            .putString(KEY_FONT, settings.font.name)
            .putString(KEY_CUSTOM_FONT, settings.customFont)
            .putInt(KEY_CUSTOM_BACKGROUND, settings.customColors.background)
            .putInt(KEY_CUSTOM_TEXT, settings.customColors.text)
            .putInt(KEY_CUSTOM_ACCENT, settings.customColors.accent)
            .putString(KEY_CHAT_SERVER_URL, settings.chatServerUrl)
            .putString(KEY_STELLAR_NETWORK, settings.stellarNetwork.name)
            .putString(KEY_BANNER_STYLE, settings.banner.style.name)
            .putBoolean(KEY_BANNER_MATCH_THEME, settings.banner.matchTheme)
            .putInt(KEY_BANNER_COLOR, settings.banner.color)
            .putBoolean(KEY_BANNER_ANIMATED, settings.banner.animated)
            .putString(KEY_BACKGROUND_STYLE, settings.background.style.name)
            .putBoolean(KEY_BACKGROUND_MATCH_THEME, settings.background.matchTheme)
            .putInt(KEY_BACKGROUND_COLOR_A, settings.background.colorA)
            .putInt(KEY_BACKGROUND_COLOR_B, settings.background.colorB)
            .putFloat(KEY_BACKGROUND_STRENGTH, settings.background.strength)
            .putBoolean(KEY_MOTION_ENABLED, settings.motion.enabled)
            .putString(KEY_MOTION_ENTRANCE, settings.motion.entrance.name)
            .putString(KEY_MOTION_SPEED, settings.motion.speed.name)
            .putBoolean(KEY_ALLOW_SCREEN_CAPTURE, settings.allowScreenCapture)
            .putString(KEY_ANCHORS_TESTNET, settings.anchors.testnet.joinToString("\n"))
            .putString(KEY_ANCHORS_PUBLIC, settings.anchors.public.joinToString("\n"))
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "cosmo_settings"
        const val KEY_ALLOW_SCREEN_CAPTURE = "allow_screen_capture"
        const val KEY_ANCHORS_TESTNET = "anchors_testnet"
        const val KEY_ANCHORS_PUBLIC = "anchors_public"
        const val KEY_THEME = "theme_mode"
        const val KEY_FONT = "font"
        const val KEY_CUSTOM_FONT = "custom_font"
        const val KEY_CUSTOM_BACKGROUND = "custom_background"
        const val KEY_CUSTOM_TEXT = "custom_text"
        const val KEY_CUSTOM_ACCENT = "custom_accent"
        const val KEY_CHAT_SERVER_URL = "chat_server_url"
        const val KEY_STELLAR_NETWORK = "stellar_network"
        const val KEY_BANNER_STYLE = "banner_style"
        const val KEY_BANNER_MATCH_THEME = "banner_match_theme"
        const val KEY_BANNER_COLOR = "banner_color"
        const val KEY_BANNER_ANIMATED = "banner_animated"
        const val KEY_BACKGROUND_STYLE = "background_style"
        const val KEY_BACKGROUND_MATCH_THEME = "background_match_theme"
        const val KEY_BACKGROUND_COLOR_A = "background_color_a"
        const val KEY_BACKGROUND_COLOR_B = "background_color_b"
        const val KEY_BACKGROUND_STRENGTH = "background_strength"
        const val KEY_MOTION_ENABLED = "motion_enabled"
        const val KEY_MOTION_ENTRANCE = "motion_entrance"
        const val KEY_MOTION_SPEED = "motion_speed"

        inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default

        fun domainList(stored: String): List<String> = stored.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
