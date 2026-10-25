package com.rivers.cosmo.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.fonts.CustomFont
import com.rivers.cosmo.fonts.CustomFontStore
import com.rivers.cosmo.fonts.FontFormatException
import com.rivers.cosmo.ui.theme.fontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File types offered in the picker; some file managers don't know WOFF2, so anything is allowed too. */
private val FONT_TYPES = arrayOf("font/woff2", "application/font-woff2", "font/ttf", "font/otf", "application/octet-stream", "*/*")

/** The user's own fonts: pick one for the app, add a .woff2 file, or remove one. */
@Composable
fun CustomFontsSection(
    store: CustomFontStore,
    fonts: List<CustomFont>,
    selectedId: String?,
    onSelect: (CustomFont) -> Unit,
    onRemoved: (CustomFont) -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
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
                onSelect(font)
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

    Column(Modifier.selectableGroup()) {
        fonts.forEach { font ->
            val selected = font.id == selectedId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(font) })
                    .padding(vertical = 8.dp)
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(font.name, fontFamily = font.fontFamily, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    store.remove(font.id)
                    onRemoved(font)
                }) { Text("Remove") }
            }
        }
        OutlinedButton(onClick = { picker.launch(FONT_TYPES) }, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
            Text(if (importing) "Adding font…" else "Add font (.woff2)")
        }
        Text(
            "Use any .woff2 web font (.ttf and .otf work too). Make sure its license allows using it in apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
