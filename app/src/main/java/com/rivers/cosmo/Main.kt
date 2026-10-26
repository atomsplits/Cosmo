package com.rivers.cosmo

import android.content.pm.ApplicationInfo
import android.graphics.Color
import androidx.compose.ui.graphics.Color as ComposeColor
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rivers.cosmo.auth.AccountStore
import com.rivers.cosmo.auth.AuthService
import com.rivers.cosmo.auth.SupabaseAccountDirectory
import com.rivers.cosmo.auth.SupabaseConfig
import com.rivers.cosmo.auth.SeedPhrase
import com.rivers.cosmo.chat.ChatService
import com.rivers.cosmo.anchor.AnchorTransfersController
import com.rivers.cosmo.fonts.CustomFontStore
import com.rivers.cosmo.kyc.KycController
import com.rivers.cosmo.market.MarketplaceController
import com.rivers.cosmo.plugins.PluginHost
import com.rivers.cosmo.splits.SplitInvite
import com.rivers.cosmo.splits.SplitsController
import com.rivers.cosmo.util.closeQuietly
import kotlinx.coroutines.flow.MutableStateFlow
import com.rivers.cosmo.ui.dashboard.DashboardLayoutStore
import com.rivers.cosmo.settings.AppSettings
import com.rivers.cosmo.settings.SettingsStore
import com.rivers.cosmo.settings.ThemeMode
import com.rivers.cosmo.ui.MainScreen
import com.rivers.cosmo.ui.auth.LogInScreen
import com.rivers.cosmo.ui.auth.SignUpScreen
import com.rivers.cosmo.ui.auth.WelcomeScreen
import com.rivers.cosmo.ui.background.AppBackground
import com.rivers.cosmo.ui.motion.LocalMotion
import com.rivers.cosmo.ui.motion.rememberMotion
import com.rivers.cosmo.ui.theme.CosmoTheme
import com.rivers.cosmo.ui.theme.LocalCustomFonts
import com.rivers.cosmo.ui.theme.fontFamily
import com.rivers.cosmo.ui.theme.palette
import com.rivers.cosmo.wallet.WalletController
import com.rivers.cosmo.wallet.WalletKeyStore

class Main : ComponentActivity() {
    /** A bill split invite link the app was opened with, waiting to be shown. */
    private val incomingInvite = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receive(intent)
        enableEdgeToEdge()
        val accountStore = AccountStore(applicationContext)
        // Accounts live in Supabase when it's set up (see app/build.gradle.kts), otherwise on this device only.
        val supabase = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val auth = AuthService(accountStore, supabase.takeIf { it.configured }?.let(::SupabaseAccountDirectory))
        val settingsStore = SettingsStore(applicationContext)
        val fontStore = CustomFontStore(applicationContext)
        val seedPhrase = SeedPhrase(assets.open(SeedPhrase.WORD_LIST_ASSET).bufferedReader().use { it.readLines() })
        setContent {
            var settings by remember { mutableStateOf(settingsStore.load()) }
            val customFonts by fontStore.fonts.collectAsState()
            val customFamily = settings.customFont?.let { id -> customFonts.firstOrNull { it.id == id }?.fontFamily }
            val palette = settings.themeMode.palette(settings.customColors)
            val darkTheme = when {
                palette != null -> palette.isDark
                settings.themeMode == ThemeMode.Light -> false
                settings.themeMode == ThemeMode.Dark -> true
                else -> isSystemInDarkTheme()
            }
            // Keep the status bar and navigation bar icons readable when the theme is overridden.
            DisposableEffect(darkTheme) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            CosmoTheme(darkTheme = darkTheme, fontFamily = customFamily ?: settings.font.fontFamily, palette = palette) {
                val invite by incomingInvite.collectAsState()
                // The background is drawn once behind everything; screens are transparent over it.
                // With no opaque surface underneath, text would fall back to Compose's default black,
                // so give everything the theme's text color.
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                    LocalMotion provides rememberMotion(settings.motion),
                    LocalCustomFonts provides customFonts
                ) {
                    Box(Modifier.fillMaxSize()) {
                        AppBackground(settings.background, Modifier.fillMaxSize())
                        CosmoApp(
                            accountStore = accountStore,
                            auth = auth,
                            seedPhrase = seedPhrase,
                            fontStore = fontStore,
                            settings = settings,
                            onSettingsChange = {
                                settings = it
                                settingsStore.save(it)
                            },
                            incomingInvite = invite,
                            onInviteHandled = { incomingInvite.value = null }
                        )
                    }
                }
            }
        }
    }

    // The activity is singleTop, so links opened while the app is running arrive here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        if (link.startsWith("${SplitInvite.SCHEME}://${SplitInvite.HOST}")) incomingInvite.value = link
    }
}

private enum class AuthScreen { Welcome, SignUp, LogIn }

@Composable
fun CosmoApp(
    accountStore: AccountStore,
    auth: AuthService,
    seedPhrase: SeedPhrase,
    fontStore: CustomFontStore,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    incomingInvite: String? = null,
    onInviteHandled: () -> Unit = {}
) {
    var userId by rememberSaveable { mutableStateOf(accountStore.currentUserId) }
    var screen by rememberSaveable { mutableStateOf(AuthScreen.Welcome) }
    val context = LocalContext.current
    // Developer shortcut: only offered in debug builds, never in the released app.
    val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val walletKeys = remember { WalletKeyStore(context.applicationContext) }

    val signedInUserId = userId
    if (signedInUserId != null) {
        // The wallet reloads when switching between the test and public Stellar networks.
        val wallet = remember(signedInUserId, settings.stellarNetwork) {
            WalletController(walletKeys, signedInUserId, settings.stellarNetwork)
        }
        DisposableEffect(wallet) {
            wallet.start()
            onDispose { closeQuietly("wallet") { wallet.close() } }
        }
        // One chat connection per signed-in user; it restarts if the chat server setting changes.
        val chatService = remember(signedInUserId, settings.chatServerUrl) {
            ChatService(context.applicationContext, signedInUserId, settings.chatServerUrl)
        }
        DisposableEffect(chatService) {
            chatService.start()
            onDispose { closeQuietly("chat") { chatService.close() } }
        }
        // Verification and cash-outs go through the anchors chosen in Settings, signed in with this wallet.
        val anchorDomains = settings.anchors.forNetwork(settings.stellarNetwork)
        val kyc = remember(wallet, anchorDomains) { KycController(wallet.anchors, anchorDomains) }
        DisposableEffect(kyc) {
            onDispose { closeQuietly("identity verification") { kyc.close() } }
        }
        val transfers = remember(wallet) { AnchorTransfersController(context.applicationContext, wallet, settings.stellarNetwork) }
        // Runs whenever someone's signed in, so a seller's phone can deliver sales without the tab open.
        val market = remember(wallet, chatService) {
            MarketplaceController(context.applicationContext, signedInUserId, chatService, wallet, settings.stellarNetwork)
        }
        DisposableEffect(market) {
            onDispose { closeQuietly("marketplace") { market.close() } }
        }
        val plugins = remember(signedInUserId) { PluginHost(context.applicationContext) }
        DisposableEffect(plugins) {
            plugins.start()
            onDispose { closeQuietly("plugins") { plugins.close() } }
        }
        val layoutStore = remember { DashboardLayoutStore(context.applicationContext) }
        val splits = remember(signedInUserId, wallet) { SplitsController(context.applicationContext, signedInUserId, wallet) }
        DisposableEffect(splits) {
            onDispose { closeQuietly("bill splits") { splits.close() } }
        }
        MainScreen(
            userId = signedInUserId,
            plugins = plugins,
            splits = splits,
            // Invites wait until someone is signed in, then open on the Payments tab.
            incomingInvite = incomingInvite,
            onInviteHandled = onInviteHandled,
            layoutStore = layoutStore,
            wallet = wallet,
            chatService = chatService,
            kyc = kyc,
            transfers = transfers,
            fontStore = fontStore,
            market = market,
            settings = settings,
            onSettingsChange = onSettingsChange,
            onLogOut = {
                accountStore.logOut()
                userId = null
                screen = AuthScreen.Welcome
            }
        )
    } else {
        AuthFlow(
            screen = screen,
            onScreenChange = { screen = it },
            accountStore = accountStore,
            auth = auth,
            walletKeys = walletKeys,
            seedPhrase = seedPhrase,
            allowScreenCapture = settings.allowScreenCapture,
            onSignedIn = { userId = it },
            onDeveloperSkip = if (isDebuggable) {
                {
                    // The developer account gets its own random wallet, kept on this device only.
                    val developerId = accountStore.developerUserId()
                    if (!walletKeys.contains(developerId)) walletKeys.save(developerId, seedPhrase.generate())
                    userId = developerId
                }
            } else {
                null
            }
        )
    }
}

@Composable
private fun AuthFlow(
    screen: AuthScreen,
    onScreenChange: (AuthScreen) -> Unit,
    accountStore: AccountStore,
    auth: AuthService,
    walletKeys: WalletKeyStore,
    seedPhrase: SeedPhrase,
    allowScreenCapture: Boolean,
    onSignedIn: (userId: String) -> Unit,
    onDeveloperSkip: (() -> Unit)?
) {
    BackHandler(enabled = screen != AuthScreen.Welcome) {
        onScreenChange(AuthScreen.Welcome)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ComposeColor.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) { innerPadding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (screen) {
            AuthScreen.Welcome -> WelcomeScreen(
                onSignUp = { onScreenChange(AuthScreen.SignUp) },
                onLogIn = { onScreenChange(AuthScreen.LogIn) },
                onSkip = onDeveloperSkip,
                modifier = modifier
            )
            AuthScreen.SignUp -> SignUpScreen(
                accountStore = accountStore,
                auth = auth,
                walletKeys = walletKeys,
                seedPhrase = seedPhrase,
                allowScreenCapture = allowScreenCapture,
                onSignedUp = onSignedIn,
                onBack = { onScreenChange(AuthScreen.Welcome) },
                modifier = modifier
            )
            AuthScreen.LogIn -> LogInScreen(
                auth = auth,
                walletKeys = walletKeys,
                seedPhrase = seedPhrase,
                allowScreenCapture = allowScreenCapture,
                onLoggedIn = onSignedIn,
                onBack = { onScreenChange(AuthScreen.Welcome) },
                modifier = modifier
            )
        }
    }
}
