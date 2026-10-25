package com.rivers.cosmo.ui.auth

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.auth.AccountStore
import com.rivers.cosmo.auth.SeedPhrase
import com.rivers.cosmo.auth.UserIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WelcomeScreen(onSignUp: () -> Unit, onLogIn: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Cosmo", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "No usernames or passwords. Your account is a user ID and a 12-word recovery phrase.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(48.dp))
        Button(onClick = onSignUp, modifier = Modifier.fillMaxWidth()) {
            Text("Sign up")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onLogIn, modifier = Modifier.fillMaxWidth()) {
            Text("Log in")
        }
    }
}

@Composable
fun SignUpScreen(
    accountStore: AccountStore,
    seedPhrase: SeedPhrase,
    onSignedUp: (userId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    SecureScreen()
    var userId by rememberSaveable { mutableStateOf(accountStore.newUserId()) }
    var phrase by rememberSaveable { mutableStateOf(seedPhrase.generate().joinToString(" ")) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack, enabled = !working) { Text("Back") }
        Text("Create your account", style = MaterialTheme.typography.headlineMedium)

        Text("Your user ID", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                userId,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(16.dp)
            )
        }

        Text("Your recovery phrase", style = MaterialTheme.typography.titleMedium)
        Text(
            "Write these 12 words down in order and keep them somewhere safe. They are your " +
                "password: anyone who has them can open your account, and if you lose them " +
                "they can't be recovered.",
            style = MaterialTheme.typography.bodyMedium
        )
        SeedPhraseGrid(phrase.split(" "))

        OutlinedButton(
            onClick = {
                userId = accountStore.newUserId()
                phrase = seedPhrase.generate().joinToString(" ")
                confirmed = false
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Generate new ID and phrase")
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = confirmed, role = Role.Checkbox, onValueChange = { confirmed = it })
        ) {
            Checkbox(checked = confirmed, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text("I've written down my user ID and recovery phrase")
        }

        Button(
            onClick = {
                working = true
                scope.launch {
                    withContext(Dispatchers.Default) { accountStore.register(userId, phrase.split(" ")) }
                    onSignedUp(userId)
                }
            },
            enabled = confirmed && !working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (working) "Creating account…" else "Create account")
        }
    }
}

@Composable
fun LogInScreen(
    accountStore: AccountStore,
    seedPhrase: SeedPhrase,
    onLoggedIn: (userId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    SecureScreen()
    var userIdInput by rememberSaveable { mutableStateOf("") }
    var phraseInput by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val words = SeedPhrase.normalize(phraseInput)

    fun submit() {
        error = when (val result = seedPhrase.validate(words)) {
            is SeedPhrase.Validation.WrongLength ->
                "Your recovery phrase must be ${SeedPhrase.WORD_COUNT} words (you entered ${result.count})."
            is SeedPhrase.Validation.UnknownWords ->
                "These aren't recovery phrase words: ${result.words.joinToString(", ")}"
            SeedPhrase.Validation.BadChecksum ->
                "That recovery phrase isn't valid. Check the words and their order."
            SeedPhrase.Validation.Valid -> null
        }
        if (error != null) return

        val userId = UserIdGenerator.normalize(userIdInput)
        working = true
        scope.launch {
            val ok = withContext(Dispatchers.Default) { accountStore.logIn(userId, words) }
            working = false
            if (ok) {
                onLoggedIn(userId)
            } else {
                error = "User ID or recovery phrase is incorrect. Accounts are stored on the " +
                    "device they were created on."
            }
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack, enabled = !working) { Text("Back") }
        Text("Log in", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = userIdInput,
            onValueChange = { userIdInput = it; error = null },
            label = { Text("User ID") },
            placeholder = { Text("COSMO-XXXX-XXXX") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = phraseInput,
            onValueChange = { phraseInput = it; error = null },
            label = { Text("12-word recovery phrase") },
            supportingText = { Text("${words.size} / ${SeedPhrase.WORD_COUNT} words") },
            minLines = 3,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { submit() },
            enabled = !working && userIdInput.isNotBlank() && words.size == SeedPhrase.WORD_COUNT,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (working) "Logging in…" else "Log in")
        }
    }
}

@Composable
private fun SeedPhraseGrid(words: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            words.withIndex().chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (index, word) ->
                        Text(
                            "${index + 1}. $word",
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** Blocks screenshots and screen recording while a recovery phrase is on screen. */
@Composable
private fun SecureScreen() {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
