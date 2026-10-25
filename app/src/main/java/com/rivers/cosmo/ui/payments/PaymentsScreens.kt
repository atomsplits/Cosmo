package com.rivers.cosmo.ui.payments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.ui.components.FormColumn
import com.rivers.cosmo.ui.components.SendForm
import com.rivers.cosmo.wallet.PaymentRecord
import com.rivers.cosmo.wallet.WalletBalance
import com.rivers.cosmo.wallet.WalletController
import com.rivers.cosmo.wallet.formatAmount
import com.rivers.cosmo.wallet.shortenAddress
import java.math.BigDecimal
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Composable
fun PaymentsScreen(state: WalletController.State, onCreatePayment: () -> Unit, modifier: Modifier = Modifier) {
    FormColumn(modifier) {
        Text("Payments", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onCreatePayment, enabled = !state.keyMissing, modifier = Modifier.fillMaxWidth()) {
            Text("Create payment")
        }
        Text("Recent activity", style = MaterialTheme.typography.titleMedium)
        when {
            state.payments.isNotEmpty() -> state.payments.forEach { PaymentRow(it) }
            state.loading && state.account == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            else -> Text("No payments yet.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PaymentRow(payment: PaymentRecord) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (payment.incoming) "Received from" else "Sent to", style = MaterialTheme.typography.labelMedium)
                Text(shortenAddress(payment.counterparty), fontFamily = FontFamily.Monospace)
                Text(
                    formatTimestamp(payment.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                (if (payment.incoming) "+" else "−") + formatAmount(payment.amount, payment.assetCode),
                style = MaterialTheme.typography.titleMedium,
                color = if (payment.incoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Horizon timestamps look like 2026-10-25T14:00:00Z; shown in the phone's time zone and format. */
private fun formatTimestamp(iso: String): String = try {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(parser.parse(iso)!!)
} catch (e: ParseException) {
    iso
}

@Composable
fun CreatePaymentScreen(
    state: WalletController.State,
    sending: Boolean,
    error: String?,
    onSubmit: (address: String, balance: WalletBalance, amount: BigDecimal, memo: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    SendForm(
        title = "Create payment",
        balances = state.balances,
        allowAssetChoice = false,
        network = state.network,
        sending = sending,
        error = error,
        onSubmit = onSubmit,
        onBack = onBack,
        modifier = modifier
    )
}
