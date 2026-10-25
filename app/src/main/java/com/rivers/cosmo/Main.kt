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
import com.rivers.cosmo.wallet.Wallet

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

private const val DEVELOPER_USER_ID = "DEVELOPER"

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

    if (userId != null) {
        MainScreen(
            wallet = Wallet.demo,
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
            seedPhrase = seedPhrase,
            onSignedIn = { userId = it },
            onDeveloperSkip = if (isDebuggable) ({ userId = DEVELOPER_USER_ID }) else null
        )
    }
}

@Composable
private fun AuthFlow(
    screen: AuthScreen,
    onScreenChange: (AuthScreen) -> Unit,
    accountStore: AccountStore,
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
                seedPhrase = seedPhrase,
                onSignedUp = onSignedIn,
                onBack = { onScreenChange(AuthScreen.Welcome) },
                modifier = modifier
            )
            AuthScreen.LogIn -> LogInScreen(
                accountStore = accountStore,
                seedPhrase = seedPhrase,
                onLoggedIn = onSignedIn,
                onBack = { onScreenChange(AuthScreen.Welcome) },
                modifier = modifier
            )
        }
    }
}
