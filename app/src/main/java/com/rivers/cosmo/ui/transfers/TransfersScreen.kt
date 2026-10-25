package com.rivers.cosmo.ui.transfers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rivers.cosmo.ui.components.AmountField
import com.rivers.cosmo.ui.components.CryptoSelector
import com.rivers.cosmo.ui.components.FormColumn
import com.rivers.cosmo.wallet.Crypto
import com.rivers.cosmo.wallet.parseAmount
import java.math.BigDecimal

@Composable
fun TransfersScreen(onTransfer: (amount: BigDecimal, crypto: Crypto) -> Unit, modifier: Modifier = Modifier) {
    var amount by rememberSaveable { mutableStateOf("") }
    var crypto by rememberSaveable { mutableStateOf(Crypto.XLM) }
    val parsedAmount = parseAmount(amount)

    FormColumn(modifier) {
        Text("Transfers", style = MaterialTheme.typography.headlineMedium)
        CryptoSelector(selected = crypto, onSelected = { crypto = it })
        AmountField(value = amount, onValueChange = { amount = it }, currencyCode = crypto.code)
        Button(
            onClick = { onTransfer(parsedAmount!!, crypto) },
            enabled = parsedAmount != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Transfer")
        }
    }
}
