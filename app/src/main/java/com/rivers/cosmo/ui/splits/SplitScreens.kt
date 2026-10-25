package com.rivers.cosmo.ui.splits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.splits.BillSplit
import com.rivers.cosmo.splits.InvalidInviteException
import com.rivers.cosmo.splits.SplitInvite
import com.rivers.cosmo.splits.SplitMath
import com.rivers.cosmo.splits.SplitShare
import com.rivers.cosmo.splits.SplitStatus
import com.rivers.cosmo.splits.SplitsController
import com.rivers.cosmo.ui.components.AmountField
import com.rivers.cosmo.ui.components.AssetSelector
import com.rivers.cosmo.ui.components.FormColumn
import com.rivers.cosmo.ui.motion.entrance
import com.rivers.cosmo.wallet.WalletBalance
import com.rivers.cosmo.wallet.formatAmount
import com.rivers.cosmo.wallet.parseAmount
import com.rivers.cosmo.wallet.shortenAddress
import java.math.BigDecimal

/** The "Bill splits" part of the Payments tab: create, open an invite, and the list of splits. */
@Composable
fun SplitsSection(
    state: SplitsController.State,
    canCreate: Boolean,
    onCreate: () -> Unit,
    onOpen: (splitId: String) -> Unit,
    onOpenInvite: (link: String) -> Unit
) {
    var pasting by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bill splits", style = MaterialTheme.typography.titleMedium)
        Text(
            "Collect money for a shared bill or fund. Everyone pays their share to you, and the app tracks who has paid.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreate, enabled = canCreate) { Text("Create bill split") }
            OutlinedButton(onClick = { pasting = true }) { Text("Open invite") }
        }
        state.splits.sortedByDescending { it.split.createdAt }.forEachIndexed { i, view ->
            Card(Modifier.entrance(i).fillMaxWidth().clickable { onOpen(view.split.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(view.split.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (view.entry.organizer) "Organizer" else "Participant", style = MaterialTheme.typography.labelSmall)
                    }
                    if (view.entry.organizer) {
                        LinearProgressIndicator(progress = { progress(view.collected, view.split.total) }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${formatAmount(view.collected, view.split.assetCode)} of ${formatAmount(view.split.total, view.split.assetCode)} · " +
                                "${view.paidCount} of ${view.split.shares.size} paid",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(participantSummary(view), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (pasting) {
        PasteInviteDialog(onDismiss = { pasting = false }, onOpen = { link ->
            pasting = false
            onOpenInvite(link)
        })
    }
}

private fun participantSummary(view: SplitsController.SplitView): String {
    val share = view.entry.myShare?.let { view.split.shares[it] } ?: return "Choose which share is yours"
    val status = view.myStatus
    return when {
        status?.complete == true -> "You've paid your ${formatAmount(share.amount, view.split.assetCode)}"
        status != null -> "Paid ${formatAmount(status.paid, view.split.assetCode)} of ${formatAmount(share.amount, view.split.assetCode)}"
        else -> "Your share: ${formatAmount(share.amount, view.split.assetCode)}"
    }
}

private fun progress(collected: BigDecimal, total: BigDecimal): Float =
    if (total.signum() == 0) 0f else (collected.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)

@Composable
private fun PasteInviteDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open invite") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste the bill split link someone sent you. Links from a QR code open the app directly.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = link, onValueChange = { link = it }, label = { Text("Invite link") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onOpen(link.trim()) }, enabled = link.isNotBlank()) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Creating a split: either one total divided equally, or a separate amount for each person. */
@Composable
fun CreateSplitScreen(
    balances: List<WalletBalance>,
    onCreate: (title: String, asset: WalletBalance, shares: List<SplitShare>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var title by rememberSaveable { mutableStateOf("") }
    var equal by rememberSaveable { mutableStateOf(true) }
    var includeMe by rememberSaveable { mutableStateOf(true) }
    var total by rememberSaveable { mutableStateOf("") }
    var myAmount by rememberSaveable { mutableStateOf("") }
    var names by rememberSaveable { mutableStateOf(listOf("", "")) }
    var amounts by rememberSaveable { mutableStateOf(listOf("", "")) }
    var assetKey by rememberSaveable { mutableStateOf<String?>(null) }
    val xlm = balances.firstOrNull { it.isNative } ?: WalletBalance("XLM", null, BigDecimal.ZERO)
    val asset = balances.firstOrNull { "${it.code}:${it.issuer}" == assetKey } ?: xlm

    val shares: List<SplitShare>? = run {
        val people = (if (includeMe) listOf(ORGANIZER_LABEL) else emptyList()) + names.map(String::trim)
        if (people.any { it.isEmpty() } || people.size < 2) return@run null
        val values = if (equal) {
            parseAmount(total)?.let { SplitMath.equalShares(it, people.size) }
        } else {
            ((if (includeMe) listOf(myAmount) else emptyList()) + amounts).map { parseAmount(it) ?: return@run null }
        } ?: return@run null
        if (values.any { it.signum() <= 0 }) return@run null
        people.zip(values).mapIndexed { i, (name, amount) -> SplitShare(name, amount, isOrganizer = includeMe && i == 0) }
    }

    FormColumn(modifier) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Create bill split", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(value = title, onValueChange = { title = it.take(60) }, label = { Text("What's it for?") }, placeholder = { Text("Trip fund") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (balances.size > 1) AssetSelector(balances = balances, selected = asset, onSelected = { assetKey = "${it.code}:${it.issuer}" })

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = equal, onClick = { equal = true }, label = { Text("Split equally") })
            FilterChip(selected = !equal, onClick = { equal = false }, label = { Text("Custom amounts") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Include my share")
                Text("Your part is counted as already paid, since the money comes to you.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = includeMe, onCheckedChange = { includeMe = it })
        }
        if (equal) AmountField(value = total, onValueChange = { total = it }, currencyCode = asset.code)

        Text("Participants", style = MaterialTheme.typography.titleMedium)
        if (includeMe && !equal) {
            AmountField(value = myAmount, onValueChange = { myAmount = it }, currencyCode = asset.code, modifier = Modifier.fillMaxWidth())
            Text("↑ Your share", style = MaterialTheme.typography.labelSmall)
        }
        names.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { value -> names = names.toMutableList().also { it[i] = value.take(40) } },
                    label = { Text("Name or Cosmo ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (!equal) {
                    OutlinedTextField(
                        value = amounts[i],
                        onValueChange = { value -> amounts = amounts.toMutableList().also { it[i] = value } },
                        label = { Text(asset.code) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp)
                    )
                }
                IconButton(onClick = { names = names.filterIndexed { j, _ -> j != i }; amounts = amounts.filterIndexed { j, _ -> j != i } }, enabled = names.size > 1) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove participant")
                }
            }
        }
        TextButton(onClick = { names = names + ""; amounts = amounts + "" }, enabled = names.size < BillSplit.MAX_SHARES - 1) { Text("+ Add participant") }

        shares?.let { list ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Total: ${formatAmount(list.fold(BigDecimal.ZERO) { s, x -> s + x.amount }, asset.code)}", style = MaterialTheme.typography.titleSmall)
                    list.forEach { Text("${it.label}: ${formatAmount(it.amount, asset.code)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Button(onClick = { onCreate(title.ifBlank { "Bill split" }, asset, shares!!) }, enabled = shares != null, modifier = Modifier.fillMaxWidth()) {
            Text("Create and get invites")
        }
    }
}

private const val ORGANIZER_LABEL = "Organizer"

/** A split's details: progress, each share's status, invites (organizer) or paying your share (participant). */
@Composable
fun SplitDetailScreen(
    view: SplitsController.SplitView,
    inviteLink: (shareIndex: Int?) -> String,
    onPay: (shareIndex: Int) -> Unit,
    onChooseShare: (shareIndex: Int) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val split = view.split
    val organizer = view.entry.organizer
    var inviting by remember { mutableStateOf<Int?>(null) }
    var invitingAll by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    FormColumn(modifier) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(split.title, style = MaterialTheme.typography.headlineMedium)
        Text(
            if (organizer) "You're collecting this into your wallet." else "Organized by ${split.organizerCosmoId} · ${shortenAddress(split.organizerAddress)}",
            style = MaterialTheme.typography.bodySmall
        )
        Text("${formatAmount(split.total, split.assetCode)} in total · ${split.network.label}", style = MaterialTheme.typography.bodyMedium)

        if (organizer) {
            LinearProgressIndicator(progress = { progress(view.collected, split.total) }, modifier = Modifier.fillMaxWidth())
            Text("${formatAmount(view.collected, split.assetCode)} collected · ${view.paidCount} of ${split.shares.size} paid", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { invitingAll = true }, modifier = Modifier.fillMaxWidth()) { Text("Invite everyone") }
        } else {
            ParticipantActions(view, onPay, onChooseShare)
        }

        Text("Shares", style = MaterialTheme.typography.titleMedium)
        split.shares.forEachIndexed { index, share ->
            val status = view.statuses.getOrNull(index)
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(share.label + if (view.entry.myShare == index) " (you)" else "", style = MaterialTheme.typography.titleSmall)
                        Text(formatAmount(share.amount, split.assetCode), style = MaterialTheme.typography.bodySmall)
                        Text(statusLabel(share, status, split.assetCode), style = MaterialTheme.typography.labelSmall, color = statusColor(status))
                        status?.payer?.let { Text("from ${shortenAddress(it)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                    }
                    if (organizer && !share.isOrganizer) TextButton(onClick = { inviting = index }) { Text("Invite") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            TextButton(onClick = { confirmingDelete = true }) { Text(if (organizer) "Delete split" else "Remove") }
        }
        Text(
            "Payments are matched by their memo (like ${split.memoFor(1)}), so pay through Cosmo or keep the memo exactly as shown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    inviting?.let { index ->
        val share = split.shares[index]
        InviteDialog(
            title = "Invite ${share.label}",
            description = "${split.title}: ${share.label}'s share is ${formatAmount(share.amount, split.assetCode)}. Open this in Cosmo to pay it.",
            link = inviteLink(index),
            onDismiss = { inviting = null },
            onCopied = { onMessage("Invite link copied.") }
        )
    }
    if (invitingAll) {
        InviteDialog(
            title = "Invite everyone",
            description = "${split.title}: ${formatAmount(split.total, split.assetCode)} split ${split.shares.size} ways. Open this in Cosmo and pick your share.",
            link = inviteLink(null),
            onDismiss = { invitingAll = false },
            onCopied = { onMessage("Invite link copied.") }
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(if (organizer) "Delete this split?" else "Remove this split?") },
            text = { Text("It's only removed from this phone. Payments already made aren't affected.") },
            confirmButton = { TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ParticipantActions(view: SplitsController.SplitView, onPay: (Int) -> Unit, onChooseShare: (Int) -> Unit) {
    val myShare = view.entry.myShare
    if (myShare == null) {
        Text("Which share is yours?", style = MaterialTheme.typography.titleSmall)
        view.split.shares.forEachIndexed { i, share ->
            if (!share.isOrganizer) TextButton(onClick = { onChooseShare(i) }) { Text("I'm ${share.label} (${formatAmount(share.amount, view.split.assetCode)})") }
        }
        return
    }
    val share = view.split.shares[myShare]
    val status = view.myStatus
    val remaining = share.amount - (status?.paid ?: BigDecimal.ZERO)
    if (status?.complete == true) {
        Text("✓ You've paid your share.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
    } else {
        Button(onClick = { onPay(myShare) }, modifier = Modifier.fillMaxWidth()) { Text("Pay my share (${formatAmount(remaining, view.split.assetCode)})") }
    }
}

private fun statusLabel(share: SplitShare, status: SplitStatus.ShareStatus?, code: String): String = when {
    share.isOrganizer -> "Organizer's share"
    status == null -> "Not visible to you"
    status.complete -> "✓ Paid"
    status.paid.signum() > 0 -> "Paid ${formatAmount(status.paid, code)} so far"
    else -> "Waiting for payment"
}

@Composable
private fun statusColor(status: SplitStatus.ShareStatus?) = when {
    status?.complete == true -> MaterialTheme.colorScheme.primary
    status != null && status.paid.signum() > 0 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Opening an invite: shows who it's from (verified) and lets the user pick their share before joining. */
@Composable
fun JoinSplitScreen(
    link: String,
    onJoin: (shareIndex: Int?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val opened = remember(link) { runCatching { SplitInvite.open(link) } }
    var selected by rememberSaveable(link) { mutableStateOf(opened.getOrNull()?.shareIndex) }

    FormColumn(modifier) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Bill split invite", style = MaterialTheme.typography.headlineMedium)
        val invite = opened.getOrNull()
        if (invite == null) {
            val message = (opened.exceptionOrNull() as? InvalidInviteException)?.message ?: "This invite can't be opened."
            Text(message, color = MaterialTheme.colorScheme.error)
            return@FormColumn
        }
        val split = invite.split
        Text(split.title, style = MaterialTheme.typography.titleLarge)
        Text("✓ Signed by the wallet that receives the money", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text("From ${split.organizerCosmoId}", style = MaterialTheme.typography.bodyMedium)
        Text(split.organizerAddress, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text("${formatAmount(split.total, split.assetCode)} in total · ${split.network.label}", style = MaterialTheme.typography.bodyMedium)

        Text("Which share is yours?", style = MaterialTheme.typography.titleMedium)
        split.shares.forEachIndexed { i, share ->
            if (share.isOrganizer) return@forEachIndexed
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { selected = i }.padding(vertical = 4.dp)
            ) {
                RadioButton(selected = selected == i, onClick = { selected = i })
                Text("${share.label} · ${formatAmount(share.amount, split.assetCode)}")
            }
        }
        Button(onClick = { onJoin(selected) }, enabled = selected != null, modifier = Modifier.fillMaxWidth()) { Text("Join") }
        Text("Joining saves the split on this phone. You'll still review and confirm any payment.", style = MaterialTheme.typography.bodySmall)
    }
}
