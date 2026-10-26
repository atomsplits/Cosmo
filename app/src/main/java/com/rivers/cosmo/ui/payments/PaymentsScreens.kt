package com.rivers.cosmo.ui.payments

import androidx.compose.foundation.layout.Arrangement
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
import com.rivers.cosmo.ui.dashboard.DashboardLayoutStore
import com.rivers.cosmo.ui.dashboard.DashboardModule
import com.rivers.cosmo.ui.dashboard.ModularPage
import com.rivers.cosmo.ui.dashboard.WidgetCard
import com.rivers.cosmo.ui.components.PaymentPrefill
import com.rivers.cosmo.ui.components.SendForm
import com.rivers.cosmo.ui.motion.entrance
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

/** The Payments page: sending, bill splits and recent activity, as widgets the user can arrange. */
@Composable
fun PaymentsScreen(
    state: WalletController.State,
    onCreatePayment: () -> Unit,
    layoutStore: DashboardLayoutStore,
    modifier: Modifier = Modifier,
    billSplits: @Composable () -> Unit = {}
) {
    val modules = listOf(
        DashboardModule("send", "Create payment", isEmpty = state.keyMissing, emptyHint = "Appears when your wallet key is on this device") {
            Button(onClick = onCreatePayment, modifier = Modifier.fillMaxWidth()) { Text("Create payment") }
        },
        DashboardModule("splits", "Bill splits") { WidgetCard { billSplits() } },
        DashboardModule("activity", "Recent activity") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                when {
                    state.payments.isNotEmpty() -> state.payments.forEachIndexed { i, payment -> PaymentRow(payment, Modifier.entrance(i)) }
                    state.loading && state.account == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                    else -> Text("No payments yet.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    )
    ModularPage(page = "payments", title = "Payments", modules = modules, layoutStore = layoutStore, modifier = modifier)
}

@Composable
fun PaymentRow(payment: PaymentRecord, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
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
    modifier: Modifier = Modifier,
    prefill: PaymentPrefill? = null
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
        modifier = modifier,
        prefill = prefill
    )
}
