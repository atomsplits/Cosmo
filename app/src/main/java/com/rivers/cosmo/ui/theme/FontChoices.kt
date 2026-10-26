package com.rivers.cosmo.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import com.rivers.cosmo.fonts.CustomFont
import com.rivers.cosmo.fonts.FontKeys
import com.rivers.cosmo.settings.AppFont

/** The fonts the user added, for pickers and for drawing text in them. */
val LocalCustomFonts = staticCompositionLocalOf<List<CustomFont>> { emptyList() }

/** The font family for a stored font choice (see [FontKeys]), or null if it no longer exists. */
fun fontFamilyFor(key: String, customFonts: List<CustomFont>): FontFamily? {
    val customId = FontKeys.customId(key)
    if (customId != null) return customFonts.firstOrNull { it.id == customId }?.fontFamily
    return AppFont.entries.firstOrNull { it.name == key }?.fontFamily
}
