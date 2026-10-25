package com.rivers.cosmo.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.BackgroundSettings
import com.rivers.cosmo.settings.BackgroundStyle
import com.rivers.cosmo.ui.background.BackgroundCanvas
import com.rivers.cosmo.ui.background.backgroundColors
import com.rivers.cosmo.ui.components.ColorPickerDialog
import com.rivers.cosmo.ui.components.ColorSwatch
import kotlin.math.roundToInt

private const val COLUMNS = 3

private enum class PickingColor { A, B }

/** Picks the app-wide background: style, colors (theme or custom) and how strongly it shows. */
@Composable
fun BackgroundSection(background: BackgroundSettings, onChange: (BackgroundSettings) -> Unit) {
    var picking by rememberSaveable { mutableStateOf<PickingColor?>(null) }
    val scheme = MaterialTheme.colorScheme
    val (colorA, colorB) = backgroundColors(background, scheme)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BackgroundStyle.entries.chunked(COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { style ->
                        val selected = style == background.style
                        val shape = RoundedCornerShape(12.dp)
                        Column(
                            Modifier
                                .weight(1f)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { onChange(background.copy(style = style)) }),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(72.dp)
                                    .clip(shape)
                                    .border(if (selected) 3.dp else 1.dp, if (selected) scheme.primary else scheme.outline, shape)
                            ) {
                                BackgroundCanvas(style, scheme.background, scheme.onBackground, colorA, colorB, background.strength, Modifier.matchParentSize())
                            }
                            Text(style.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        if (background.style != BackgroundStyle.Plain) {
            Column(Modifier.selectableGroup()) {
                ColorModeRow("Match theme", selected = background.matchTheme, onClick = { onChange(background.copy(matchTheme = true)) })
                ColorModeRow("Custom colors", selected = !background.matchTheme, onClick = { onChange(background.copy(matchTheme = false)) }) {
                    ColorSwatch(background.colorA, Modifier.size(32.dp).clickable(onClickLabel = "Change first background color") { picking = PickingColor.A })
                    Spacer(Modifier.width(8.dp))
                    ColorSwatch(background.colorB, Modifier.size(32.dp).clickable(onClickLabel = "Change second background color") { picking = PickingColor.B })
                }
            }
            Column {
                Text("Strength: ${(background.strength * 100).roundToInt()}%")
                Slider(
                    value = background.strength,
                    onValueChange = { onChange(background.copy(strength = it)) },
                    valueRange = 0.1f..BackgroundSettings.MAX_STRENGTH
                )
                Text(
                    "Colors that would make text hard to read are toned down automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    picking?.let { which ->
        ColorPickerDialog(
            title = if (which == PickingColor.A) "First color" else "Second color",
            initialColor = if (which == PickingColor.A) background.colorA else background.colorB,
            onDismiss = { picking = null },
            onConfirm = { argb ->
                picking = null
                onChange(
                    if (which == PickingColor.A) background.copy(colorA = argb, matchTheme = false)
                    else background.copy(colorB = argb, matchTheme = false)
                )
            }
        )
    }
}

@Composable
private fun ColorModeRow(label: String, selected: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
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
