package com.rivers.cosmo

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rivers.cosmo.auth.AccountStore
import com.rivers.cosmo.auth.SeedPhrase
import com.rivers.cosmo.chat.ChatService
import com.rivers.cosmo.kyc.KycController
import com.rivers.cosmo.settings.AppSettings
import com.rivers.cosmo.settings.SettingsStore
import com.rivers.cosmo.settings.ThemeMode
import com.rivers.cosmo.ui.MainScreen
import com.rivers.cosmo.ui.auth.LogInScreen
import com.rivers.cosmo.ui.auth.SignUpScreen
import com.rivers.cosmo.ui.auth.WelcomeScreen
import com.rivers.cosmo.ui.theme.CosmoTheme
import com.rivers.cosmo.ui.theme.fontFamily
import com.rivers.cosmo.ui.theme.palette
import com.rivers.cosmo.wallet.WalletController
import com.rivers.cosmo.wallet.WalletKeyStore

class Main : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val accountStore = AccountStore(applicationContext)
        val settingsStore = SettingsStore(applicationContext)
        val seedPhrase = SeedPhrase(assets.open(SeedPhrase.WORD_LIST_ASSET).bufferedReader().use { it.readLines() })
        setContent {
            var settings by remember { mutableStateOf(settingsStore.load()) }
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
            CosmoTheme(darkTheme = darkTheme, fontFamily = settings.font.fontFamily, palette = palette) {
                CosmoApp(
                    accountStore = accountStore,
                    seedPhrase = seedPhrase,
                    settings = settings,
                    onSettingsChange = {
                        settings = it
                        settingsStore.save(it)
                    }
                )
            }
        }
    }
}

private enum class AuthScreen { Welcome, SignUp, LogIn }

@Composable
fun CosmoApp(
    accountStore: AccountStore,
    seedPhrase: SeedPhrase,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
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
            onDispose { wallet.close() }
        }
        // One chat connection per signed-in user; it restarts if the chat server setting changes.
        val chatService = remember(signedInUserId, settings.chatServerUrl) {
            ChatService(context.applicationContext, signedInUserId, settings.chatServerUrl)
        }
        DisposableEffect(chatService) {
            chatService.start()
            onDispose { chatService.close() }
        }
        val kyc = remember(signedInUserId, settings.chatServerUrl) {
            KycController(context.applicationContext, signedInUserId, settings.chatServerUrl)
        }
        DisposableEffect(kyc) {
            onDispose { kyc.close() }
        }
        MainScreen(
            userId = signedInUserId,
            wallet = wallet,
            chatService = chatService,
            kyc = kyc,
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
            walletKeys = walletKeys,
            seedPhrase = seedPhrase,
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
    walletKeys: WalletKeyStore,
    seedPhrase: SeedPhrase,
    onSignedIn: (userId: String) -> Unit,
    onDeveloperSkip: (() -> Unit)?
) {
    BackHandler(enabled = screen != AuthScreen.Welcome) {
        onScreenChange(AuthScreen.Welcome)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
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
                walletKeys = walletKeys,
                seedPhrase = seedPhrase,
                onSignedUp = onSignedIn,
                onBack = { onScreenChange(AuthScreen.Welcome) },
                modifier = modifier
            )
            AuthScreen.LogIn -> LogInScreen(
                accountStore = accountStore,
                walletKeys = walletKeys,
                seedPhrase = seedPhrase,
                onLoggedIn = onSignedIn,
                onBack = { onScreenChange(AuthScreen.Welcome) },
                modifier = modifier
            )
        }
    }
}
