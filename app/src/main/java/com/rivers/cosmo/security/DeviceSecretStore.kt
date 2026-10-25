package com.rivers.cosmo.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saves secrets as files encrypted with an AES key that lives in the Android Keystore, so they
 * can't be read even by copying the app's files off the phone. Exclude [directory] from backups:
 * a copy could never be decrypted on another device.
 */
class DeviceSecretStore(context: Context, directory: String) {
    private val directory = File(context.filesDir, directory).apply { mkdirs() }

    fun contains(name: String): Boolean = File(directory, name).exists()

    fun read(name: String): ByteArray? {
        val file = File(directory, name)
        return if (file.exists()) decrypt(file.readBytes()) else null
    }

    fun write(name: String, secret: ByteArray) {
        // Write to a temporary file first so a crash can't leave a half-written secret behind.
        val temporary = File(directory, "$name.tmp")
        temporary.writeBytes(encrypt(secret))
        check(temporary.renameTo(File(directory, name))) { "Couldn't save $name" }
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
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        // Kept from when only chat keys were stored, so existing chat keys still decrypt.
        const val KEY_ALIAS = "cosmo_chat_key_wrapper"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
