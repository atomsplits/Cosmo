package com.rivers.cosmo.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.AppFont
import com.rivers.cosmo.settings.AppSettings
import com.rivers.cosmo.settings.ThemeMode
import com.rivers.cosmo.ui.components.FormColumn

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    FormColumn(modifier) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text("Theme", style = MaterialTheme.typography.titleMedium)
        OptionGroup(
            options = ThemeMode.entries,
            selected = settings.themeMode,
            label = { it.label },
            onSelected = { onSettingsChange(settings.copy(themeMode = it)) }
        )

        Text("Font", style = MaterialTheme.typography.titleMedium)
        OptionGroup(
            options = AppFont.entries,
            selected = settings.font,
            label = { it.label },
            // Each font option is drawn in its own font, so it previews itself.
            fontFamily = { it.fontFamily },
            onSelected = { onSettingsChange(settings.copy(font = it)) }
        )
    }
}

val AppFont.fontFamily: FontFamily
    get() = when (this) {
        AppFont.Default -> FontFamily.Default
        AppFont.Serif -> FontFamily.Serif
        AppFont.Monospace -> FontFamily.Monospace
    }

@Composable
private fun <T> OptionGroup(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    fontFamily: (T) -> FontFamily? = { null }
) {
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelected(option) })
                    .padding(vertical = 8.dp)
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(label(option), fontFamily = fontFamily(option))
            }
        }
    }
}
