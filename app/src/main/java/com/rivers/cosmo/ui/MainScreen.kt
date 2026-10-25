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
import androidx.compose.runtime.key
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
import com.rivers.cosmo.wallet.WalletBalance
import com.rivers.cosmo.wallet.WalletController
import com.rivers.cosmo.wallet.formatAmount
import com.rivers.cosmo.wallet.shortenAddress
import java.math.BigDecimal
import kotlinx.coroutines.launch

private enum class MainTab(val label: String, val icon: ImageVector) {
    Wallet("Wallet", Icons.Filled.AccountBalanceWallet),
    Payments("Payments", Icons.Filled.Payments),
    Transfers("Transfers", Icons.Filled.SwapHoriz),
    Chat("Chat", Icons.AutoMirrored.Filled.Chat),
    Settings("Settings", Icons.Filled.Settings)
}

/** The signed-in app: one screen per tab in the bottom navigation bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    userId: String,
    wallet: WalletController,
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
    val walletState by wallet.state.collectAsState()
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    // Bumped after each successful transfer so the form starts empty again.
    var transferFormKey by rememberSaveable { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun send(address: String, balance: WalletBalance, amount: BigDecimal, memo: String?, onSent: () -> Unit) {
        sending = true
        sendError = null
        scope.launch {
            wallet.send(address, balance, amount, memo)
                .onSuccess {
                    onSent()
                    showMessage("Sent ${formatAmount(amount, balance.code)} to ${shortenAddress(address)}.")
                }
                .onFailure { sendError = it.message }
            sending = false
        }
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
                            sendError = null
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
            MainTab.Wallet -> WalletScreen(
                state = walletState,
                onRefresh = wallet::refresh,
                onFund = wallet::fundWithFriendbot,
                onAddressCopied = { showMessage("Wallet address copied.") },
                onLogOut = onLogOut,
                modifier = modifier
            )
            MainTab.Payments ->
                if (creatingPayment) {
                    CreatePaymentScreen(
                        state = walletState,
                        sending = sending,
                        error = sendError,
                        onSubmit = { address, balance, amount, memo ->
                            send(address, balance, amount, memo) { creatingPayment = false }
                        },
                        onBack = {
                            creatingPayment = false
                            sendError = null
                        },
                        modifier = modifier
                    )
                } else {
                    PaymentsScreen(
                        state = walletState,
                        onCreatePayment = {
                            sendError = null
                            creatingPayment = true
                        },
                        modifier = modifier
                    )
                }
            MainTab.Transfers -> key(transferFormKey) {
                TransfersScreen(
                    state = walletState,
                    sending = sending,
                    error = sendError,
                    onTransfer = { address, balance, amount, memo ->
                        send(address, balance, amount, memo) { transferFormKey++ }
                    },
                    modifier = modifier
                )
            }
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
