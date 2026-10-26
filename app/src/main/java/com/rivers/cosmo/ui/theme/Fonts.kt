package com.rivers.cosmo.ui.theme

import android.graphics.Typeface as AndroidTypeface
import androidx.annotation.FontRes
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import com.rivers.cosmo.R
import com.rivers.cosmo.fonts.CustomFont
import com.rivers.cosmo.settings.AppFont

// Bundled fonts are open source (SIL Open Font License, see assets/font_licenses). Helvetica itself
// is a paid font that apps can't bundle, so Inter and Arimo stand in for it.
private val families = mutableMapOf<AppFont, FontFamily>()

val AppFont.fontFamily: FontFamily
    get() = families.getOrPut(this) {
        when (this) {
            AppFont.Default -> FontFamily.Default
            AppFont.Serif -> FontFamily.Serif
            AppFont.Monospace -> FontFamily.Monospace
            AppFont.Cursive -> FontFamily.Cursive
            AppFont.Condensed -> systemFamily("sans-serif-condensed")
            AppFont.Casual -> systemFamily("casual")
            AppFont.Inter -> variableFamily(R.font.inter)
            AppFont.Arimo -> variableFamily(R.font.arimo)
            AppFont.Montserrat -> variableFamily(R.font.montserrat)
            AppFont.SpaceGrotesk -> variableFamily(R.font.space_grotesk)
            AppFont.Lora -> variableFamily(R.font.lora)
            AppFont.PlayfairDisplay -> variableFamily(R.font.playfair_display)
            AppFont.JetBrainsMono -> variableFamily(R.font.jetbrains_mono)
            AppFont.Doto -> variableFamily(R.font.doto)
            AppFont.Poppins -> FontFamily(
                Font(R.font.poppins_regular, FontWeight.Normal),
                Font(R.font.poppins_bold, FontWeight.Bold)
            )
        }
    }

private val customFamilies = mutableMapOf<String, FontFamily>()

/** A font the user added, loaded from the TrueType file made when they imported it. */
val CustomFont.fontFamily: FontFamily
    get() = customFamilies.getOrPut(file.path) { FontFamily(Typeface(AndroidTypeface.createFromFile(file))) }

/** A font that ships with Android, looked up by its system name. */
private fun systemFamily(name: String) = FontFamily(Typeface(AndroidTypeface.create(name, AndroidTypeface.NORMAL)))

/** One variable font file used at the weights the app's text styles need. */
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(@FontRes font: Int) = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.Bold).map { weight ->
        Font(font, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    }
)
