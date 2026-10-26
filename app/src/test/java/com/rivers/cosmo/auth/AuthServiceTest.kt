package com.rivers.cosmo.auth

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthServiceTest {
    private val phrase = "abandon ability able about above absent absorb abstract absurd abuse access accident".split(" ")
    private val otherPhrase = phrase.reversed()
    private val id = "COSMO-ABCD-EFGH"

    @Test
    fun derivesTheSameVerifierAsStandardPbkdf2() {
        // Reference values from Python: hashlib.pbkdf2_hmac("sha256", phrase, b"cosmo-auth-v1:" + id, 100000, 32)
        assertEquals("8f027bb586ddb27ccc705704d5317fb7f141870b0f96cc5176369992a89e58b8", PhraseVerifier.derive(id, phrase))
        assertEquals("96d03ce773b10ec5464f0ca4b927900fcc4eb8500a1c189a56a210cd88cad746", PhraseVerifier.derive("COSMO-WXYZ-2345", phrase))
    }

    @Test
    fun theVerifierRevealsNothingOfThePhrase() {
        val verifier = PhraseVerifier.derive(id, phrase)
        assertTrue(Regex("[0-9a-f]{64}").matches(verifier))
        phrase.forEach { assertFalse(verifier.contains(it)) }
        assertNotEquals(verifier, PhraseVerifier.derive(id, otherPhrase))
    }

    /** Supabase's accounts table, as the SQL functions behave, storing only what the app sends. */
    private class FakeDirectory : AccountDirectory {
        val stored = mutableMapOf<String, String>()
        var offline = false
        val received = mutableListOf<String>()

        override fun register(cosmoId: String, verifier: String): Boolean {
            if (offline) throw IOException("offline")
            received += verifier
            return stored.putIfAbsent(cosmoId, verifier) == null
        }

        override fun verify(cosmoId: String, verifier: String): AccountDirectory.Verification {
            if (offline) throw IOException("offline")
            received += verifier
            val expected = stored[cosmoId] ?: return AccountDirectory.Verification.Unknown
            return if (expected == verifier) AccountDirectory.Verification.Ok else AccountDirectory.Verification.Mismatch
        }
    }

    private class FakeLocal : LocalAccounts {
        val accounts = mutableMapOf<String, List<String>>()
        var session: String? = null
        override fun register(userId: String, phrase: List<String>) {
            accounts[userId] = phrase
            session = userId
        }
        override fun logIn(userId: String, phrase: List<String>): Boolean =
            (accounts[userId] == phrase).also { if (it) session = userId }
    }

    @Test
    fun signsUpInSupabaseWithoutSendingThePhrase() {
        val server = FakeDirectory()
        val local = FakeLocal()
        assertEquals(AuthService.Result.Success(online = true), AuthService(local, server).signUp(id, phrase))
        assertEquals(PhraseVerifier.derive(id, phrase), server.stored[id])
        server.received.forEach { sent -> phrase.forEach { word -> assertFalse(sent.contains(word)) } }
        assertEquals(id, local.session)
    }

    @Test
    fun logsInOnAnotherPhone() {
        val server = FakeDirectory()
        AuthService(FakeLocal(), server).signUp(id, phrase)
        val newPhone = FakeLocal()
        val auth = AuthService(newPhone, server)
        assertEquals(AuthService.Result.Success(online = true), auth.logIn(id, phrase))
        assertEquals(id, newPhone.session)
        assertTrue(auth.logIn(id, otherPhrase) is AuthService.Result.Failure)
        assertTrue(auth.logIn("COSMO-ZZZZ-ZZZZ", phrase) is AuthService.Result.Failure)
    }

    @Test
    fun refusesATakenId() {
        val server = FakeDirectory()
        AuthService(FakeLocal(), server).signUp(id, phrase)
        val local = FakeLocal()
        val result = AuthService(local, server).signUp(id, otherPhrase)
        assertTrue(result is AuthService.Result.Failure)
        assertEquals(PhraseVerifier.derive(id, phrase), server.stored[id]) // the first account is untouched
        assertTrue(local.accounts.isEmpty())
    }

    @Test
    fun worksOfflineForAccountsAlreadyOnThePhone() {
        val server = FakeDirectory()
        val local = FakeLocal()
        val auth = AuthService(local, server)
        auth.signUp(id, phrase)
        server.offline = true
        assertEquals(AuthService.Result.Success(online = false), auth.logIn(id, phrase))
        assertTrue(auth.logIn(id, otherPhrase) is AuthService.Result.Failure)
        // A new account can't be created without the server.
        assertTrue(auth.signUp("COSMO-WXYZ-2345", phrase) is AuthService.Result.Failure)
    }

    @Test
    fun movesOlderPhoneOnlyAccountsToSupabase() {
        val server = FakeDirectory()
        val local = FakeLocal().apply { accounts[id] = phrase }
        assertEquals(AuthService.Result.Success(online = true), AuthService(local, server).logIn(id, phrase))
        assertEquals(PhraseVerifier.derive(id, phrase), server.stored[id])
    }

    @Test
    fun saysWhenSupabaseIsMissingTheMigration() {
        val missing = AccountServerException(
            SupabaseAccountDirectory.describeError(404, """{"code":"PGRST202","message":"Could not find the function public.register_account(p_cosmo_id, p_verifier) in the schema cache"}""")
        )
        val server = object : AccountDirectory {
            override fun register(cosmoId: String, verifier: String): Boolean = throw missing
            override fun verify(cosmoId: String, verifier: String): AccountDirectory.Verification = throw missing
        }
        val result = AuthService(FakeLocal(), server).signUp(id, phrase) as AuthService.Result.Failure
        assertTrue(result.message, result.message.contains("supabase/migrations"))
        val login = AuthService(FakeLocal(), server).logIn(id, phrase) as AuthService.Result.Failure
        assertTrue(login.message, login.message.contains("supabase/migrations"))
    }

    @Test
    fun explainsSupabaseErrors() {
        assertTrue(SupabaseAccountDirectory.describeError(401, """{"message":"Invalid API key"}""").contains("supabase.anonKey"))
        assertEquals(
            "Cosmo's account server returned an error (500): boom",
            SupabaseAccountDirectory.describeError(500, """{"code":"XX000","message":"boom"}""")
        )
    }

    @Test
    fun keepsAccountsOnThePhoneWithoutSupabase() {
        val local = FakeLocal()
        val auth = AuthService(local, directory = null)
        assertFalse(auth.usesServer)
        assertEquals(AuthService.Result.Success(online = false), auth.signUp(id, phrase))
        assertEquals(AuthService.Result.Success(online = false), auth.logIn(id, phrase))
    }
}
