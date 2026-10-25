package com.rivers.cosmo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.auth.AccountStore
import com.rivers.cosmo.auth.SeedPhrase
import com.rivers.cosmo.ui.auth.LogInScreen
import com.rivers.cosmo.ui.auth.SignUpScreen
import com.rivers.cosmo.ui.auth.WelcomeScreen
import com.rivers.cosmo.ui.theme.CosmoTheme

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

@Composable
fun CosmoApp(accountStore: AccountStore, seedPhrase: SeedPhrase) {
    var userId by rememberSaveable { mutableStateOf(accountStore.currentUserId) }
    var screen by rememberSaveable { mutableStateOf(AuthScreen.Welcome) }

    BackHandler(enabled = userId == null && screen != AuthScreen.Welcome) {
        screen = AuthScreen.Welcome
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        val signedInUserId = userId
        if (signedInUserId != null) {
            HomeScreen(
                userId = signedInUserId,
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

@Composable
fun HomeScreen(userId: String, onLogOut: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Greeting(name = "Cosmo Wallet")
        Spacer(Modifier.height(8.dp))
        Text("Signed in as $userId", fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onLogOut, modifier = Modifier.fillMaxWidth()) {
            Text("Log out")
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Welcome back, $name!",
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CosmoTheme {
        Greeting("Android")
    }
}
