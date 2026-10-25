package com.rivers.cosmo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.rivers.cosmo.chat.ChatService
import com.rivers.cosmo.settings.AppSettings
import com.rivers.cosmo.ui.chat.ChatScreen
import com.rivers.cosmo.ui.chat.ConversationScreen
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

// TODO: Payments and transfers aren't connected to the Stellar network yet,
//  so for now submitting those forms only shows a message.
private const val NOT_CONNECTED = "Cosmo isn't connected to the network yet."

/** The signed-in app: one screen per tab in the bottom navigation bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    userId: String,
    wallet: Wallet,
    chatService: ChatService,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onLogOut: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Wallet) }
    var creatingPayment by rememberSaveable { mutableStateOf(false) }
    var openContactId by rememberSaveable { mutableStateOf<String?>(null) }
    var openingChat by remember { mutableStateOf(false) }
    val chatState by chatService.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    BackHandler(enabled = creatingPayment || openContactId != null || tab != MainTab.Wallet) {
        when {
            creatingPayment -> creatingPayment = false
            openContactId != null -> openContactId = null
            else -> tab = MainTab.Wallet
        }
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
                            if (tab == item) {
                                creatingPayment = false
                                openContactId = null
                            }
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
            // Lets screens that use imePadding() avoid counting the navigation bar twice.
            .consumeWindowInsets(innerPadding)
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
            MainTab.Chat -> {
                val conversation = openContactId?.let { chatState.conversations[it] }
                if (conversation != null) {
                    ConversationScreen(
                        conversation = conversation,
                        online = chatState.connection == ChatService.Connection.Online,
                        onSend = { text -> chatService.send(conversation.contactId, text) },
                        onBack = { openContactId = null },
                        modifier = modifier
                    )
                } else {
                    ChatScreen(
                        userId = userId,
                        state = chatState,
                        opening = openingChat,
                        onStartChat = { cosmoId ->
                            openingChat = true
                            scope.launch {
                                chatService.openConversation(cosmoId)
                                    .onSuccess { openContactId = cosmoId }
                                    .onFailure { showMessage(it.message ?: "Couldn't start a chat with $cosmoId.") }
                                openingChat = false
                            }
                        },
                        onOpenConversation = { openContactId = it },
                        onReconnect = chatService::reconnect,
                        modifier = modifier
                    )
                }
            }
            MainTab.Settings -> SettingsScreen(
                settings = settings,
                onSettingsChange = onSettingsChange,
                modifier = modifier
            )
        }
    }
}
