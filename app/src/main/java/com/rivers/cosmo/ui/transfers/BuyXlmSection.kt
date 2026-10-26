package com.rivers.cosmo.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.anchor.AnchorTransfersController
import com.rivers.cosmo.anchor.PurchaseRules
import com.rivers.cosmo.anchor.Sep24
import com.rivers.cosmo.kyc.KycController
import com.rivers.cosmo.kyc.KycStatus
import com.rivers.cosmo.ui.components.AmountField
import com.rivers.cosmo.wallet.StellarNetwork
import com.rivers.cosmo.wallet.formatAmount
import com.rivers.cosmo.wallet.parseAmount
import kotlinx.coroutines.launch

/**
 * Buy XLM with a credit or debit card through an anchor (SEP-24 deposit). Locked until the anchor
 * has verified the user's identity (SEP-12): until then this shows the verification instead.
 * The anchor's own page takes the card payment, so card details never pass through Cosmo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuyXlmSection(
    transfers: AnchorTransfersController,
    kycState: KycController.State,
    network: StellarNetwork,
    onSelectAnchor: (String) -> Unit,
    /** The identity verification widget, shown inline while buying is locked. */
    verification: @Composable () -> Unit,
    onMessage: (String) -> Unit
) {
    val state by transfers.state.collectAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var amount by rememberSaveable { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    val domain = kycState.domain
    val verified = kycState.status == KycStatus.ACCEPTED

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CreditCard, contentDescription = null)
            Text("  Buy XLM with a card", style = MaterialTheme.typography.titleMedium)
        }
        when {
            domain == null -> Text("Add an anchor in Settings → Anchors to buy XLM through it.")
            else -> {
                if (kycState.domains.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        kycState.domains.forEach { option ->
                            FilterChip(selected = option == domain, onClick = { onSelectAnchor(option) }, label = { Text(option) })
                        }
                    }
                }
                if (!verified) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            "  " + if (kycState.loading && kycState.status == null) "Checking your verification…" else PurchaseRules.kycMessage(domain, kycState.status),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // Card purchases are regulated: the anchor has to know who's paying.
                    verification()
                } else {
                    Column {
                        AmountField(value = amount, onValueChange = { amount = it }, currencyCode = "XLM")
                        Text("Optional: you can also choose the amount on $domain's page.", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            starting = true
                            scope.launch {
                                transfers.startPurchase(domain, parseAmount(amount))
                                    .onSuccess { url ->
                                        amount = ""
                                        uriHandler.openUri(url)
                                    }
                                    .onFailure { onMessage(it.message ?: "Couldn't start the purchase.") }
                                starting = false
                            }
                        },
                        enabled = !starting && (amount.isBlank() || parseAmount(amount) != null),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (starting) "Opening $domain…" else "Buy on $domain") }
                    Text(
                        "$domain takes your card payment on its own secure page and shows its fees and rate there. " +
                            "Cosmo never sees your card details. The XLM arrives in this wallet when the payment clears." +
                            if (network == StellarNetwork.Testnet) " On the test network no real card is charged." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        state.purchases.forEach { purchase ->
            PurchaseCard(purchase, onOpen = uriHandler::openUri, onRemove = { scope.launch { transfers.remove(purchase.id) } })
        }
    }
}

@Composable
private fun PurchaseCard(purchase: AnchorTransfersController.Transfer, onOpen: (String) -> Unit, onRemove: () -> Unit) {
    val tx = purchase.transaction
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                (tx?.amountOut ?: tx?.amountIn)?.let { "${formatAmount(it, "XLM")} from ${purchase.domain}" } ?: "XLM from ${purchase.domain}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(tx?.let { Sep24.statusLabel(it.status, deposit = true) } ?: "Checking…", style = MaterialTheme.typography.bodyMedium)
            tx?.amountFee?.let { Text("Fee: ${it.toPlainString()}", style = MaterialTheme.typography.bodySmall) }
            tx?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            purchase.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tx?.moreInfoUrl?.let { url -> TextButton(onClick = { onOpen(url) }) { Text("Details") } }
                if (tx?.finished == true || (tx == null && purchase.error != null)) TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}
