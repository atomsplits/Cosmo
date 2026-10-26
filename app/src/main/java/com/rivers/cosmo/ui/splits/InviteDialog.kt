package com.rivers.cosmo.ui.splits

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code for [text], always black on white so any camera can read it whatever the theme. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            // A 4-module white border ("quiet zone") is what the QR standard asks for; scanners need it,
            // especially in dark theme where the dialog around the code is dark.
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        )
    }
    Canvas(modifier.aspectRatio(1f).background(Color.White)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                // Slightly oversized cells avoid hairline gaps between modules from rounding.
                if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Shows an invite as a QR code, with buttons to share or copy the link. */
@Composable
fun InviteDialog(title: String, description: String, link: String, onDismiss: () -> Unit, onCopied: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description, style = MaterialTheme.typography.bodySmall)
                QrCode(link, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text(link, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(link))
                    onCopied()
                }) { Text("Copy link") }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$description\n\n$link")
                    context.startActivity(Intent.createChooser(send, "Share invite"))
                }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
