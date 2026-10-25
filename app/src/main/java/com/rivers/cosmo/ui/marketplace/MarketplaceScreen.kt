package com.rivers.cosmo.ui.marketplace

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.market.Goods
import com.rivers.cosmo.market.Listing
import com.rivers.cosmo.market.MarketProtocol
import com.rivers.cosmo.market.MarketplaceController
import com.rivers.cosmo.market.Order
import com.rivers.cosmo.ui.components.AmountField
import com.rivers.cosmo.ui.components.FormColumn
import com.rivers.cosmo.ui.motion.LocalMotion
import com.rivers.cosmo.ui.motion.entrance
import com.rivers.cosmo.ui.motion.screenTransition
import com.rivers.cosmo.wallet.Escrow
import com.rivers.cosmo.wallet.StellarNetwork
import com.rivers.cosmo.wallet.formatAmount
import com.rivers.cosmo.wallet.parseAmount
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MarketTab(val label: String) { Browse("Browse"), Selling("Selling"), Orders("Orders") }

private const val REFRESH_MS = 10_000L

/** Buy and sell digital goods for XLM, paid through an escrow on Stellar. */
@Composable
fun MarketplaceScreen(
    market: MarketplaceController,
    userId: String,
    network: StellarNetwork,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by market.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(MarketTab.Browse) }
    val motion = LocalMotion.current

    LaunchedEffect(market) {
        market.browse()
        while (true) {
            market.refresh()
            delay(REFRESH_MS)
        }
    }

    Column(modifier) {
        TabRow(selectedTabIndex = tab.ordinal) {
            MarketTab.entries.forEach { option ->
                Tab(selected = option == tab, onClick = { tab = option }, text = { Text(option.label) })
            }
        }
        AnimatedContent(targetState = tab, transitionSpec = { screenTransition(motion) }, label = "market tab", modifier = Modifier.weight(1f)) { shown ->
            val content = Modifier.fillMaxWidth()
            when (shown) {
                MarketTab.Browse -> BrowseTab(state, userId, network, market, onMessage, onBought = { tab = MarketTab.Orders }, modifier = content)
                MarketTab.Selling -> SellingTab(state, market, onMessage, modifier = content)
                MarketTab.Orders -> OrdersTab(state, network, market, onMessage, modifier = content)
            }
        }
    }
}

@Composable
private fun BrowseTab(
    state: MarketplaceController.State,
    userId: String,
    network: StellarNetwork,
    market: MarketplaceController,
    onMessage: (String) -> Unit,
    onBought: () -> Unit,
    modifier: Modifier
) {
    val scope = rememberCoroutineScope()
    var buying by remember { mutableStateOf<Listing?>(null) }
    FormColumn(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Marketplace", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = market::browse, enabled = !state.browsing) { Text(if (state.browsing) "Loading…" else "Refresh") }
        }
        state.browseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.listings.isEmpty() && !state.browsing) Text("Nothing for sale yet. List something in Selling.")
        state.listings.forEachIndexed { i, listing ->
            Card(Modifier.entrance(i).fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(listing.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(formatAmount(listing.price, "XLM"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (listing.description.isNotBlank()) Text(listing.description, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${if (listing.kind == Listing.FILE) "File" else "Text"} · sold by ${listing.sellerId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (listing.sellerId == userId) {
                        Text("Your listing", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Button(onClick = { buying = listing }) { Text("Buy") }
                    }
                }
            }
        }
    }

    buying?.let { listing ->
        AlertDialog(
            onDismissRequest = { buying = null },
            title = { Text("Buy ${listing.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How it works:")
                    Text("1. The seller's app sends the item to you, locked.", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "2. You put ${formatAmount(listing.price, "XLM")} (plus a ${Escrow.DEPOSIT.toPlainString()} XLM deposit you get back) " +
                            "into an escrow on Stellar that neither of you controls alone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("3. The seller can only take the money by revealing the key that unlocks your item.", style = MaterialTheme.typography.bodySmall)
                    Text("4. If they don't within 48 hours, you can take a refund.", style = MaterialTheme.typography.bodySmall)
                    if (network == StellarNetwork.Testnet) Text("This is on the test network, so no real money moves.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    buying = null
                    scope.launch {
                        market.buy(listing)
                            .onSuccess {
                                onMessage("Asked ${listing.sellerId} for an offer.")
                                onBought()
                            }
                            .onFailure { onMessage(it.message ?: "Couldn't contact the seller.") }
                    }
                }) { Text("Ask for an offer") }
            },
            dismissButton = { TextButton(onClick = { buying = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SellingTab(state: MarketplaceController.State, market: MarketplaceController, onMessage: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(Listing.TEXT) }
    var text by rememberSaveable { mutableStateOf("") }
    var file by remember { mutableStateOf<Goods?>(null) }
    var saving by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { runCatching { readFile(context, uri) }.getOrNull() }
            when {
                picked == null -> onMessage("Couldn't read that file.")
                picked.bytes.size > MarketProtocol.MAX_GOODS_BYTES -> onMessage("Files can be at most 48 KB.")
                else -> file = picked
            }
        }
    }
    val parsedPrice = parseAmount(price)
    val goods = if (kind == Listing.TEXT) text.takeIf { it.isNotBlank() }?.let { Goods(Listing.TEXT, it.toByteArray(Charsets.UTF_8)) } else file
    val ready = title.isNotBlank() && parsedPrice != null && goods != null && !saving

    FormColumn(modifier) {
        Text("Sell something", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = title, onValueChange = { title = it.take(80) }, label = { Text("Title") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = description, onValueChange = { description = it.take(1000) }, label = { Text("Description (public)") },
            minLines = 2, modifier = Modifier.fillMaxWidth()
        )
        AmountField(value = price, onValueChange = { price = it }, currencyCode = "XLM")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == Listing.TEXT, onClick = { kind = Listing.TEXT }, label = { Text("Text (code, link…)") })
            FilterChip(selected = kind == Listing.FILE, onClick = { kind = Listing.FILE }, label = { Text("File") })
        }
        if (kind == Listing.TEXT) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("What the buyer gets (private)") },
                minLines = 3, modifier = Modifier.fillMaxWidth()
            )
        } else {
            OutlinedButton(onClick = { picker.launch("*/*") }) {
                Text(file?.let { "✓ ${it.fileName ?: "File"} (${it.bytes.size / 1024 + 1} KB)" } ?: "Choose file (up to 48 KB)")
            }
        }
        Text(
            "Buyers only get the item once they've paid into escrow, and you only get paid by unlocking it for them. " +
                "Your phone delivers it automatically while Cosmo is open, so keep it running to make sales.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                saving = true
                scope.launch {
                    market.createListing(title, description, parsedPrice!!, goods!!)
                        .onSuccess {
                            title = ""; description = ""; price = ""; text = ""; file = null
                            onMessage("Listed.")
                        }
                        .onFailure { onMessage(it.message ?: "Couldn't list that.") }
                    saving = false
                }
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "Listing…" else "List for sale") }

        if (state.myListings.isNotEmpty()) {
            Text("Your listings", style = MaterialTheme.typography.titleMedium)
            state.myListings.forEachIndexed { i, listing ->
                Card(Modifier.entrance(i).fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(listing.title, style = MaterialTheme.typography.titleSmall)
                            Text(formatAmount(listing.price, "XLM"), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { scope.launch { market.deleteListing(listing.id) } }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrdersTab(
    state: MarketplaceController.State,
    network: StellarNetwork,
    market: MarketplaceController,
    onMessage: (String) -> Unit,
    modifier: Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var paying by remember { mutableStateOf<Order?>(null) }
    var saving by remember { mutableStateOf<Goods?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val goods = saving
        saving = null
        if (uri == null || goods == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(goods.bytes) } != null }.getOrDefault(false)
            }
            onMessage(if (saved) "Saved." else "Couldn't save the file.")
        }
    }
    fun run(action: suspend () -> Result<Unit>, done: String) {
        scope.launch { action().onSuccess { onMessage(done) }.onFailure { onMessage(it.message ?: "Something went wrong.") } }
    }
    val now = System.currentTimeMillis() / 1000

    FormColumn(modifier) {
        Text("Orders", style = MaterialTheme.typography.headlineSmall)
        if (state.orders.isEmpty()) Text("Things you buy and sell show up here.")
        state.orders.forEachIndexed { i, order ->
            val busy = order.id in state.busy
            Card(Modifier.entrance(i).fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(order.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text((if (order.buying) "Buying · " else "Selling · ") + formatAmount(order.price, "XLM"), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(statusText(order, now), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        (if (order.buying) "Seller: " else "Buyer: ") + order.counterparty,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    order.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

                    val goods = order.goods
                    if (order.buying && goods != null) {
                        if (goods.kind == Listing.TEXT) {
                            Text(goods.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { clipboard.setText(AnnotatedString(goods.text)); onMessage("Copied.") }) { Text("Copy") }
                        } else {
                            Button(onClick = {
                                saving = goods
                                saver.launch(goods.fileName ?: "${order.title}.bin")
                            }) { Text("Save ${goods.fileName ?: "file"}") }
                        }
                    }
                    when {
                        order.buying && (order.status == Order.Status.Offered || order.status == Order.Status.Opening) ->
                            Button(onClick = { paying = order }, enabled = !busy) {
                                Text(if (busy) "Paying…" else if (order.status == Order.Status.Opening) "Finish payment" else "Pay into escrow")
                            }
                        order.canRefund(now) ->
                            OutlinedButton(onClick = { run({ market.refund(order.id) }, if (order.status == Order.Status.Completed) "Deposit returned." else "Refunded.") }, enabled = !busy) {
                                Text(if (order.status == Order.Status.Completed) "Get your ${Escrow.DEPOSIT.toPlainString()} XLM deposit back" else "Get a refund")
                            }
                        !order.buying && order.status == Order.Status.Funded ->
                            OutlinedButton(onClick = { run({ market.retryRelease(order.id) }, "Payment collected.") }, enabled = !busy) {
                                Text(if (busy) "Collecting…" else "Collect payment")
                            }
                    }
                }
            }
        }
    }

    paying?.let { order ->
        val total = Escrow.startingBalance(order.price)
        AlertDialog(
            onDismissRequest = { paying = null },
            title = { Text("Pay ${formatAmount(total, "XLM")} into escrow?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${formatAmount(order.price, "XLM")} for ${order.title}, plus a ${Escrow.DEPOSIT.toPlainString()} XLM deposit that comes back to you.")
                    Text("The seller gets paid only by unlocking your item. If they haven't by ${deadlineText(order.deadline)}, you can take a refund.", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (network == StellarNetwork.Public) "This uses real XLM." else "This is on the test network, so no real money moves.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    paying = null
                    run({ market.pay(order.id) }, "Paid into escrow. Your item unlocks when the seller collects.")
                }) { Text("Pay") }
            },
            dismissButton = { TextButton(onClick = { paying = null }) { Text("Cancel") } }
        )
    }
}

private fun statusText(order: Order, now: Long): String = when (order.status) {
    Order.Status.Requested -> "Waiting for the seller's app to send an offer."
    Order.Status.Offered -> if (order.buying) "Offer received. Pay into escrow to get the item." else "Offer sent. Waiting for the buyer to pay."
    Order.Status.Opening -> "The payment was interrupted. Finish it to lock the escrow."
    Order.Status.Funded -> if (order.buying) {
        if (order.deadline != null && now >= order.deadline) "The seller didn't deliver in time." else "In escrow. Your item unlocks when the seller collects (by ${deadlineText(order.deadline)})."
    } else {
        "The buyer paid into escrow. Collecting it delivers the item."
    }
    Order.Status.Completed -> if (order.buying) {
        when {
            order.refundDone -> "Delivered. Deposit returned."
            else -> "Delivered. Your deposit comes back after ${deadlineText(order.deadline)}."
        }
    } else {
        "Sold. You were paid ${formatAmount(order.price, "XLM")}."
    }
    Order.Status.Refunded -> "Refunded."
    Order.Status.Declined -> "Declined."
}

private fun deadlineText(deadline: Long?): String =
    deadline?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000)) } ?: "the deadline"

private fun readFile(context: android.content.Context, uri: Uri): Goods? {
    val resolver = context.contentResolver
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    val bytes = resolver.openInputStream(uri)?.use { input ->
        // Read at most one byte past the limit, so huge files aren't loaded just to be refused.
        val buffer = ByteArray(MarketProtocol.MAX_GOODS_BYTES + 1)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        buffer.copyOf(total)
    } ?: return null
    return Goods(Listing.FILE, bytes, name, resolver.getType(uri))
}
