package com.rivers.cosmo.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCodeTest {
    private val theme = SharedTheme(
        themeMode = ThemeMode.Custom,
        customColors = CustomColors(0xFF101418.toInt(), 0xFFE8EAED.toInt(), 0xFF4FC3F7.toInt()),
        font = AppFont.SpaceGrotesk,
        banner = BannerSettings(BannerStyle.Topographic, matchTheme = false, color = 0xFFD71921.toInt(), animated = false),
        background = BackgroundSettings(BackgroundStyle.PolkaDots, matchTheme = false, colorA = 0xFF7C3AED.toInt(), colorB = 0xFF00BFA5.toInt(), strength = 0.5f)
    )

    @Test
    fun roundTripsATheme() {
        val code = ThemeCode.encode(theme)
        assertTrue(code.startsWith("COSMO-THEME:1:"))
        assertTrue("short enough for an easy QR code: ${code.length}", code.length < 300)
        assertEquals(theme, ThemeCode.decode(code))
        assertEquals(theme, ThemeCode.decode("  $code\n")) // pasted with whitespace
    }

    @Test
    fun appliesOnlyTheLook() {
        val mine = AppSettings(customFont = "my-font", chatServerUrl = "wss://example.com/chat", stellarNetwork = com.rivers.cosmo.wallet.StellarNetwork.Public)
        val applied = theme.applyTo(mine)
        assertEquals(ThemeMode.Custom, applied.themeMode)
        assertEquals(AppFont.SpaceGrotesk, applied.font)
        assertNull(applied.customFont) // added fonts don't travel, so the shared built-in font is used
        assertEquals("wss://example.com/chat", applied.chatServerUrl)
        assertEquals(com.rivers.cosmo.wallet.StellarNetwork.Public, applied.stellarNetwork)
    }

    @Test
    fun refusesCodesThatArentThemes() {
        assertNull(ThemeCode.decode("cosmo://split?x=1"))
        assertNull(ThemeCode.decode("COSMO-THEME:2:{}")) // a future version
        assertNull(ThemeCode.decode("COSMO-THEME:1:not json"))
        assertNull(ThemeCode.decode("COSMO-THEME:1:{\"m\":\"Hacker\"}"))
        val code = ThemeCode.encode(theme)
        assertNull(ThemeCode.decode(code.replace("#101418", "red")))
        assertNull(ThemeCode.decode(code + " ".repeat(1000) + "x"))
    }

    @Test
    fun toleratesValuesFromNewerVersions() {
        val code = ThemeCode.encode(theme).replace("\"SpaceGrotesk\"", "\"FutureFont\"").replace("0.5", "9")
        val decoded = ThemeCode.decode(code)!!
        assertEquals(AppFont.Default, decoded.font)
        assertEquals(BackgroundSettings.MAX_STRENGTH, decoded.background.strength) // clamped
    }
}
