package com.rivers.cosmo.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.rivers.cosmo.wallet.StellarAddress
import com.rivers.cosmo.wallet.StellarClient
import com.rivers.cosmo.wallet.StellarNetwork
import com.rivers.cosmo.wallet.WalletBalance
import com.rivers.cosmo.wallet.formatAmount
import com.rivers.cosmo.wallet.parseAmount
import java.math.BigDecimal

/**
 * A form for sending an asset to a Stellar address: recipient, optional asset choice, amount and
 * memo, then a confirmation step, because Stellar payments can't be reversed.
 */
@Composable
fun SendForm(
    title: String,
    balances: List<WalletBalance>,
    allowAssetChoice: Boolean,
    network: StellarNetwork,
    sending: Boolean,
    error: String?,
    onSubmit: (address: String, balance: WalletBalance, amount: BigDecimal, memo: String?) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    /** Pre-filled values, e.g. from a plugin's suggested payment. The user can still change them. */
    prefill: PaymentPrefill? = null
) {
    var address by rememberSaveable { mutableStateOf(prefill?.address.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(prefill?.amount.orEmpty()) }
    var memo by rememberSaveable { mutableStateOf(prefill?.memo.orEmpty()) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable { mutableStateOf(false) }

    val xlm = balances.firstOrNull { it.isNative } ?: WalletBalance("XLM", null, BigDecimal.ZERO)
    val selected = when {
        // A pre-filled request (e.g. a bill split share) names its asset; if the wallet doesn't hold
        // it, the zero balance makes the form explain that it can't be paid.
        prefill?.assetCode != null ->
            balances.firstOrNull { it.code == prefill.assetCode && it.issuer == prefill.assetIssuer }
                ?: WalletBalance(prefill.assetCode, prefill.assetIssuer, BigDecimal.ZERO)
        allowAssetChoice -> balances.firstOrNull { key(it) == selectedKey } ?: xlm
        else -> xlm
    }
    val trimmedAddress = address.trim()
    val addressInvalid = trimmedAddress.isNotEmpty() && !StellarAddress.isValid(trimmedAddress)
    val parsedAmount = parseAmount(amount)
    val overBalance = parsedAmount != null && parsedAmount > selected.amount
    val memoTooLong = memo.trim().toByteArray(Charsets.UTF_8).size > StellarClient.MAX_MEMO_BYTES
    val ready = StellarAddress.isValid(trimmedAddress) && parsedAmount != null && !overBalance && !memoTooLong && !sending

    FormColumn(modifier) {
        onBack?.let { TextButton(onClick = it, enabled = !sending) { Text("Back") } }
        Text(title, style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Wallet address") },
            placeholder = { Text("G…") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            isError = addressInvalid,
            supportingText = if (addressInvalid) {
                { Text("That isn't a valid Stellar address. It should be 56 characters starting with G.") }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )

        if (allowAssetChoice && balances.isNotEmpty()) {
            AssetSelector(balances = balances, selected = selected, onSelected = { selectedKey = key(it) })
        }

        Column {
            AmountField(value = amount, onValueChange = { amount = it }, currencyCode = selected.code)
            Text(
                if (overBalance) "That's more than you have." else "Available: ${formatAmount(selected.amount, selected.code)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (overBalance) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedTextField(
            value = memo,
            onValueChange = { memo = it },
            label = { Text("Memo (optional)") },
            singleLine = true,
            isError = memoTooLong,
            supportingText = {
                Text(if (memoTooLong) "Memos can be at most ${StellarClient.MAX_MEMO_BYTES} characters." else "Exchanges often require one.")
            },
            modifier = Modifier.fillMaxWidth()
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        Button(onClick = { confirming = true }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
            Text(if (sending) "Sending…" else "Review")
        }
    }

    if (confirming && parsedAmount != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Send ${formatAmount(parsedAmount, selected.code)}?") },
            text = {
                Column {
                    Text("To:")
                    Text(trimmedAddress, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    if (memo.isNotBlank()) Text("Memo: ${memo.trim()}")
                    Text(
                        if (network == StellarNetwork.Public) {
                            "This uses real XLM and can't be undone."
                        } else {
                            "This is on the test network, so no real money moves."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onSubmit(trimmedAddress, selected, parsedAmount, memo.trim().ifEmpty { null })
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }
}

data class PaymentPrefill(
    val address: String,
    val amount: String?,
    val memo: String?,
    /** Null means XLM (or the form's normal choice). */
    val assetCode: String? = null,
    val assetIssuer: String? = null
)

private fun key(balance: WalletBalance) = "${balance.code}:${balance.issuer.orEmpty()}"
