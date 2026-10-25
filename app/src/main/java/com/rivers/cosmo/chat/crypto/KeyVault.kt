package com.rivers.cosmo.chat.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.openpgp.PGPSecretKeyRing

/**
 * Stores each user's PGP secret key on this device only. The key file is encrypted with an AES key
 * that lives in the Android Keystore, so it can't be read even by copying the app's files off the phone.
 *
 * Generating and loading keys is slow-ish, so call [loadOrCreate] off the main thread.
 */
class KeyVault(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /** The secret key for [cosmoId], generated and saved the first time it's needed. */
    fun loadOrCreate(cosmoId: String): PGPSecretKeyRing {
        val file = File(directory, "$cosmoId.key")
        if (file.exists()) {
            return Pgp.readSecretKey(decrypt(file.readBytes()).toString(Charsets.UTF_8))
        }
        val key = Pgp.generateKey(cosmoId)
        // Write to a temporary file first so a crash can't leave a half-written key behind.
        val temporary = File(directory, "$cosmoId.key.tmp")
        temporary.writeBytes(encrypt(Pgp.armor(key).toByteArray(Charsets.UTF_8)))
        check(temporary.renameTo(file)) { "Couldn't save the chat key" }
        return key
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(stored: ByteArray): ByteArray {
        val iv = stored.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val DIRECTORY = "pgp" // Excluded from backups in res/xml/*_rules.xml.
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "cosmo_chat_key_wrapper"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
