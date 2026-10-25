package com.rivers.cosmo.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.ui.components.AmountField
import com.rivers.cosmo.ui.components.AssetSelector
import com.rivers.cosmo.ui.components.FormColumn
import com.rivers.cosmo.wallet.POPULAR_TRADE_CODES
import com.rivers.cosmo.wallet.StellarNetwork
import com.rivers.cosmo.wallet.SwapQuote
import com.rivers.cosmo.wallet.TradeAsset
import com.rivers.cosmo.wallet.WalletBalance
import com.rivers.cosmo.wallet.formatAmount
import com.rivers.cosmo.wallet.parseAmount
import com.rivers.cosmo.wallet.shortenAddress
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val QUOTE_DELAY_MS = 500L
private const val QUOTE_REFRESH_MS = 15_000L

/**
 * Trades on Stellar's built-in exchange (the DEX), e.g. XLM for BTC. Tokens like BTC are issued by
 * anchors that hold the real coin; only issuers vouched for by their own website are offered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TradeScreen(
    balances: List<WalletBalance>,
    network: StellarNetwork,
    onFind: suspend (code: String) -> Result<List<TradeAsset>>,
    onQuote: suspend (from: TradeAsset, amount: BigDecimal, to: TradeAsset) -> Result<SwapQuote?>,
    onSwap: suspend (SwapQuote) -> Result<String>,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var fromKey by rememberSaveable { mutableStateOf<String?>(null) }
    var toCode by rememberSaveable { mutableStateOf<String?>(null) }
    var customCode by rememberSaveable { mutableStateOf("") }
    var toKey by rememberSaveable { mutableStateOf<String?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<TradeAsset>>(emptyList()) }
    var finding by remember { mutableStateOf(false) }
    var findError by remember { mutableStateOf<String?>(null) }
    var quote by remember { mutableStateOf<SwapQuote?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var quoteError by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<SwapQuote?>(null) }
    var swapping by remember { mutableStateOf(false) }

    val xlm = balances.firstOrNull { it.isNative } ?: WalletBalance("XLM", null, BigDecimal.ZERO)
    val from = balances.firstOrNull { TradeAsset.of(it).key == fromKey } ?: xlm
    val fromAsset = TradeAsset.of(from)
    val to = candidates.firstOrNull { it.key == toKey } ?: candidates.firstOrNull()
    val parsedAmount = parseAmount(amount)
    val overBalance = parsedAmount != null && parsedAmount > from.amount

    // The test network has the reference anchor's SRT; BTC and friends only really exist on the public one.
    val codes = buildList {
        if (!fromAsset.isNative) add("XLM")
        if (network == StellarNetwork.Testnet) add("SRT")
        addAll(POPULAR_TRADE_CODES)
        balances.filter { !it.isNative }.forEach { add(it.code) }
    }.distinct().filter { it != fromAsset.code || !fromAsset.isNative }

    LaunchedEffect(toCode, balances.map { it.code to it.issuer }) {
        val code = toCode ?: return@LaunchedEffect
        findError = null
        if (code == "XLM") {
            candidates = listOf(TradeAsset.XLM)
            return@LaunchedEffect
        }
        val held = balances.filter { it.code == code && !it.isNative }.map { TradeAsset.of(it) }
        candidates = held
        finding = true
        onFind(code)
            .onSuccess { found ->
                candidates = (held + found).distinctBy { it.key }
                if (candidates.isEmpty()) findError = "No verified $code found on the ${if (network == StellarNetwork.Public) "public" else "test"} network."
            }
            .onFailure { findError = it.message }
        finding = false
    }

    // Re-quote shortly after the user stops typing, and keep the price fresh while they look at it.
    LaunchedEffect(fromAsset.key, to?.key, parsedAmount) {
        quote = null
        quoteError = null
        val target = to ?: return@LaunchedEffect
        val sell = parsedAmount ?: return@LaunchedEffect
        if (target.key == fromAsset.key) return@LaunchedEffect
        delay(QUOTE_DELAY_MS)
        while (true) {
            quoting = true
            onQuote(fromAsset, sell, target)
                .onSuccess {
                    quote = it
                    quoteError = if (it == null) "Nobody is trading ${fromAsset.code} for ${target.code} right now." else null
                }
                .onFailure { quoteError = it.message }
            quoting = false
            delay(QUOTE_REFRESH_MS)
        }
    }

    FormColumn(modifier) {
        Text("Trade", style = MaterialTheme.typography.headlineSmall)
        Text("You pay", style = MaterialTheme.typography.labelLarge)
        if (balances.size > 1) AssetSelector(balances = balances, selected = from, onSelected = { fromKey = TradeAsset.of(it).key })
        Column {
            AmountField(value = amount, onValueChange = { amount = it }, currencyCode = from.code)
            Text(
                if (overBalance) "That's more than you have." else "Available: ${formatAmount(from.amount, from.code)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (overBalance) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text("You get", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            codes.forEach { code ->
                FilterChip(selected = code == toCode, onClick = { toCode = code; toKey = null }, label = { Text(code) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = customCode,
                onValueChange = { customCode = it.filter(Char::isLetterOrDigit).take(12) },
                label = { Text("Other token code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { toCode = customCode.uppercase(); toKey = null },
                enabled = customCode.isNotBlank()
            ) { Text("Find") }
        }

        if (finding) Text("Looking for verified issuers…", style = MaterialTheme.typography.bodySmall)
        findError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (candidates.size > 1 || (candidates.size == 1 && !candidates[0].isNative)) {
            Column(Modifier.selectableGroup()) {
                candidates.forEach { asset ->
                    val selected = asset.key == to?.key
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { toKey = asset.key })
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${asset.code} · ${asset.homeDomain ?: "in your wallet"}")
                            Text(
                                listOfNotNull(asset.name, asset.issuer?.let { "issuer ${shortenAddress(it)}" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        val shown = quote
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    shown != null -> {
                        Text("≈ ${formatAmount(shown.receiveAmount, shown.to.code)}", style = MaterialTheme.typography.titleLarge)
                        Text("1 ${shown.from.code} ≈ ${shown.rate.toPlainString()} ${shown.to.code}", style = MaterialTheme.typography.bodySmall)
                        Text("At least ${formatAmount(shown.minimumReceived, shown.to.code)} (1% price protection)", style = MaterialTheme.typography.bodySmall)
                    }
                    quoting -> Text("Getting a price…")
                    quoteError != null -> Text(quoteError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    else -> Text("Pick what to get and enter an amount to see a price.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (to != null && !to.isNative && balances.none { to.matches(it) }) {
            Text(
                "This adds ${to.code} to your wallet, which sets aside 0.5 XLM while you hold it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Tokens like BTC on Stellar are issued by anchors that hold the real coin. They're worth what their issuer " +
                "pays out for them; to move real bitcoin elsewhere, withdraw through the issuer's website.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = { confirming = shown },
            enabled = shown != null && !overBalance && !swapping,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (swapping) "Trading…" else "Review trade") }
    }

    confirming?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Trade ${formatAmount(pending.sendAmount, pending.from.code)}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("You'll get about ${formatAmount(pending.receiveAmount, pending.to.code)}, and at least ${formatAmount(pending.minimumReceived, pending.to.code)}.")
                    pending.to.homeDomain?.let { Text("${pending.to.code} issued by $it.") }
                    Text(
                        if (network == StellarNetwork.Public) "This uses real funds and can't be undone." else "This is on the test network, so no real money moves.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    swapping = true
                    scope.launch {
                        onSwap(pending)
                            .onSuccess {
                                amount = ""
                                onMessage("Traded ${formatAmount(pending.sendAmount, pending.from.code)} for ${pending.to.code}.")
                            }
                            .onFailure { onMessage(it.message ?: "The trade didn't go through.") }
                        swapping = false
                    }
                }) { Text("Trade") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } }
        )
    }
}
