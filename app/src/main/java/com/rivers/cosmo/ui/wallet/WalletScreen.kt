package com.rivers.cosmo.ui.wallet

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.BannerSettings
import com.rivers.cosmo.settings.BannerStyle
import com.rivers.cosmo.ui.dashboard.DashboardLayoutStore
import com.rivers.cosmo.ui.dashboard.DashboardModule
import com.rivers.cosmo.ui.dashboard.ModularPage
import com.rivers.cosmo.ui.payments.PaymentRow
import com.rivers.cosmo.wallet.AccountState
import com.rivers.cosmo.wallet.StellarClient
import com.rivers.cosmo.wallet.StellarNetwork
import com.rivers.cosmo.wallet.WalletController
import com.rivers.cosmo.wallet.formatAmount
import com.rivers.cosmo.wallet.shortenAddress
import java.math.BigDecimal

/** The Wallet tab: a dashboard of modules the user can rearrange, resize and hide. */
@Composable
fun WalletDashboard(
    modules: List<DashboardModule>,
    layoutStore: DashboardLayoutStore,
    banner: BannerSettings,
    network: StellarNetwork,
    modifier: Modifier = Modifier
) {
    ModularPage(
        page = DashboardLayoutStore.WALLET,
        title = null,
        modules = modules,
        layoutStore = layoutStore,
        modifier = modifier,
        header = { layout, editing, update ->
            if (banner.style != BannerStyle.Off) {
                WalletBanner(banner, network, layout, editing, onLayoutChange = update)
            }
        }
    )
}

/** The built-in wallet modules, in their default top-to-bottom order. */
fun walletModules(
    state: WalletController.State,
    onRefresh: () -> Unit,
    onFund: () -> Unit,
    onAddressCopied: () -> Unit,
    /** Identity verification with the user's anchor (SEP-12). */
    kycSection: @Composable () -> Unit,
    /** Buying XLM by card, locked until the anchor has verified the user. */
    buyXlm: @Composable () -> Unit,
    onLogOut: () -> Unit
): List<DashboardModule> = listOf(
    DashboardModule("network", "Network") { NetworkBadge(state.network) },
    DashboardModule(
        "key-missing", "Wallet key notice",
        isEmpty = !state.keyMissing,
        emptyHint = "Appears if this device doesn't have your wallet key"
    ) {
        Notice(
            "Your wallet key isn't on this device. Log out, then log in again with your " +
                "12-word recovery phrase to unlock your wallet."
        )
    },
    DashboardModule("balance", "Balance", isEmpty = state.keyMissing) { BalanceModule(state) },
    DashboardModule(
        "activation", "Activate wallet",
        isEmpty = state.account != AccountState.NotActivated,
        emptyHint = "Appears while your wallet isn't activated"
    ) { NotActivated(state, onFund) },
    DashboardModule("address", "Wallet ID", isEmpty = state.address == null) {
        state.address?.let { WalletIdCard(it, onAddressCopied) }
    },
    DashboardModule("refresh", "Refresh", isEmpty = state.keyMissing) { RefreshModule(state, onRefresh) },
    DashboardModule("activity", "Recent activity", isEmpty = state.keyMissing) { ActivityModule(state) },
    DashboardModule("kyc", "Identity verification") { kycSection() },
    DashboardModule("buy", "Buy XLM") {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { buyXlm() } }
    },
    DashboardModule("logout", "Log out") {
        OutlinedButton(onClick = onLogOut, modifier = Modifier.fillMaxWidth()) { Text("Log out") }
    }
)

@Composable
private fun NetworkBadge(network: StellarNetwork) {
    Text(
        if (network == StellarNetwork.Testnet) "TEST NETWORK · balances have no real value" else "PUBLIC NETWORK · real XLM",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
private fun BalanceModule(state: WalletController.State) {
    val active = state.account as? AccountState.Active
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Current Balance", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                active != null -> formatAmount(active.xlm, "XLM")
                state.account == AccountState.NotActivated -> formatAmount(BigDecimal.ZERO, "XLM")
                else -> "…"
            },
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center
        )
        active?.balances?.filterNot { it.isNative }?.forEach { balance ->
            Text(
                formatAmount(balance.amount, balance.code),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RefreshModule(state: WalletController.State, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        TextButton(onClick = onRefresh, enabled = !state.loading) {
            Text(if (state.loading) "Refreshing…" else "Refresh")
        }
    }
}

@Composable
private fun ActivityModule(state: WalletController.State) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recent activity", style = MaterialTheme.typography.titleMedium)
        if (state.payments.isEmpty()) {
            Text("No payments yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            state.payments.take(ACTIVITY_ITEMS).forEach { PaymentRow(it) }
        }
    }
}

private const val ACTIVITY_ITEMS = 5

@Composable
private fun NotActivated(state: WalletController.State, onFund: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Activate your wallet", style = MaterialTheme.typography.titleSmall)
            if (state.network.friendbotUrl != null) {
                Text(
                    "New Stellar accounts need XLM before they exist on the network. On the test network " +
                        "you can get free test XLM.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onFund, enabled = !state.funding, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.funding) "Requesting test XLM…" else "Get free test XLM")
                }
            } else {
                Text(
                    "New Stellar accounts need at least ${StellarClient.MIN_STARTING_BALANCE} XLM before they " +
                        "exist on the network. Send some to your wallet ID from an exchange or another wallet.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
    }
}

/** Shows the shortened wallet address; tapping it toggles the full address. */
@Composable
private fun WalletIdCard(address: String, onCopied: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Hide full wallet ID" else "Show full wallet ID") {
                expanded = !expanded
            }
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Wallet ID", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (expanded) address else shortenAddress(address),
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expanded) "Tap to hide" else "Tap to show full ID",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(address))
                    onCopied()
                }) { Text("Copy") }
            }
        }
    }
}
