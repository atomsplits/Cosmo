package com.rivers.cosmo.ui.transfers

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rivers.cosmo.anchor.CashOutController
import com.rivers.cosmo.ui.motion.LocalMotion
import com.rivers.cosmo.ui.motion.screenTransition
import com.rivers.cosmo.wallet.WalletController

private enum class TransferMode(val label: String) { Trade("Trade"), CashOut("Cash out") }

/** Converting money: trade between assets on Stellar's exchange, or cash out to a bank through an anchor. (Sending is in Payments.) */
@Composable
fun TransfersScreen(
    state: WalletController.State,
    wallet: WalletController,
    cashOut: CashOutController,
    anchorDomains: List<String>,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by rememberSaveable { mutableStateOf(TransferMode.Trade) }
    val motion = LocalMotion.current
    Column(modifier) {
        TabRow(selectedTabIndex = mode.ordinal) {
            TransferMode.entries.forEach { option ->
                Tab(selected = option == mode, onClick = { mode = option }, text = { Text(option.label) })
            }
        }
        AnimatedContent(
            targetState = mode,
            transitionSpec = { screenTransition(motion) },
            label = "transfer mode",
            modifier = Modifier.weight(1f)
        ) { shown ->
            val content = Modifier.fillMaxWidth()
            when (shown) {
                TransferMode.Trade -> TradeScreen(
                    balances = state.balances,
                    network = state.network,
                    onFind = { code -> wallet.findAssets(code, anchorDomains) },
                    onQuote = wallet::quote,
                    onSwap = wallet::swap,
                    onMessage = onMessage,
                    modifier = content
                )
                TransferMode.CashOut -> CashOutScreen(
                    cashOut = cashOut,
                    balances = state.balances,
                    anchorDomains = anchorDomains,
                    network = state.network,
                    onMessage = onMessage,
                    modifier = content
                )
            }
        }
    }
}
