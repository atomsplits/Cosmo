package com.rivers.cosmo.ui.wallet

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.ui.theme.CosmoTheme
import com.rivers.cosmo.wallet.AccountState
import com.rivers.cosmo.wallet.StellarClient
import com.rivers.cosmo.wallet.StellarNetwork
import com.rivers.cosmo.wallet.Wallet
import com.rivers.cosmo.wallet.WalletBalance
import com.rivers.cosmo.wallet.WalletController
import com.rivers.cosmo.wallet.formatAmount
import com.rivers.cosmo.wallet.shortenAddress
import java.math.BigDecimal

@Composable
fun WalletScreen(
    state: WalletController.State,
    onRefresh: () -> Unit,
    onFund: () -> Unit,
    onAddressCopied: () -> Unit,
    onLogOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.network == StellarNetwork.Testnet) {
            Text(
                "TEST NETWORK · balances have no real value",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (state.keyMissing) {
            Notice(
                "Your wallet key isn't on this device. Log out, then log in again with your " +
                    "12-word recovery phrase to unlock your wallet."
            )
        } else {
            Text("Current Balance", style = MaterialTheme.typography.titleMedium)
            val active = state.account as? AccountState.Active
            Text(
                when {
                    active != null -> formatAmount(active.xlm, "XLM")
                    state.account == AccountState.NotActivated -> formatAmount(BigDecimal.ZERO, "XLM")
                    else -> "…"
                },
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center
            )
            active?.balances?.filterNot { it.isNative }?.forEach { balance -> OtherAsset(balance) }

            if (state.account == AccountState.NotActivated) {
                NotActivated(state, onFund)
            }

            state.address?.let { WalletIdCard(it, onCopied = onAddressCopied) }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text(if (state.loading) "Refreshing…" else "Refresh")
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onLogOut, modifier = Modifier.fillMaxWidth()) {
            Text("Log out")
        }
    }
}

@Composable
private fun OtherAsset(balance: WalletBalance) {
    Text(
        formatAmount(balance.amount, balance.code),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun NotActivated(state: WalletController.State, onFund: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Activate your wallet", style = MaterialTheme.typography.titleSmall)
            if (state.network.friendbotUrl != null) {
                Text(
                    "New Stellar accounts need XLM before they exist on the network. On the test network " +
                        "you can get free test XLM.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onFund, enabled = !state.funding, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.funding) "Requesting test XLM…" else "Get free test XLM")
                }
            } else {
                Text(
                    "New Stellar accounts need at least ${StellarClient.MIN_STARTING_BALANCE} XLM before they " +
                        "exist on the network. Send some to the address below from an exchange or another wallet.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
    }
}

/** Shows the shortened wallet address; tapping it toggles the full address. */
@Composable
private fun WalletIdCard(address: String, onCopied: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
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
                if (expanded) address else shortenAddress(address),
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expanded) "Tap to hide" else "Tap to show full ID",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(address))
                    onCopied()
                }) { Text("Copy") }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WalletScreenPreview() {
    CosmoTheme {
        WalletScreen(
            state = WalletController.State(
                network = StellarNetwork.Testnet,
                address = Wallet.DEMO_ADDRESS,
                loading = false,
                account = AccountState.Active(listOf(WalletBalance("XLM", null, BigDecimal("1250.5"))))
            ),
            onRefresh = {},
            onFund = {},
            onAddressCopied = {},
            onLogOut = {}
        )
    }
}
