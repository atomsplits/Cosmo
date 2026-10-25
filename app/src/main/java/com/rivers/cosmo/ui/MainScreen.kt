package com.rivers.cosmo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.rivers.cosmo.settings.AppSettings
import com.rivers.cosmo.ui.chat.ChatScreen
import com.rivers.cosmo.ui.payments.CreatePaymentScreen
import com.rivers.cosmo.ui.payments.PaymentsScreen
import com.rivers.cosmo.ui.settings.SettingsScreen
import com.rivers.cosmo.ui.transfers.TransfersScreen
import com.rivers.cosmo.ui.wallet.WalletScreen
import com.rivers.cosmo.wallet.Wallet
import com.rivers.cosmo.wallet.shortenAddress
import kotlinx.coroutines.launch

private enum class MainTab(val label: String, val icon: ImageVector) {
    Wallet("Wallet", Icons.Filled.AccountBalanceWallet),
    Payments("Payments", Icons.Filled.Payments),
    Transfers("Transfers", Icons.Filled.SwapHoriz),
    Chat("Chat", Icons.AutoMirrored.Filled.Chat),
    Settings("Settings", Icons.Filled.Settings)
}

// TODO: Payments, transfers and chat aren't connected to the Stellar network or a chat service yet,
//  so for now submitting those forms only shows a message.
private const val NOT_CONNECTED = "Cosmo isn't connected to the network yet."

/** The signed-in app: one screen per tab in the bottom navigation bar. */
@Composable
fun MainScreen(
    wallet: Wallet,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onLogOut: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Wallet) }
    var creatingPayment by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    BackHandler(enabled = creatingPayment || tab != MainTab.Wallet) {
        if (creatingPayment) creatingPayment = false else tab = MainTab.Wallet
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            // Tapping the current tab again returns to its first screen.
                            if (tab == item) creatingPayment = false
                            tab = item
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (tab) {
            MainTab.Wallet -> WalletScreen(wallet = wallet, onLogOut = onLogOut, modifier = modifier)
            MainTab.Payments ->
                if (creatingPayment) {
                    CreatePaymentScreen(
                        onSubmit = { address, amount ->
                            creatingPayment = false
                            showMessage("Payment of ${amount.toPlainString()} XLM to ${shortenAddress(address)} not sent. $NOT_CONNECTED")
                        },
                        onBack = { creatingPayment = false },
                        modifier = modifier
                    )
                } else {
                    PaymentsScreen(onCreatePayment = { creatingPayment = true }, modifier = modifier)
                }
            MainTab.Transfers -> TransfersScreen(
                onTransfer = { amount, crypto -> showMessage("Transfer of ${amount.toPlainString()} ${crypto.code} not sent. $NOT_CONNECTED") },
                modifier = modifier
            )
            MainTab.Chat -> ChatScreen(
                onStartChat = { cosmoId -> showMessage("Couldn't open a chat with $cosmoId. $NOT_CONNECTED") },
                modifier = modifier
            )
            MainTab.Settings -> SettingsScreen(
                settings = settings,
                onSettingsChange = onSettingsChange,
                modifier = modifier
            )
        }
    }
}
