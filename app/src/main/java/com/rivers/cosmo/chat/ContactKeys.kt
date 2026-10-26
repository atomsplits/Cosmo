package com.rivers.cosmo.chat

import android.content.Context
import com.rivers.cosmo.chat.crypto.Pgp
import org.bouncycastle.openpgp.PGPPublicKeyRing

/**
 * Remembers the public key first seen for each contact ("trust on first use"). If the server ever
 * hands out a different key for the same Cosmo ID, chat refuses to use it instead of silently
 * encrypting messages to someone else.
 */
class ContactKeys(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    sealed interface PinResult {
        data class Trusted(val publicKey: PGPPublicKeyRing, val fingerprint: String) : PinResult
        data class Changed(val pinnedFingerprint: String, val newFingerprint: String) : PinResult
    }

    fun pinned(cosmoId: String): PGPPublicKeyRing? = prefs.getString(cosmoId, null)?.let(Pgp::readPublicKey)

    /** Checks [armoredKey] against the pinned key for [cosmoId], pinning it if there wasn't one. */
    fun pin(cosmoId: String, armoredKey: String): PinResult {
        val publicKey = Pgp.readPublicKey(armoredKey)
        require(cosmoId in Pgp.userIds(publicKey)) { "The server sent a key that doesn't belong to $cosmoId." }
        val fingerprint = Pgp.fingerprint(publicKey)

        val pinned = pinned(cosmoId)
        if (pinned == null) {
            prefs.edit().putString(cosmoId, armoredKey).apply()
            return PinResult.Trusted(publicKey, fingerprint)
        }
        val pinnedFingerprint = Pgp.fingerprint(pinned)
        return if (pinnedFingerprint == fingerprint) {
            PinResult.Trusted(pinned, fingerprint)
        } else {
            PinResult.Changed(pinnedFingerprint, fingerprint)
        }
    }

    private companion object {
        const val PREFS_NAME = "cosmo_contact_keys"
    }
}
