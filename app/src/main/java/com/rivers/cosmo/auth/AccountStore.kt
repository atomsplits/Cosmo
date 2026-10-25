package com.rivers.cosmo.auth

import android.content.Context
import android.os.Build
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Keeps accounts on this device. The recovery phrase itself is never stored: only a salted
 * PBKDF2 hash of it, which is enough to check a log-in but not to recover the phrase.
 *
 * [register] and [logIn] are deliberately slow (key stretching), so call them off the main thread.
 */
class AccountStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val currentUserId: String?
        get() = prefs.getString(KEY_SESSION, null)

    /** A fresh random user ID that no account on this device already uses. */
    fun newUserId(): String {
        var userId: String
        do {
            userId = UserIdGenerator.generate()
        } while (prefs.contains(accountKey(userId)))
        return userId
    }

    fun register(userId: String, phrase: List<String>) {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val algorithm = preferredAlgorithm()
        val record = listOf(algorithm, encode(salt), encode(hash(algorithm, phrase, salt))).joinToString(":")
        prefs.edit()
            .putString(accountKey(userId), record)
            .putString(KEY_SESSION, userId)
            .apply()
    }

    /** Returns true and starts a session when [phrase] matches the account for [userId]. */
    fun logIn(userId: String, phrase: List<String>): Boolean {
        val record = prefs.getString(accountKey(userId), null) ?: return false
        val (algorithm, salt, expected) = record.split(":")
        val matches = MessageDigest.isEqual(hash(algorithm, phrase, decode(salt)), decode(expected))
        if (matches) prefs.edit().putString(KEY_SESSION, userId).apply()
        return matches
    }

    /**
     * A Cosmo ID for the debug-only "skip login" button, kept the same on this device so chat keys
     * and contacts stay attached to it.
     */
    fun developerUserId(): String =
        prefs.getString(KEY_DEVELOPER_ID, null) ?: newUserId().also { prefs.edit().putString(KEY_DEVELOPER_ID, it).apply() }

    fun logOut() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private fun hash(algorithm: String, phrase: List<String>, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(phrase.joinToString(" ").toCharArray(), salt, ITERATIONS, HASH_BITS)
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val PREFS_NAME = "cosmo_accounts"
        const val KEY_SESSION = "session_user_id"
        const val KEY_DEVELOPER_ID = "developer_user_id"
        const val SALT_BYTES = 16
        const val HASH_BITS = 256
        const val ITERATIONS = 100_000

        fun accountKey(userId: String) = "account_$userId"

        // PBKDF2 with SHA-256 is only built in from Android 8.0; older devices fall back to SHA-1.
        // The algorithm is saved with each account, so either kind keeps working after an OS update.
        fun preferredAlgorithm() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
    }
}
