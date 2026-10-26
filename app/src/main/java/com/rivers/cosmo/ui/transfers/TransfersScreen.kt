package com.rivers.cosmo.ui.transfers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.rivers.cosmo.anchor.AnchorTransfersController
import com.rivers.cosmo.ui.dashboard.DashboardLayoutStore
import com.rivers.cosmo.ui.dashboard.DashboardModule
import com.rivers.cosmo.ui.dashboard.ModularPage
import com.rivers.cosmo.ui.dashboard.WidgetCard
import com.rivers.cosmo.wallet.WalletController
import kotlinx.coroutines.delay

private const val POLL_MS = 5_000L

/**
 * Money in, out and across: buy XLM with a card, trade on Stellar's exchange, and cash out to a
 * bank, as widgets the user can arrange. (Sending is in Payments.)
 */
@Composable
fun TransfersScreen(
    state: WalletController.State,
    wallet: WalletController,
    transfers: AnchorTransfersController,
    anchorDomains: List<String>,
    layoutStore: DashboardLayoutStore,
    buyXlm: @Composable () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Follows purchases and cash-outs while this page is open, e.g. after coming back from an anchor's page.
    LaunchedEffect(transfers) {
        while (true) {
            transfers.refresh()
            delay(POLL_MS)
        }
    }
    val modules = listOf(
        DashboardModule("buy", "Buy XLM") { WidgetCard { buyXlm() } },
        DashboardModule("trade", "Trade") {
            WidgetCard {
                TradeScreen(
                    balances = state.balances,
                    network = state.network,
                    onFind = { code -> wallet.findAssets(code, anchorDomains) },
                    onQuote = wallet::quote,
                    onSwap = wallet::swap,
                    onMessage = onMessage
                )
            }
        },
        DashboardModule("cashout", "Cash out") {
            WidgetCard {
                CashOutScreen(
                    cashOut = transfers,
                    balances = state.balances,
                    anchorDomains = anchorDomains,
                    network = state.network,
                    onMessage = onMessage
                )
            }
        }
    )
    ModularPage(page = "transfers", title = "Transfers", modules = modules, layoutStore = layoutStore, modifier = modifier)
}
