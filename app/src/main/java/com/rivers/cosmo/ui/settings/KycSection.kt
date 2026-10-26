package com.rivers.cosmo.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.anchor.Sep12
import com.rivers.cosmo.anchor.Upload
import com.rivers.cosmo.kyc.KycController
import com.rivers.cosmo.kyc.KycStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_FILE_BYTES = 10 * 1024 * 1024

/**
 * Identity verification with an anchor through Stellar's KYC API (SEP-12): shows the status and,
 * when the anchor needs details, a form built from exactly what it asks for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KycSection(
    state: KycController.State,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onSubmit: suspend (fields: Map<String, String>, files: Map<String, Upload>) -> Result<Unit>,
    onMessage: (String) -> Unit
) {
    var formOpen by rememberSaveable { mutableStateOf(false) }
    val customer = state.customer
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val domain = state.domain
            if (domain == null) {
                Text("Add an anchor in Settings → Anchors to verify your identity with it.", style = MaterialTheme.typography.bodySmall)
            } else {
                if (state.domains.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.domains.forEach { option ->
                            FilterChip(selected = option == domain, onClick = { onSelect(option) }, label = { Text(option) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        statusLabel(state.status, state.loading),
                        style = MaterialTheme.typography.titleSmall,
                        color = when (state.status) {
                            KycStatus.ACCEPTED -> MaterialTheme.colorScheme.primary
                            KycStatus.REJECTED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh") }
                }
                Text(customer?.message ?: statusDetail(state.status, domain), style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                if (customer != null && customer.needed.isNotEmpty() && customer.status != KycStatus.REJECTED) {
                    if (formOpen) {
                        KycForm(
                            domain = domain,
                            fields = customer.needed,
                            submitting = state.submitting,
                            onSubmit = { fields, files ->
                                onSubmit(fields, files).onSuccess {
                                    formOpen = false
                                    onMessage("Sent to $domain.")
                                }
                            },
                            onMessage = onMessage,
                            onCancel = { formOpen = false }
                        )
                    } else {
                        Button(onClick = { formOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (customer.provided.isEmpty()) "Verify identity" else "Provide the rest")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KycForm(
    domain: String,
    fields: List<Sep12.Field>,
    submitting: Boolean,
    onSubmit: suspend (Map<String, String>, Map<String, Upload>) -> Unit,
    onMessage: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Kept in memory only: these are personal details, so they aren't saved to disk.
    val values = remember { mutableStateMapOf<String, String>() }
    val files = remember { mutableStateMapOf<String, Uri>() }
    var picking by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val field = picking
        if (uri != null && field != null) files[field] = uri
        picking = null
    }
    val missing = fields.filter { !it.optional && (if (it.isFile) it.name !in files else values[it.name].isNullOrBlank()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEach { field ->
            val supporting = field.error?.let { "Rejected: $it" } ?: field.description.takeIf { it != field.name }
            when {
                field.isFile -> Column {
                    Text(field.label + if (field.optional) " (optional)" else "", style = MaterialTheme.typography.labelLarge)
                    supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (field.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    OutlinedButton(onClick = {
                        picking = field.name
                        picker.launch("image/*")
                    }) { Text(files[field.name]?.let { "✓ ${displayName(context, it)}" } ?: "Choose photo") }
                }
                field.choices.isNotEmpty() -> Column {
                    Text(field.label, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        field.choices.forEach { choice ->
                            FilterChip(selected = values[field.name] == choice, onClick = { values[field.name] = choice }, label = { Text(choice) })
                        }
                    }
                }
                else -> OutlinedTextField(
                    value = values[field.name].orEmpty(),
                    onValueChange = { values[field.name] = it },
                    label = { Text(field.label + if (field.optional) " (optional)" else "") },
                    placeholder = if (field.type == "date") ({ Text("YYYY-MM-DD") }) else null,
                    supportingText = supporting?.let { { Text(it) } },
                    isError = field.error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (field.type) {
                            "number" -> KeyboardType.Decimal
                            "date" -> KeyboardType.Number
                            else -> if ("email" in field.name) KeyboardType.Email else if ("phone" in field.name) KeyboardType.Phone else KeyboardType.Text
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Text(
            "Your details go straight to $domain over an encrypted connection. Cosmo doesn't keep a copy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, enabled = !submitting) { Text("Cancel") }
            Button(
                onClick = {
                    scope.launch {
                        val uploads = try {
                            withContext(Dispatchers.IO) { files.mapValues { (name, uri) -> readUpload(context, name, uri) } }
                        } catch (e: IllegalArgumentException) {
                            onMessage(e.message ?: "Couldn't read that photo.")
                            return@launch
                        }
                        onSubmit(values.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }, uploads)
                    }
                },
                enabled = missing.isEmpty() && !submitting,
                modifier = Modifier.weight(1f)
            ) { Text(if (submitting) "Sending…" else "Send to $domain") }
        }
    }
}

private fun readUpload(context: android.content.Context, field: String, uri: Uri): Upload {
    val type = context.contentResolver.getType(uri) ?: "image/jpeg"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = input.readBytes()
        require(buffer.size <= MAX_FILE_BYTES) { "Photos can be at most 10 MB." }
        buffer
    } ?: throw IllegalArgumentException("Couldn't read that photo.")
    return Upload(bytes, type, "$field.${type.substringAfter('/', "jpg")}")
}

private fun displayName(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: "Photo selected"

private fun statusLabel(status: KycStatus?, loading: Boolean): String = when (status) {
    null -> if (loading) "Checking…" else "Status unknown"
    KycStatus.ACCEPTED -> "✓ Verified"
    KycStatus.PROCESSING -> "In review"
    KycStatus.NEEDS_INFO -> "Not verified yet"
    KycStatus.REJECTED -> "Verification declined"
}

private fun statusDetail(status: KycStatus?, domain: String): String = when (status) {
    KycStatus.ACCEPTED -> "$domain has verified your identity."
    KycStatus.PROCESSING -> "$domain is checking your details."
    KycStatus.REJECTED -> "$domain couldn't verify your identity. Contact them if you think this is a mistake."
    KycStatus.NEEDS_INFO -> "$domain needs some details before you can cash out with it."
    null -> "Verification uses Stellar's KYC API (SEP-12) with $domain, after signing in with your wallet."
}
