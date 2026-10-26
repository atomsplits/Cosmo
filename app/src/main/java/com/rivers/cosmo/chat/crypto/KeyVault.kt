package com.rivers.cosmo.chat.crypto

import android.content.Context
import com.rivers.cosmo.security.DeviceSecretStore
import org.bouncycastle.openpgp.PGPSecretKeyRing

/**
 * Stores each user's PGP secret key on this device only, encrypted by [DeviceSecretStore].
 *
 * Generating and loading keys is slow-ish, so call [loadOrCreate] off the main thread.
 */
class KeyVault(context: Context) {
    private val store = DeviceSecretStore(context, DIRECTORY)

    /** The secret key for [cosmoId], generated and saved the first time it's needed. */
    fun loadOrCreate(cosmoId: String): PGPSecretKeyRing {
        val name = "$cosmoId.key"
        store.read(name)?.let { return Pgp.readSecretKey(it.toString(Charsets.UTF_8)) }
        val key = Pgp.generateKey(cosmoId)
        store.write(name, Pgp.armor(key).toByteArray(Charsets.UTF_8))
        return key
    }

    private companion object {
        const val DIRECTORY = "pgp" // Excluded from backups in res/xml/*_rules.xml.
    }
}
