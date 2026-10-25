package com.rivers.cosmo.market

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A digital item: some text (a code, a link, a message) or a small file. */
data class Goods(val kind: String, val bytes: ByteArray, val fileName: String? = null, val mimeType: String? = null) {
    val text: String get() = String(bytes, Charsets.UTF_8)
}

/** Seals goods with a sale's 32-byte key (AES-256-GCM), and opens them once the key is revealed. */
object GoodsCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun seal(goods: Goods, key: ByteArray): SealedGoods {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce)) }
        return SealedGoods(goods.kind, goods.fileName, goods.mimeType, nonce, cipher.doFinal(goods.bytes))
    }

    /** Throws if [key] is wrong or the goods were tampered with (GCM checks both). */
    fun open(sealed: SealedGoods, key: ByteArray): Goods {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.nonce))
        }
        return Goods(sealed.kind, cipher.doFinal(sealed.ciphertext), sealed.fileName, sealed.mimeType)
    }
}
