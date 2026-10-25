package com.rivers.cosmo.ui.payments

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import com.rivers.cosmo.ui.components.AmountField
import com.rivers.cosmo.ui.components.FormColumn
import com.rivers.cosmo.wallet.StellarAddress
import com.rivers.cosmo.wallet.parseAmount
import java.math.BigDecimal

@Composable
fun PaymentsScreen(onCreatePayment: () -> Unit, modifier: Modifier = Modifier) {
    FormColumn(modifier) {
        Text("Payments", style = MaterialTheme.typography.headlineMedium)
        Text("You haven't made any payments yet.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onCreatePayment, modifier = Modifier.fillMaxWidth()) {
            Text("Create payment")
        }
    }
}

@Composable
fun CreatePaymentScreen(
    onSubmit: (address: String, amount: BigDecimal) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var address by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    val trimmedAddress = address.trim()
    val addressInvalid = trimmedAddress.isNotEmpty() && !StellarAddress.isValid(trimmedAddress)
    val parsedAmount = parseAmount(amount)

    FormColumn(modifier) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Create payment", style = MaterialTheme.typography.headlineMedium)

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

        AmountField(value = amount, onValueChange = { amount = it }, currencyCode = "XLM")

        Button(
            onClick = { onSubmit(trimmedAddress, parsedAmount!!) },
            enabled = StellarAddress.isValid(trimmedAddress) && parsedAmount != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send payment")
        }
    }
}
