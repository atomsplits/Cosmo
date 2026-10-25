package com.rivers.cosmo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import com.rivers.cosmo.chat.ChatService
import com.rivers.cosmo.kyc.KycController
import com.rivers.cosmo.kyc.KycException
import com.rivers.cosmo.kyc.SumsubLauncher
import com.rivers.cosmo.settings.AppSettings
import com.rivers.cosmo.ui.chat.ChatScreen
import com.rivers.cosmo.ui.chat.ConversationScreen
import com.rivers.cosmo.ui.payments.CreatePaymentScreen
import com.rivers.cosmo.ui.payments.PaymentsScreen
import com.rivers.cosmo.ui.settings.KycSection
import com.rivers.cosmo.ui.settings.PluginsSection
import com.rivers.cosmo.ui.plugins.pluginModules
import com.rivers.cosmo.ui.components.PaymentPrefill
import com.rivers.cosmo.ui.dashboard.DashboardLayoutStore
import com.rivers.cosmo.plugins.PluginEffect
import com.rivers.cosmo.plugins.PluginHost
import com.rivers.cosmo.splits.SplitsController
import com.rivers.cosmo.ui.splits.CreateSplitScreen
import com.rivers.cosmo.ui.splits.JoinSplitScreen
import com.rivers.cosmo.ui.splits.SplitDetailScreen
import com.rivers.cosmo.ui.splits.SplitsSection
import com.rivers.cosmo.plugins.WalletData
import com.rivers.cosmo.ui.settings.SettingsScreen
import com.rivers.cosmo.ui.transfers.TransfersScreen
import com.rivers.cosmo.ui.wallet.WalletDashboard
import com.rivers.cosmo.ui.wallet.walletModules
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

private const val SPLIT_CREATE = "create"
private const val SPLIT_JOIN = "join"

/** The signed-in app: one screen per tab in the bottom navigation bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    userId: String,
    wallet: WalletController,
    chatService: ChatService,
    kyc: KycController,
    plugins: PluginHost,
    splits: SplitsController,
    /** Bill split invite links opened from outside the app (QR codes, shared links). */
    incomingInvite: String?,
    onInviteHandled: () -> Unit,
    layoutStore: DashboardLayoutStore,
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
    val kycState by kyc.state.collectAsState()
    val pluginState by plugins.state.collectAsState()
    var paymentPrefill by remember { mutableStateOf<PaymentPrefill?>(null) }
    val splitsState by splits.state.collectAsState()
    // Which bill split screen the Payments tab shows: null (the list), "create", "join" or a split's ID.
    var splitRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var joinLink by rememberSaveable { mutableStateOf<String?>(null) }
    var startingKyc by remember { mutableStateOf(false) }
    val sumsub = remember(kyc) { SumsubLauncher(tokenProvider = kyc::accessToken) }
    val activity = LocalContext.current.findActivity()
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    // Bumped after each successful transfer so the form starts empty again.
    var transferFormKey by rememberSaveable { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun startVerification() {
        startingKyc = true
        scope.launch {
            try {
                val token = kyc.accessToken()
                sumsub.present(activity, token, onClosed = kyc::refresh)
            } catch (e: KycException) {
                showMessage(e.message ?: "Couldn't start verification.")
            }
            startingKyc = false
        }
    }

    // Plugins see a read-only snapshot of the wallet, refreshed whenever it changes.
    LaunchedEffect(walletState, kycState.status) {
        plugins.updateData(WalletData.json(userId, walletState, kycState.status))
    }

    // What plugins ask for: messages, or a payment the user reviews in the normal form.
    LaunchedEffect(plugins) {
        plugins.effects.collect { effect ->
            when (effect) {
                is PluginEffect.Toast -> showMessage(effect.message)
                is PluginEffect.SuggestPayment -> {
                    paymentPrefill = PaymentPrefill(effect.to, effect.amount, effect.memo)
                    sendError = null
                    tab = MainTab.Payments
                    creatingPayment = true
                }
            }
        }
    }

    LaunchedEffect(incomingInvite) {
        incomingInvite ?: return@LaunchedEffect
        tab = MainTab.Payments
        creatingPayment = false
        joinLink = incomingInvite
        splitRoute = SPLIT_JOIN
        onInviteHandled()
    }

    // Keep bill split progress current while the Payments tab is open and as new payments arrive.
    LaunchedEffect(tab, walletState.payments) {
        if (tab == MainTab.Payments) splits.refresh()
    }

    // Check verification status whenever Settings opens, e.g. after a review finished.
    LaunchedEffect(tab) {
        if (tab == MainTab.Settings) kyc.refresh()
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

    BackHandler(enabled = creatingPayment || splitRoute != null || openContactId != null || tab != MainTab.Wallet) {
        when {
            creatingPayment -> creatingPayment = false
            splitRoute != null -> splitRoute = null
            openContactId != null -> openContactId = null
            else -> tab = MainTab.Wallet
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // Transparent so the app background (Settings → Background) shows through.
        containerColor = Color.Transparent,
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
                                splitRoute = null
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
            MainTab.Wallet -> WalletDashboard(
                modules = walletModules(
                    state = walletState,
                    kycState = kycState,
                    startingKyc = startingKyc,
                    onRefresh = wallet::refresh,
                    onFund = wallet::fundWithFriendbot,
                    onAddressCopied = { showMessage("Wallet address copied.") },
                    onVerify = ::startVerification,
                    onRefreshKyc = kyc::refresh,
                    onLogOut = onLogOut
                ) + pluginModules(pluginState.widgets, onClick = plugins::click),
                layoutStore = layoutStore,
                banner = settings.banner,
                network = walletState.network,
                modifier = modifier
            )
            MainTab.Payments ->
                if (creatingPayment) {
                    // Keyed so a new suggested payment refills the form even if it's already open.
                    key(paymentPrefill) { CreatePaymentScreen(
                        prefill = paymentPrefill,
                        state = walletState,
                        sending = sending,
                        error = sendError,
                        onSubmit = { address, balance, amount, memo ->
                            send(address, balance, amount, memo) {
                                creatingPayment = false
                                paymentPrefill = null
                            }
                        },
                        onBack = {
                            creatingPayment = false
                            paymentPrefill = null
                            sendError = null
                        },
                        modifier = modifier
                    ) }
                } else when (val route = splitRoute) {
                    null -> PaymentsScreen(
                        state = walletState,
                        onCreatePayment = {
                            sendError = null
                            paymentPrefill = null
                            creatingPayment = true
                        },
                        modifier = modifier,
                        billSplits = {
                            SplitsSection(
                                state = splitsState,
                                canCreate = walletState.address != null,
                                onCreate = { splitRoute = SPLIT_CREATE },
                                onOpen = { splitRoute = it },
                                onOpenInvite = { link ->
                                    joinLink = link
                                    splitRoute = SPLIT_JOIN
                                }
                            )
                        }
                    )
                    SPLIT_CREATE -> CreateSplitScreen(
                        balances = walletState.balances,
                        onCreate = { title, asset, shares ->
                            splits.create(title, asset.code, asset.issuer, shares)
                                .onSuccess { splitRoute = it.split.id }
                                .onFailure { showMessage(it.message ?: "Couldn't create the split.") }
                        },
                        onBack = { splitRoute = null },
                        modifier = modifier
                    )
                    SPLIT_JOIN -> JoinSplitScreen(
                        link = joinLink.orEmpty(),
                        onJoin = { share ->
                            splits.join(joinLink.orEmpty(), share)
                                .onSuccess { splitRoute = it.split.id }
                                .onFailure { showMessage(it.message ?: "Couldn't join the split.") }
                        },
                        onBack = { splitRoute = null },
                        modifier = modifier
                    )
                    else -> {
                        val view = splitsState.splits.firstOrNull { it.split.id == route }
                        if (view == null) {
                            // The split was deleted (or never existed): go back to the list.
                            LaunchedEffect(route) { splitRoute = null }
                        } else {
                            SplitDetailScreen(
                                view = view,
                                inviteLink = { share -> splits.inviteLink(view.entry, share) },
                                onPay = { share ->
                                    val split = view.split
                                    val owed = split.shares[share].amount - (view.myStatus?.paid ?: BigDecimal.ZERO)
                                    paymentPrefill = PaymentPrefill(split.organizerAddress, owed.toPlainString(), split.memoFor(share), split.assetCode, split.assetIssuer)
                                    sendError = null
                                    creatingPayment = true
                                },
                                onChooseShare = { share -> splits.chooseShare(view.split.id, share) },
                                onRefresh = splits::refresh,
                                onDelete = {
                                    splits.delete(view.split.id)
                                    splitRoute = null
                                },
                                onBack = { splitRoute = null },
                                onMessage = ::showMessage,
                                modifier = modifier
                            )
                        }
                    }
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
                modifier = modifier,
                identityVerification = {
                    KycSection(
                        state = kycState,
                        starting = startingKyc,
                        onVerify = ::startVerification,
                        onRefresh = kyc::refresh
                    )
                },
                plugins = {
                    PluginsSection(
                        plugins = pluginState.plugins,
                        onInstall = plugins::install,
                        onEnabledChange = plugins::setEnabled,
                        onRemove = plugins::remove,
                        onMessage = ::showMessage
                    )
                }
            )
        }
    }
}

/** LocalContext is often a wrapper around the Activity rather than the Activity itself. */
private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No Activity in the context chain")
}
