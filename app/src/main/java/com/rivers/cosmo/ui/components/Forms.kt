package com.rivers.cosmo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.wallet.WalletBalance
import com.rivers.cosmo.wallet.formatAmount
import com.rivers.cosmo.wallet.parseAmount
import com.rivers.cosmo.wallet.shortenAddress

/** A scrollable column with the standard page padding used by every tab. */
@Composable
fun FormColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

/** A number field for amounts; shows an error once the text isn't a valid amount. */
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    currencyCode: String,
    modifier: Modifier = Modifier
) {
    val invalid = value.isNotBlank() && parseAmount(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Amount") },
        suffix = { Text(currencyCode) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) {
            { Text("Enter an amount above 0 with up to 7 decimal places") }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = modifier.fillMaxWidth()
    )
}

/** Picks which of the account's assets to send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetSelector(
    balances: List<WalletBalance>,
    selected: WalletBalance,
    onSelected: (WalletBalance) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = assetLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text("Asset") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            balances.forEach { balance ->
                DropdownMenuItem(
                    text = { Text("${assetLabel(balance)} · ${formatAmount(balance.amount, balance.code)}") },
                    onClick = {
                        onSelected(balance)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** e.g. "XLM (Stellar Lumens)" or "USDC · GA5Z…KZVN" so look-alike assets from different issuers stay distinguishable. */
fun assetLabel(balance: WalletBalance): String =
    if (balance.issuer == null) "XLM (Stellar Lumens)" else "${balance.code} · ${shortenAddress(balance.issuer)}"
