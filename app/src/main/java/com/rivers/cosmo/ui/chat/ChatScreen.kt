package com.rivers.cosmo.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.rivers.cosmo.auth.UserIdGenerator
import com.rivers.cosmo.ui.components.FormColumn

@Composable
fun ChatScreen(onStartChat: (cosmoId: String) -> Unit, modifier: Modifier = Modifier) {
    var cosmoId by rememberSaveable { mutableStateOf("") }
    val normalized = UserIdGenerator.normalize(cosmoId)
    val invalid = cosmoId.isNotBlank() && !UserIdGenerator.isValid(normalized)

    FormColumn(modifier) {
        Text("Chat", style = MaterialTheme.typography.headlineMedium)
        Text("Start a conversation with another Cosmo user.", style = MaterialTheme.typography.bodyMedium)

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
            onClick = { onStartChat(normalized) },
            enabled = UserIdGenerator.isValid(normalized),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start chat")
        }
    }
}
