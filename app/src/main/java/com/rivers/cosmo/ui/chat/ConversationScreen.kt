package com.rivers.cosmo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.chat.ChatService
import java.text.DateFormat
import java.util.Date

@Composable
fun ConversationScreen(
    conversation: ChatService.Conversation,
    online: Boolean,
    onSend: (text: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var showFingerprint by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val canSend = online && !conversation.keyChanged

    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) listState.animateScrollToItem(conversation.messages.lastIndex)
    }

    Column(modifier.imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClickLabel = "Show encryption key") { showFingerprint = !showFingerprint }
                    .padding(8.dp)
            ) {
                Text(conversation.contactId, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Text(
                    if (showFingerprint) "Tap to hide their key" else "🔒 End-to-end encrypted · tap to verify",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showFingerprint) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Their key fingerprint", style = MaterialTheme.typography.labelLarge)
                    Text(conversation.fingerprint ?: "Unknown", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Compare this with the fingerprint shown in their Chat tab, in person or on a call. " +
                            "If they match, nobody can read your messages but the two of you.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (conversation.keyChanged) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Text(
                    "${conversation.contactId}'s encryption key has changed since you last talked. This can mean " +
                        "someone is trying to intercept your messages, so sending is blocked.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(conversation.messages, key = { it.id }) { message -> MessageBubble(message) }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(if (online) "Message" else "Waiting for connection…") },
                enabled = canSend,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    onSend(draft)
                    draft = ""
                },
                enabled = canSend && draft.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatService.ChatMessage) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.sentAt))
    val status = when (message.status) {
        ChatService.Status.Sending -> "Sending…"
        ChatService.Status.Delivered -> "Delivered"
        ChatService.Status.Queued -> "Sent · they're offline"
        ChatService.Status.Failed -> "Not sent"
        ChatService.Status.Received -> if (message.verified) null else "⚠ Signature not verified"
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                message.text,
                color = if (message.outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .background(
                        if (message.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
            Text(
                listOfNotNull(time, status).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (message.status == ChatService.Status.Failed || !message.verified) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}
