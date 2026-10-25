package com.rivers.cosmo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.AppFont
import com.rivers.cosmo.settings.AppSettings
import com.rivers.cosmo.settings.ColorMath
import com.rivers.cosmo.settings.CustomColors
import com.rivers.cosmo.settings.DEFAULT_CHAT_SERVER_URL
import com.rivers.cosmo.settings.ThemeMode
import com.rivers.cosmo.ui.components.ColorPickerDialog
import com.rivers.cosmo.ui.components.ColorSwatch
import com.rivers.cosmo.ui.components.FormColumn
import com.rivers.cosmo.ui.theme.Palette
import com.rivers.cosmo.ui.theme.fontFamily
import com.rivers.cosmo.ui.theme.previewPalette

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    FormColumn(modifier) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text("Theme", style = MaterialTheme.typography.titleMedium)
        ThemeGrid(
            selected = settings.themeMode,
            customColors = settings.customColors,
            onSelected = { onSettingsChange(settings.copy(themeMode = it)) }
        )
        if (settings.themeMode == ThemeMode.Custom) {
            CustomThemeEditor(
                colors = settings.customColors,
                onChange = { onSettingsChange(settings.copy(customColors = it)) }
            )
        }

        Text("Chat server", style = MaterialTheme.typography.titleMedium)
        ChatServerField(
            url = settings.chatServerUrl,
            onSave = { onSettingsChange(settings.copy(chatServerUrl = it)) }
        )

        Text("Font", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            AppFont.entries.forEach { font ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = font == settings.font,
                            role = Role.RadioButton,
                            onClick = { onSettingsChange(settings.copy(font = font)) }
                        )
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(selected = font == settings.font, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    // Each font option is drawn in its own font, so it previews itself.
                    Text(font.label, fontFamily = font.fontFamily)
                }
            }
        }
    }
}

private const val THEME_COLUMNS = 3

@Composable
private fun ThemeGrid(selected: ThemeMode, customColors: CustomColors, onSelected: (ThemeMode) -> Unit) {
    val systemDark = isSystemInDarkTheme()
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ThemeMode.entries.chunked(THEME_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { mode ->
                    ThemeTile(
                        label = mode.label,
                        palette = mode.previewPalette(customColors, systemDark),
                        selected = mode == selected,
                        onClick = { onSelected(mode) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Keep tiles in a short last row the same width as the others.
                repeat(THEME_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A small preview of a theme: its background, a sample of its text, and a dot of its accent color. */
@Composable
private fun ThemeTile(label: String, palette: Palette, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(shape)
                .background(palette.background)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = shape
                )
                .padding(10.dp)
        ) {
            Text(
                "Aa",
                color = palette.text,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopStart)
            )
            Box(
                Modifier
                    .size(16.dp)
                    .align(Alignment.BottomEnd)
                    .background(palette.accent, CircleShape)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private enum class CustomColorSlot(val label: String) {
    BackgroundColor("Background"),
    TextColor("Text"),
    AccentColor("Accent (buttons and highlights)");

    fun get(colors: CustomColors): Int = when (this) {
        BackgroundColor -> colors.background
        TextColor -> colors.text
        AccentColor -> colors.accent
    }

    fun set(colors: CustomColors, argb: Int): CustomColors = when (this) {
        BackgroundColor -> colors.copy(background = argb)
        TextColor -> colors.copy(text = argb)
        AccentColor -> colors.copy(accent = argb)
    }
}

@Composable
private fun CustomThemeEditor(colors: CustomColors, onChange: (CustomColors) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<CustomColorSlot?>(null) }
    val contrast = ColorMath.contrastRatio(colors.text, colors.background)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Custom colors", style = MaterialTheme.typography.titleSmall)
            Text("Tap a color to change it on the color wheel.", style = MaterialTheme.typography.bodySmall)
            CustomColorSlot.entries.forEach { slot ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Change ${slot.label} color") { editing = slot }
                        .padding(vertical = 8.dp)
                ) {
                    ColorSwatch(slot.get(colors), Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(slot.label)
                        Text(
                            ColorMath.toHex(slot.get(colors)),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            if (contrast < ColorMath.MIN_READABLE_CONTRAST) {
                Text(
                    "Text may be hard to read on this background (contrast %.1f:1, aim for 4.5:1 or more).".format(contrast),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { onChange(CustomColors()) }) { Text("Reset to default colors") }
        }
    }

    editing?.let { slot ->
        ColorPickerDialog(
            title = slot.label,
            initialColor = slot.get(colors),
            onDismiss = { editing = null },
            onConfirm = { argb ->
                onChange(slot.set(colors, argb))
                editing = null
            }
        )
    }
}

@Composable
private fun ChatServerField(url: String, onSave: (String) -> Unit) {
    var draft by rememberSaveable(url) { mutableStateOf(url) }
    val trimmed = draft.trim()
    val valid = trimmed.startsWith("ws://") || trimmed.startsWith("wss://")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Server address") },
            singleLine = true,
            isError = !valid,
            supportingText = {
                Text(
                    if (valid) {
                        "On the emulator use $DEFAULT_CHAT_SERVER_URL. On your phone, use your " +
                            "computer's Wi-Fi address, e.g. ws://192.168.1.20:8080/chat."
                    } else {
                        "The address must start with ws:// or wss://"
                    }
                )
            },
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(trimmed) }, enabled = valid && trimmed != url) { Text("Save and reconnect") }
            TextButton(onClick = { draft = DEFAULT_CHAT_SERVER_URL }) { Text("Reset") }
        }
    }
}
