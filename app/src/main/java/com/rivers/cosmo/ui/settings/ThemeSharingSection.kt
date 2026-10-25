package com.rivers.cosmo.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rivers.cosmo.qr.QrImages
import com.rivers.cosmo.settings.AppSettings
import com.rivers.cosmo.settings.BackgroundStyle
import com.rivers.cosmo.settings.BannerStyle
import com.rivers.cosmo.settings.SharedTheme
import com.rivers.cosmo.settings.ThemeCode
import com.rivers.cosmo.ui.splits.QrCode
import com.rivers.cosmo.ui.theme.fontFamily
import com.rivers.cosmo.ui.theme.previewPalette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share the current theme as a QR code, or import one from a QR code or its text. */
@Composable
fun ThemeSharingSection(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<SharedTheme?>(null) }
    // The settings from before the last import, so it can be undone.
    var beforeImport by remember { mutableStateOf<AppSettings?>(null) }

    fun read(code: String?) {
        val theme = code?.let(ThemeCode::decode)
        if (theme == null) {
            onMessage(if (code == null) "No QR code found. Try again with the code filling more of the picture." else "That isn't a Cosmo theme.")
        } else {
            importing = false
            found = theme
        }
    }

    fun scan(uri: Uri) {
        scanning = true
        scope.launch {
            read(withContext(Dispatchers.IO) { runCatching { QrImages.decode(context, uri) }.getOrNull() })
            scanning = false
        }
    }

    val photoFile = remember { File(context.cacheDir, "scans/theme-qr.jpg") }
    val photoUri = remember { lazy { photoFile.parentFile?.mkdirs(); FileProvider.getUriForFile(context, "${context.packageName}.files", photoFile) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) scan(photoUri.value)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let(::scan) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { sharing = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Share theme")
        }
        OutlinedButton(onClick = { importing = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Import theme")
        }
    }
    beforeImport?.let { previous ->
        TextButton(onClick = {
            onSettingsChange(previous)
            beforeImport = null
        }) { Text("Undo theme import") }
    }

    if (sharing) {
        val code = remember(settings) { ThemeCode.encode(SharedTheme.from(settings)) }
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { sharing = false },
            title = { Text("Share this theme") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Scan it with Cosmo (Settings → Import theme) to get the same theme, colors, font, banner and background." +
                            if (settings.customFont != null) " Fonts you added aren't included." else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    QrCode(code, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Text(code, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(code))
                        onMessage("Theme code copied.")
                    }) { Text("Copy code") }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code)
                        context.startActivity(Intent.createChooser(send, "Share theme"))
                    }) { Text("Share") }
                }
            },
            dismissButton = { TextButton(onClick = { sharing = false }) { Text("Close") } }
        )
    }

    if (importing) {
        var pasted by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Import a theme") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            try {
                                camera.launch(photoUri.value)
                            } catch (e: ActivityNotFoundException) {
                                onMessage("There's no camera app to take the picture.")
                            }
                        },
                        enabled = !scanning,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (scanning) "Reading the code…" else "Scan with camera") }
                    OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                        Text("From a photo or screenshot")
                    }
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text("Or paste a theme code") },
                        placeholder = { Text("COSMO-THEME:1:…") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = { TextButton(onClick = { read(pasted) }, enabled = pasted.isNotBlank()) { Text("Use code") } },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } }
        )
    }

    found?.let { theme ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("Apply this theme?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemePreview(theme)
                    Text(
                        listOf(
                            "Theme: ${theme.themeMode.label}",
                            "Font: ${theme.font.label}",
                            "Banner: ${if (theme.banner.style == BannerStyle.Off) "off" else theme.banner.style.label}",
                            "Background: ${if (theme.background.style == BackgroundStyle.Plain) "plain" else theme.background.style.label}"
                        ).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("This replaces your current theme, font, banner and background. You can undo it.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    beforeImport = settings
                    onSettingsChange(theme.applyTo(settings))
                    found = null
                    onMessage("Theme applied.")
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text("Cancel") } }
        )
    }
}

/** What an imported theme looks like: its background, text in its font, and its accent. */
@Composable
private fun ThemePreview(theme: SharedTheme) {
    val palette = theme.themeMode.previewPalette(theme.customColors, isSystemInDarkTheme())
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(shape)
            .background(palette.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(12.dp)
    ) {
        Text(
            "Cosmo",
            color = palette.text,
            fontFamily = theme.font.fontFamily,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Box(Modifier.size(20.dp).align(Alignment.BottomEnd).background(palette.accent, CircleShape))
    }
}
