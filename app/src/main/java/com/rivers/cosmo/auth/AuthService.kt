package com.rivers.cosmo.auth

import java.io.IOException

/** The accounts kept on this device: for logging in offline, and accounts from before Supabase. */
interface LocalAccounts {
    /** Saves the account here and starts a session for it. */
    fun register(userId: String, phrase: List<String>)

    /** Starts a session if [phrase] matches an account saved here. */
    fun logIn(userId: String, phrase: List<String>): Boolean
}

/**
 * Signing up and logging in. Accounts live in Supabase ([directory]) so they work on any phone,
 * and are also remembered on the device so logging in works without a connection. Only a value
 * derived from the recovery phrase ([PhraseVerifier]) is ever sent. Without Supabase set up
 * ([directory] null) accounts stay on the device, as before.
 *
 * Slow (key stretching and network calls): call from a background thread.
 */
class AuthService(private val local: LocalAccounts, private val directory: AccountDirectory?) {
    sealed interface Result {
        /** [online] is false when the account was only checked or saved on this device. */
        data class Success(val online: Boolean) : Result
        data class Failure(val message: String) : Result
    }

    val usesServer: Boolean get() = directory != null

    fun signUp(userId: String, phrase: List<String>): Result {
        val directory = directory ?: run {
            local.register(userId, phrase)
            return Result.Success(online = false)
        }
        val created = try {
            directory.register(userId, PhraseVerifier.derive(userId, phrase))
        } catch (e: AccountServerException) {
            return Result.Failure("The account wasn't created. ${e.message}")
        } catch (e: IOException) {
            return Result.Failure("Couldn't reach Cosmo's account server, so the account wasn't created. Check your connection and try again.")
        }
        if (!created) return Result.Failure("Someone just took that user ID. Generate a new ID and phrase.")
        local.register(userId, phrase)
        return Result.Success(online = true)
    }

    fun logIn(userId: String, phrase: List<String>): Result {
        val directory = directory
            ?: return if (local.logIn(userId, phrase)) Result.Success(online = false) else Result.Failure(WRONG)
        val verifier = PhraseVerifier.derive(userId, phrase)
        val verification = try {
            directory.verify(userId, verifier)
        } catch (e: IOException) {
            // Offline: accounts already used on this phone still open.
            return if (local.logIn(userId, phrase)) Result.Success(online = false)
            else Result.Failure((e as? AccountServerException)?.message ?: "Couldn't reach Cosmo's account server. Check your connection and try again.")
        }
        return when (verification) {
            AccountDirectory.Verification.Ok -> {
                local.register(userId, phrase) // so this phone can log in offline next time
                Result.Success(online = true)
            }
            AccountDirectory.Verification.Mismatch -> Result.Failure(WRONG)
            AccountDirectory.Verification.Unknown ->
                // An account created on this phone before accounts moved to Supabase: move it there now.
                if (local.logIn(userId, phrase)) {
                    val moved = runCatching { directory.register(userId, verifier) }.getOrDefault(false)
                    Result.Success(online = moved)
                } else {
                    Result.Failure(WRONG)
                }
        }
    }

    private companion object {
        const val WRONG = "User ID or recovery phrase is incorrect."
    }
}
