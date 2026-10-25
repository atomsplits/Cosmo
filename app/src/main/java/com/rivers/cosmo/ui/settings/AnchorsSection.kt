package com.rivers.cosmo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.anchor.StellarToml
import com.rivers.cosmo.wallet.StellarNetwork

/** The anchors used on the current network, by domain: for cashing out, verification and trading. */
@Composable
fun AnchorsSection(network: StellarNetwork, domains: List<String>, onChange: (List<String>) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val normalized = StellarToml.normalizeDomain(input)
    val invalid = input.isNotBlank() && normalized == null
    fun add() {
        val domain = normalized ?: return
        if (domain !in domains) onChange(domains + domain)
        input = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Anchors connect Stellar to banks. Cosmo signs in to them with your wallet to cash out, verify your " +
                "identity, and find tokens to trade. These are for the ${if (network == StellarNetwork.Public) "public" else "test"} network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (domains.isEmpty()) Text("No anchors yet.", style = MaterialTheme.typography.bodyMedium)
        domains.forEach { domain ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(domain, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(domains - domain) }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Anchor domain") },
                placeholder = { Text("anchor.example.com") },
                isError = invalid,
                supportingText = if (invalid) ({ Text("Enter a domain, like testanchor.stellar.org.") }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = ::add, enabled = normalized != null) { Text("Add") }
        }
    }
}
