package com.rivers.cosmo

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rivers.cosmo.auth.AccountStore
import com.rivers.cosmo.auth.SeedPhrase
import com.rivers.cosmo.ui.auth.LogInScreen
import com.rivers.cosmo.ui.auth.SignUpScreen
import com.rivers.cosmo.ui.auth.WelcomeScreen
import com.rivers.cosmo.ui.theme.CosmoTheme
import com.rivers.cosmo.ui.wallet.WalletScreen
import com.rivers.cosmo.wallet.Wallet

class Main : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val accountStore = AccountStore(applicationContext)
        val seedPhrase = SeedPhrase(assets.open(SeedPhrase.WORD_LIST_ASSET).bufferedReader().use { it.readLines() })
        setContent {
            CosmoTheme {
                CosmoApp(accountStore, seedPhrase)
            }
        }
    }
}

private enum class AuthScreen { Welcome, SignUp, LogIn }

private const val DEVELOPER_USER_ID = "DEVELOPER"

@Composable
fun CosmoApp(accountStore: AccountStore, seedPhrase: SeedPhrase) {
    var userId by rememberSaveable { mutableStateOf(accountStore.currentUserId) }
    var screen by rememberSaveable { mutableStateOf(AuthScreen.Welcome) }
    val context = LocalContext.current
    val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    BackHandler(enabled = userId == null && screen != AuthScreen.Welcome) {
        screen = AuthScreen.Welcome
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        val signedInUserId = userId
        if (signedInUserId != null) {
            WalletScreen(
                wallet = Wallet.demo,
                onLogOut = {
                    accountStore.logOut()
                    userId = null
                    screen = AuthScreen.Welcome
                },
                modifier = modifier
            )
        } else when (screen) {
            AuthScreen.Welcome -> WelcomeScreen(
                onSignUp = { screen = AuthScreen.SignUp },
                onLogIn = { screen = AuthScreen.LogIn },
                // Developer shortcut: only offered in debug builds, never in the released app.
                onSkip = if (isDebuggable) ({ userId = DEVELOPER_USER_ID }) else null,
                modifier = modifier
            )
            AuthScreen.SignUp -> SignUpScreen(
                accountStore = accountStore,
                seedPhrase = seedPhrase,
                onSignedUp = { userId = it },
                onBack = { screen = AuthScreen.Welcome },
                modifier = modifier
            )
            AuthScreen.LogIn -> LogInScreen(
                accountStore = accountStore,
                seedPhrase = seedPhrase,
                onLoggedIn = { userId = it },
                onBack = { screen = AuthScreen.Welcome },
                modifier = modifier
            )
        }
    }
}
