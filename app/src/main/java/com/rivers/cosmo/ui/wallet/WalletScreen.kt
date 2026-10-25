package com.rivers.cosmo.ui.wallet

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.ui.theme.CosmoTheme
import com.rivers.cosmo.wallet.Wallet

@Composable
fun WalletScreen(wallet: Wallet, onLogOut: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Current Balance", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(wallet.formattedBalance, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(24.dp))
        WalletIdCard(wallet)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onLogOut, modifier = Modifier.fillMaxWidth()) {
            Text("Log out")
        }
    }
}

/** Shows the shortened wallet ID; tapping it toggles the full ID. */
@Composable
private fun WalletIdCard(wallet: Wallet) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Hide full wallet ID" else "Show full wallet ID") {
                expanded = !expanded
            }
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Wallet ID", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (expanded) wallet.id else wallet.shortId,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (expanded) "Tap to hide" else "Tap to show full ID",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WalletScreenPreview() {
    CosmoTheme {
        WalletScreen(wallet = Wallet.demo, onLogOut = {})
    }
}
