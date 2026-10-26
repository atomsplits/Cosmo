package com.rivers.cosmo.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.fonts.CustomFont
import com.rivers.cosmo.fonts.CustomFontStore
import com.rivers.cosmo.fonts.FontFormatException
import com.rivers.cosmo.settings.AppFont
import com.rivers.cosmo.settings.AppSettings
import com.rivers.cosmo.ui.theme.fontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FONT_COLUMNS = 3

/** File types offered in the picker; some file managers don't know WOFF2, so anything is allowed too. */
private val FONT_TYPES = arrayOf("font/woff2", "application/font-woff2", "font/ttf", "font/otf", "application/octet-stream", "*/*")

/** One tile in the grid: a built-in font, one the user added, or the "add font" tile. */
private sealed interface FontTile {
    data class BuiltIn(val font: AppFont) : FontTile
    data class Custom(val font: CustomFont) : FontTile
    data object Add : FontTile
}

/**
 * The app font, as a grid of tiles that each preview their font: the built-in ones, then fonts the
 * user added (removable), then a tile to add a .woff2 (or .ttf/.otf) font.
 */
@Composable
fun FontSection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    store: CustomFontStore,
    customFonts: List<CustomFont>,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CustomFont?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                val (bytes, name) = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val name = runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }.getOrNull()
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FontFormatException("Couldn't read that file.")
                    bytes to name
                }
                val font = store.import(bytes, name)
                onSettingsChange(settings.copy(customFont = font.id))
                onMessage("Added ${font.name}.")
            } catch (e: FontFormatException) {
                onMessage(e.message ?: "Couldn't add that font.")
            } catch (e: java.io.IOException) {
                onMessage("Couldn't read that file.")
            } catch (e: OutOfMemoryError) {
                onMessage("That font is too large.")
            }
            importing = false
        }
    }

    val tiles = AppFont.entries.map { FontTile.BuiltIn(it) } + customFonts.map { FontTile.Custom(it) } + FontTile.Add
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(FONT_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile ->
                    val modifier = Modifier.weight(1f)
                    when (tile) {
                        is FontTile.BuiltIn -> FontTileView(
                            label = tile.font.label,
                            family = tile.font.fontFamily,
                            selected = settings.customFont == null && settings.font == tile.font,
                            onClick = { onSettingsChange(settings.copy(font = tile.font, customFont = null)) },
                            modifier = modifier
                        )
                        is FontTile.Custom -> FontTileView(
                            label = tile.font.name,
                            family = tile.font.fontFamily,
                            selected = settings.customFont == tile.font.id,
                            onClick = { onSettingsChange(settings.copy(customFont = tile.font.id)) },
                            onRemove = { removing = tile.font },
                            modifier = modifier
                        )
                        FontTile.Add -> AddFontTile(importing, onClick = { picker.launch(FONT_TYPES) }, modifier = modifier)
                    }
                }
                // Keep tiles in a short last row the same width as the others.
                repeat(FONT_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(
            "Add any .woff2 web font (.ttf and .otf work too). Make sure its license allows using it in apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    removing?.let { font ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${font.name}?") },
            text = { Text("Widgets using it switch back to the app font.") },
            confirmButton = {
                TextButton(onClick = {
                    store.remove(font.id)
                    if (settings.customFont == font.id) onSettingsChange(settings.copy(customFont = null))
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
}

private val TILE_SHAPE = RoundedCornerShape(12.dp)

@Composable
private fun FontTileView(
    label: String,
    family: FontFamily,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    onRemove: (() -> Unit)? = null
) {
    Column(modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(TILE_SHAPE)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, TILE_SHAPE)
        ) {
            // Each tile previews its own font.
            Text("Aa", fontFamily = family, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.Center))
            onRemove?.let {
                IconButton(onClick = it, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove $label", modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(
            label,
            fontFamily = family,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun AddFontTile(importing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier.selectable(selected = false, enabled = !importing, role = Role.Button, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(TILE_SHAPE)
                .border(1.dp, MaterialTheme.colorScheme.outline, TILE_SHAPE),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
        Text(
            if (importing) "Adding…" else "Add .woff2",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
