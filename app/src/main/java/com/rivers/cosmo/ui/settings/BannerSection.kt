package com.rivers.cosmo.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.BannerSettings
import com.rivers.cosmo.settings.BannerStyle
import com.rivers.cosmo.ui.banner.AnimatedBanner
import com.rivers.cosmo.ui.banner.textColorOnBanner
import com.rivers.cosmo.ui.components.ColorPickerDialog
import com.rivers.cosmo.ui.components.ColorSwatch

private const val STYLE_COLUMNS = 2

/** Picks the Wallet tab banner's style and color. */
@Composable
fun BannerSection(banner: BannerSettings, onChange: (BannerSettings) -> Unit) {
    var pickingColor by rememberSaveable { mutableStateOf(false) }
    val baseColor = if (banner.matchTheme) MaterialTheme.colorScheme.primary else Color(banner.color)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BannerStyle.entries.chunked(STYLE_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { style ->
                        StyleTile(style, baseColor, selected = style == banner.style, onClick = { onChange(banner.copy(style = style)) }, modifier = Modifier.weight(1f))
                    }
                    repeat(STYLE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        if (banner.style != BannerStyle.Off) {
            Column(Modifier.selectableGroup()) {
                ColorChoice("Match theme", selected = banner.matchTheme, onClick = { onChange(banner.copy(matchTheme = true)) })
                ColorChoice("Custom color", selected = !banner.matchTheme, onClick = { onChange(banner.copy(matchTheme = false)) }) {
                    ColorSwatch(
                        banner.color,
                        Modifier
                            .size(32.dp)
                            .clickable(onClickLabel = "Change banner color") { pickingColor = true }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Animate", modifier = Modifier.weight(1f))
                Switch(checked = banner.animated, onCheckedChange = { onChange(banner.copy(animated = it)) })
            }
        }
    }

    if (pickingColor) {
        ColorPickerDialog(
            title = "Banner color",
            initialColor = banner.color,
            onDismiss = { pickingColor = false },
            onConfirm = { argb ->
                pickingColor = false
                onChange(banner.copy(color = argb, matchTheme = false))
            }
        )
    }
}

/** A still preview of one banner style, labelled with its name. */
@Composable
private fun StyleTile(style: BannerStyle, baseColor: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val tileModifier = modifier
        .height(64.dp)
        .clip(shape)
        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    if (style == BannerStyle.Off) {
        Column(tileModifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Off", style = MaterialTheme.typography.labelLarge)
        }
        return
    }
    AnimatedBanner(style = style, baseColor = baseColor, animated = false, modifier = tileModifier) {
        Text(
            style.label,
            style = MaterialTheme.typography.labelLarge,
            color = Color(textColorOnBanner(style, baseColor.toArgb())),
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
        )
    }
}

@Composable
private fun ColorChoice(label: String, selected: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
        trailing()
    }
}
