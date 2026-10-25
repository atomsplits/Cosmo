package com.rivers.cosmo.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.auth.UserIdGenerator
import com.rivers.cosmo.chat.ChatService
import com.rivers.cosmo.ui.components.FormColumn
import com.rivers.cosmo.ui.motion.entrance

@Composable
fun ChatScreen(
    userId: String,
    state: ChatService.State,
    opening: Boolean,
    onStartChat: (cosmoId: String) -> Unit,
    onOpenConversation: (cosmoId: String) -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var cosmoId by rememberSaveable { mutableStateOf("") }
    val normalized = UserIdGenerator.normalize(cosmoId)
    val invalid = cosmoId.isNotBlank() && !UserIdGenerator.isValid(normalized)
    val online = state.connection == ChatService.Connection.Online

    FormColumn(modifier) {
        Text("Chat", style = MaterialTheme.typography.headlineMedium)
        ConnectionStatus(state, onReconnect)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your Cosmo ID", style = MaterialTheme.typography.labelLarge)
                Text(userId, fontFamily = FontFamily.Monospace)
                Text("Your key fingerprint", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Text(
                    state.ownFingerprint ?: "Generating your key…",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Messages are end-to-end encrypted with PGP. Your private key never leaves this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        OutlinedTextField(
            value = cosmoId,
            onValueChange = { cosmoId = it },
            label = { Text("Enter Cosmo ID") },
            placeholder = { Text("COSMO-XXXX-XXXX") },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) {
                { Text("Cosmo IDs look like COSMO-XXXX-XXXX") }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                onStartChat(normalized)
                cosmoId = ""
            },
            enabled = online && !opening && UserIdGenerator.isValid(normalized),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (opening) "Looking up their key…" else "Start chat")
        }

        if (state.conversations.isNotEmpty()) {
            Text("Conversations", style = MaterialTheme.typography.titleMedium)
            state.conversations.values
                .sortedByDescending { conversation -> conversation.messages.lastOrNull()?.sentAt ?: 0L }
                .forEachIndexed { i, conversation ->
                    Card(
                        modifier = Modifier
                            .entrance(i)
                            .fillMaxWidth()
                            .clickable { onOpenConversation(conversation.contactId) }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(conversation.contactId, fontFamily = FontFamily.Monospace)
                            Text(
                                conversation.messages.lastOrNull()?.text ?: "No messages yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun ConnectionStatus(state: ChatService.State, onReconnect: () -> Unit) {
    val (label, color) = when (state.connection) {
        ChatService.Connection.Online -> "Connected" to MaterialTheme.colorScheme.primary
        ChatService.Connection.Connecting -> "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        ChatService.Connection.Offline -> "Offline" to MaterialTheme.colorScheme.error
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("● $label", color = color, style = MaterialTheme.typography.labelLarge)
            if (state.connection == ChatService.Connection.Offline) {
                TextButton(onClick = onReconnect) { Text("Retry") }
            }
        }
        if (state.error != null && state.connection != ChatService.Connection.Online) {
            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
