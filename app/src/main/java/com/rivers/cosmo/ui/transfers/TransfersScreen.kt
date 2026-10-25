package com.rivers.cosmo.ui.transfers

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rivers.cosmo.ui.components.SendForm
import com.rivers.cosmo.wallet.WalletBalance
import com.rivers.cosmo.wallet.WalletController
import java.math.BigDecimal

/** Sends any asset the account holds (XLM, or tokens like USDC once it has a trustline for them). */
@Composable
fun TransfersScreen(
    state: WalletController.State,
    sending: Boolean,
    error: String?,
    onTransfer: (address: String, balance: WalletBalance, amount: BigDecimal, memo: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    SendForm(
        title = "Transfers",
        balances = state.balances,
        allowAssetChoice = true,
        network = state.network,
        sending = sending,
        error = error,
        onSubmit = onTransfer,
        modifier = modifier
    )
}
