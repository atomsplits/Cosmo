package com.rivers.cosmo.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.anchor.AnchorTransfersController
import com.rivers.cosmo.anchor.Sep24
import com.rivers.cosmo.ui.components.AmountField
import com.rivers.cosmo.ui.motion.entrance
import com.rivers.cosmo.wallet.StellarNetwork
import com.rivers.cosmo.wallet.WalletBalance
import com.rivers.cosmo.wallet.formatAmount
import com.rivers.cosmo.wallet.parseAmount
import kotlinx.coroutines.launch

/**
 * Cash out to a bank account through an anchor (SEP-24): the anchor's page collects the bank
 * details, then Cosmo sends it the crypto and it pays out the money.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CashOutScreen(
    cashOut: AnchorTransfersController,
    balances: List<WalletBalance>,
    anchorDomains: List<String>,
    network: StellarNetwork,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by cashOut.state.collectAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var chosenDomain by rememberSaveable { mutableStateOf<String?>(null) }
    // Falls back to the first anchor if the chosen one was removed in Settings.
    val domain = chosenDomain?.takeIf { it in anchorDomains } ?: anchorDomains.firstOrNull()
    var assets by remember { mutableStateOf<List<Sep24.TransferAsset>?>(null) }
    var assetsError by remember { mutableStateOf<String?>(null) }
    var assetCode by rememberSaveable { mutableStateOf<String?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<AnchorTransfersController.Transfer?>(null) }

    LaunchedEffect(domain) {
        assets = null
        assetsError = null
        val selected = domain ?: return@LaunchedEffect
        cashOut.withdrawAssets(selected)
            .onSuccess { assets = it }
            .onFailure { assetsError = it.message }
    }

    val asset = assets?.firstOrNull { it.code == assetCode } ?: assets?.firstOrNull()
    val balance = asset?.let { a -> balances.firstOrNull { if (a.isXlm) it.isNative else it.code == a.code && it.issuer == a.issuer } }
    val parsedAmount = parseAmount(amount)
    val amountProblem = when {
        parsedAmount == null || balance == null -> null
        parsedAmount > balance.amount -> "That's more than you have."
        asset.minAmount != null && parsedAmount < asset.minAmount -> "The minimum is ${asset.minAmount.toPlainString()}."
        asset.maxAmount != null && parsedAmount > asset.maxAmount -> "The maximum is ${asset.maxAmount.toPlainString()}."
        else -> null
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Cash out to your bank", style = MaterialTheme.typography.titleMedium)
        if (anchorDomains.isEmpty()) {
            Text("Add an anchor in Settings → Anchors to cash out through it.")
        } else {
            if (anchorDomains.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    anchorDomains.forEach { option ->
                        FilterChip(selected = option == domain, onClick = { chosenDomain = option }, label = { Text(option) })
                    }
                }
            }
            when {
                assetsError != null -> Text(assetsError.orEmpty(), color = MaterialTheme.colorScheme.error)
                assets == null -> Text("Checking what ${domain.orEmpty()} pays out…", style = MaterialTheme.typography.bodySmall)
                assets.orEmpty().isEmpty() -> Text("${domain.orEmpty()} isn't offering cash-outs right now.")
                else -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        assets.orEmpty().forEach { option ->
                            val label = if (option.isXlm) "XLM" else option.code
                            FilterChip(selected = option == asset, onClick = { assetCode = option.code }, label = { Text(label) })
                        }
                    }
                    if (balance == null) {
                        Text("You don't hold any ${asset?.code}. Get some in the Trade tab first.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Column {
                            AmountField(value = amount, onValueChange = { amount = it }, currencyCode = balance.code)
                            Text(
                                amountProblem ?: "Available: ${formatAmount(balance.amount, balance.code)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (amountProblem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                val selected = domain ?: return@Button
                                starting = true
                                scope.launch {
                                    cashOut.startWithdrawal(selected, balance, parsedAmount)
                                        .onSuccess { url -> uriHandler.openUri(url) }
                                        .onFailure { onMessage(it.message ?: "Couldn't start the cash-out.") }
                                    starting = false
                                }
                            },
                            enabled = parsedAmount != null && amountProblem == null && !starting,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (starting) "Signing in to ${domain.orEmpty()}…" else "Continue on ${domain.orEmpty()}") }
                        Text(
                            "${domain.orEmpty()} opens in your browser to collect your bank details (and verify your identity if needed). " +
                                "Come back here when it's done to send the funds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (state.cashOuts.isNotEmpty()) {
            Text("Your cash-outs", style = MaterialTheme.typography.titleMedium)
            state.cashOuts.forEachIndexed { i, item ->
                CashOutCard(
                    item = item,
                    paying = state.payingId == item.id,
                    onOpen = { url -> uriHandler.openUri(url) },
                    onPay = { paying = item },
                    onRemove = { scope.launch { cashOut.remove(item.id) } },
                    modifier = Modifier.entrance(i)
                )
            }
        }
    }

    paying?.let { item ->
        val tx = item.transaction
        AlertDialog(
            onDismissRequest = { paying = null },
            title = { Text("Send ${tx?.amountIn?.let { formatAmount(it, item.assetCode) }} to ${item.domain}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tx?.amountOut?.let { Text("You'll receive about ${it.toPlainString()} in your bank, after the anchor's fees.") }
                    Text("To:")
                    Text(tx?.withdrawAnchorAccount.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    tx?.withdrawMemo?.let { Text("Memo: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(
                        if (network == StellarNetwork.Public) "This uses real funds and can't be undone." else "This is on the test network, so no real money moves.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    paying = null
                    scope.launch {
                        cashOut.pay(item.id)
                            .onSuccess { onMessage("Sent. ${item.domain} will pay out to your bank.") }
                            .onFailure { onMessage(it.message ?: "The payment didn't go through.") }
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { paying = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CashOutCard(
    item: AnchorTransfersController.Transfer,
    paying: Boolean,
    onOpen: (String) -> Unit,
    onPay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tx = item.transaction
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(tx?.amountIn?.let { formatAmount(it, item.assetCode) } ?: item.assetCode, "via ${item.domain}").joinToString(" "),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                when {
                    item.paymentHash != null && tx?.finished != true -> "Funds sent · ${tx?.status?.let(Sep24::statusLabel) ?: "waiting for the anchor"}"
                    tx != null -> Sep24.statusLabel(tx.status)
                    else -> "Checking…"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            tx?.amountFee?.let { Text("Fee: ${it.toPlainString()}", style = MaterialTheme.typography.bodySmall) }
            tx?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            item.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.canPay) {
                    Button(onClick = onPay, enabled = !paying) { Text(if (paying) "Sending…" else "Send funds") }
                }
                tx?.moreInfoUrl?.let { url -> TextButton(onClick = { onOpen(url) }) { Text("Details") } }
                if (tx?.finished == true || (tx == null && item.error != null)) {
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
        }
    }
}
