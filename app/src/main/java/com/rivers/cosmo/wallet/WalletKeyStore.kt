package com.rivers.cosmo.wallet

import android.content.Context
import com.rivers.cosmo.security.DeviceSecretStore
import org.stellar.sdk.KeyPair

/**
 * Keeps each user's recovery phrase on this device, encrypted by [DeviceSecretStore], so the app
 * can sign Stellar transactions without asking for the 12 words every time. The phrase is saved
 * when the user signs up or logs in, because that's the only time the app sees it.
 */
class WalletKeyStore(context: Context) {
    private val store = DeviceSecretStore(context, DIRECTORY)

    fun save(cosmoId: String, phrase: List<String>) {
        store.write(fileName(cosmoId), phrase.joinToString(" ").toByteArray(Charsets.UTF_8))
    }

    fun contains(cosmoId: String): Boolean = store.contains(fileName(cosmoId))

    /** The user's Stellar key pair, or null if their phrase isn't saved on this device. Slow-ish: call off the main thread. */
    fun keyPair(cosmoId: String): KeyPair? =
        store.read(fileName(cosmoId))?.let { StellarKeys.fromPhrase(it.toString(Charsets.UTF_8).split(" ")) }

    private fun fileName(cosmoId: String) = "$cosmoId.phrase"

    private companion object {
        const val DIRECTORY = "wallet" // Excluded from backups in res/xml/*_rules.xml.
    }
}
