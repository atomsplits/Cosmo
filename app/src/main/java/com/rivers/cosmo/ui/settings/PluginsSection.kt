package com.rivers.cosmo.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.plugins.PluginEngine
import com.rivers.cosmo.plugins.PluginHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingScript(val name: String, val source: String)

/** An example plugin bundled with the app (docs/plugins/examples, copied into assets at build time). */
private data class ExamplePlugin(val name: String, val file: String, val description: String)

private val EXAMPLES = listOf(
    ExamplePlugin("XLM price", "xlm-price.js", "The live XLM price, its 24-hour chart, what your balance is worth, and price alerts."),
    ExamplePlugin("Cosmo pet", "cosmo-pet.js", "Nova, a little moon to feed, play with and put to bed. It cheers up when you get paid.")
)

/** Lets the user import, enable, disable and remove JavaScript plugins. */
@Composable
fun PluginsSection(
    plugins: List<PluginHost.PluginState>,
    onInstall: suspend (name: String, source: String) -> Result<Unit>,
    onEnabledChange: (id: String, enabled: Boolean) -> Unit,
    onRemove: (id: String) -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingScript?>(null) }
    var pasting by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<PluginHost.PluginState?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val script = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "plugin.js"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    if (bytes.size > PluginEngine.MAX_SCRIPT_CHARS) null else PendingScript(name.removeSuffix(".js"), bytes.toString(Charsets.UTF_8))
                }.getOrNull()
            }
            if (script == null) onMessage("Couldn't read that file. Scripts must be text and under 100 KB.") else pending = script
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Plugins are JavaScript files that add widgets to your Wallet tab. They can read your balance, " +
                "wallet ID and payment history, but they can't send money, see your recovery phrase or keys, " +
                "or use the internet.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { choosing = true }, enabled = !installing) { Text("Import script") }
            OutlinedButton(onClick = { pasting = true }, enabled = !installing) { Text("Paste script") }
        }
        plugins.forEach { state ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.plugin.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(checked = state.plugin.enabled, onCheckedChange = { onEnabledChange(state.plugin.id, it) })
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { removing = state }) { Text("Remove") }
                }
            }
        }
    }

    if (choosing) {
        ImportScriptDialog(
            installed = plugins.map { it.plugin.name }.toSet(),
            installing = installing,
            onAddExample = { example ->
                installing = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            context.assets.open("plugin-examples/${example.file}").use { it.readBytes().toString(Charsets.UTF_8) }
                        }
                    }.mapCatching { source -> onInstall(example.name, source).getOrThrow() }
                        .onSuccess { onMessage("Added ${example.name}. Its widget is on your Wallet tab.") }
                        .onFailure { onMessage("Couldn't add ${example.name}: ${it.message}") }
                    installing = false
                }
            },
            onOpenFile = {
                choosing = false
                picker.launch(arrayOf("*/*"))
            },
            onDismiss = { choosing = false }
        )
    }

    if (pasting) {
        PasteScriptDialog(
            onDismiss = { pasting = false },
            onContinue = { name, source ->
                pasting = false
                pending = PendingScript(name, source)
            }
        )
    }

    pending?.let { script ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Install \"${script.name}\"?") },
            text = {
                Text(
                    "Only install scripts you trust. This plugin will be able to read your balance, wallet ID " +
                        "and payment history, and suggest payments that you'd still have to confirm.\n\n" +
                        "${script.source.lines().size} lines · ${"%.1f".format(script.source.length / 1000.0)} KB"
                )
            },
            confirmButton = {
                TextButton(enabled = !installing, onClick = {
                    installing = true
                    scope.launch {
                        onInstall(script.name, script.source)
                            .onSuccess { onMessage("Installed ${script.name}. Its widgets are on your Wallet tab.") }
                            .onFailure { onMessage("Couldn't install ${script.name}: ${it.message}") }
                        installing = false
                        pending = null
                    }
                }) { Text(if (installing) "Installing…" else "Install") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } }
        )
    }

    removing?.let { state ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove \"${state.plugin.name}\"?") },
            text = { Text("Its widgets and saved data will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(state.plugin.id)
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
}

/** Import script: one-tap example widgets, or a .js file from the device. */
@Composable
private fun ImportScriptDialog(
    installed: Set<String>,
    installing: Boolean,
    onAddExample: (ExamplePlugin) -> Unit,
    onOpenFile: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import script") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Examples", style = MaterialTheme.typography.labelLarge)
                EXAMPLES.forEach { example ->
                    val added = example.name in installed
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(example.name, style = MaterialTheme.typography.titleSmall)
                                Text(example.description, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.width(8.dp))
                            if (added) {
                                Text("Added", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                FilledTonalButton(onClick = { onAddExample(example) }, enabled = !installing) { Text("Add") }
                            }
                        }
                    }
                }
                Text(
                    "Or open a .js file from your device. Only install scripts from people you trust.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onOpenFile, enabled = !installing) { Text("Open file…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun PasteScriptDialog(onDismiss: () -> Unit, onContinue: (name: String, source: String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste script") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it.take(PluginEngine.MAX_SCRIPT_CHARS) },
                    label = { Text("JavaScript") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onContinue(name.trim(), source) }, enabled = name.isNotBlank() && source.isNotBlank()) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
