package com.rivers.cosmo.server

import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.pgpainless.PGPainless
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.key.OpenPgpFingerprint

data class RegisteredKey(val userId: String, val armoredPublicKey: String, val fingerprint: String)

/**
 * Which public key belongs to which Cosmo ID. The first key to log in with an ID claims it, and
 * from then on only that key can log in as that ID.
 *
 * TODO: Move this into Supabase (see supabase/migrations) so keys survive server restarts.
 */
interface KeyRegistry {
    fun get(userId: String): RegisteredKey?

    /** Claims [key.userId] for [key], or returns the key that already holds it. */
    fun claim(key: RegisteredKey): RegisteredKey
}

class InMemoryKeyRegistry : KeyRegistry {
    private val keys = ConcurrentHashMap<String, RegisteredKey>()
    override fun get(userId: String) = keys[userId]
    override fun claim(key: RegisteredKey): RegisteredKey = keys.putIfAbsent(key.userId, key) ?: key
}

/** Checks a log-in: the key must be named for the Cosmo ID and must have signed this connection's challenge. */
object HelloVerifier {
    private val COSMO_ID = Regex("COSMO-[A-HJKMNP-TW-Z2-9]{4}-[A-HJKMNP-TW-Z2-9]{4}")
    private const val MAX_KEY_LENGTH = 16 * 1024

    fun isCosmoId(text: String) = COSMO_ID.matches(text)

    /** Returns the verified key, or throws [IllegalArgumentException] with a reason to show the user. */
    fun verify(hello: ClientFrame.Hello, nonce: String): RegisteredKey {
        require(isCosmoId(hello.userId)) { "That isn't a valid Cosmo ID." }
        require(hello.publicKey.length <= MAX_KEY_LENGTH) { "Public key is too large." }
        val publicKey: PGPPublicKeyRing = runCatching { PGPainless.readKeyRing().publicKeyRing(hello.publicKey) }
            .getOrNull() ?: throw IllegalArgumentException("Couldn't read the public key.")
        require(hello.userId in PGPainless.inspectKeyRing(publicKey).userIds) {
            "The public key doesn't belong to ${hello.userId}."
        }
        require(signedBy(hello.signature, nonce, publicKey)) { "The log-in signature is invalid." }
        return RegisteredKey(hello.userId, hello.publicKey, OpenPgpFingerprint.of(publicKey).toString())
    }

    private fun signedBy(signedMessage: String, expectedText: String, publicKey: PGPPublicKeyRing): Boolean =
        runCatching {
            val stream = PGPainless.decryptAndOrVerify()
                .onInputStream(ByteArrayInputStream(signedMessage.toByteArray(Charsets.UTF_8)))
                .withOptions(ConsumerOptions().addVerificationCert(publicKey))
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            text == expectedText && stream.metadata.isVerifiedSignedBy(publicKey)
        }.getOrDefault(false)
}
