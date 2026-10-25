package com.rivers.cosmo.settings

import android.content.Context

enum class ThemeMode(val label: String) {
    System("System default"),
    Light("Light"),
    Dark("Dark")
}

enum class AppFont(val label: String) {
    Default("Default"),
    Serif("Serif"),
    Monospace("Monospace")
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val font: AppFont = AppFont.Default
)

/** Saves the user's appearance choices so they survive app restarts. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load() = AppSettings(
        themeMode = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.System),
        font = enumOrDefault(prefs.getString(KEY_FONT, null), AppFont.Default)
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.themeMode.name)
            .putString(KEY_FONT, settings.font.name)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "cosmo_settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_FONT = "font"

        inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
