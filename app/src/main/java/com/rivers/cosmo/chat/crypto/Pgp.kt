package com.rivers.cosmo.chat.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.pgpainless.PGPainless
import org.pgpainless.algorithm.DocumentSignatureType
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.encryption_signing.SigningOptions
import org.pgpainless.key.OpenPgpFingerprint
import org.pgpainless.key.protection.SecretKeyRingProtector

/**
 * OpenPGP operations for chat. Keys are modern Curve25519 keys (Ed25519 for signing, X25519 for
 * encryption), with the user's Cosmo ID as the key's user ID.
 *
 * Secret keys have no passphrase of their own: on the device they are encrypted by [KeyVault]
 * with a key held in the Android Keystore instead.
 */
object Pgp {

    data class Decrypted(val text: String, val verified: Boolean)

    fun generateKey(cosmoId: String): PGPSecretKeyRing =
        PGPainless.generateKeyRing().modernKeyRing(cosmoId)

    fun publicKeyOf(secretKey: PGPSecretKeyRing): PGPPublicKeyRing = PGPainless.extractCertificate(secretKey)

    fun armor(secretKey: PGPSecretKeyRing): String = PGPainless.asciiArmor(secretKey)

    fun armor(publicKey: PGPPublicKeyRing): String = PGPainless.asciiArmor(publicKey)

    fun readSecretKey(armored: String): PGPSecretKeyRing =
        PGPainless.readKeyRing().secretKeyRing(armored) ?: throw IllegalArgumentException("Not a secret key")

    fun readPublicKey(armored: String): PGPPublicKeyRing =
        PGPainless.readKeyRing().publicKeyRing(armored) ?: throw IllegalArgumentException("Not a public key")

    /** The key's fingerprint, grouped for reading aloud when two people compare keys. */
    fun fingerprint(publicKey: PGPPublicKeyRing): String = OpenPgpFingerprint.of(publicKey).prettyPrint()

    fun userIds(publicKey: PGPPublicKeyRing): List<String> = PGPainless.inspectKeyRing(publicKey).userIds

    /** Encrypts [text] so both [recipient] and the sender can read it, and signs it with [senderKey]. */
    fun encryptAndSign(text: String, recipient: PGPPublicKeyRing, senderKey: PGPSecretKeyRing): String {
        val out = ByteArrayOutputStream()
        val options = ProducerOptions.signAndEncrypt(
            EncryptionOptions.encryptCommunications()
                .addRecipient(recipient)
                .addRecipient(publicKeyOf(senderKey)),
            SigningOptions().addInlineSignature(
                SecretKeyRingProtector.unprotectedKeys(),
                senderKey,
                DocumentSignatureType.CANONICAL_TEXT_DOCUMENT
            )
        ).setAsciiArmor(true)
        PGPainless.encryptAndOrSign().onOutputStream(out).withOptions(options).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Decrypts a message with [ownKey]. It counts as verified only if it carries a valid signature
     * from [senderPublicKey]; pass null when the sender's key isn't known yet.
     */
    fun decryptAndVerify(armored: String, ownKey: PGPSecretKeyRing, senderPublicKey: PGPPublicKeyRing?): Decrypted {
        val options = ConsumerOptions().addDecryptionKey(ownKey)
        senderPublicKey?.let { options.addVerificationCert(it) }
        val stream = PGPainless.decryptAndOrVerify()
            .onInputStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8)))
            .withOptions(options)
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val metadata = stream.metadata
        val verified = senderPublicKey != null && metadata.isVerifiedSignedBy(senderPublicKey)
        return Decrypted(text, verified)
    }

    /** Signs the server's log-in challenge, proving this device holds the secret key. */
    fun sign(text: String, key: PGPSecretKeyRing): String {
        val out = ByteArrayOutputStream()
        val options = ProducerOptions.sign(
            SigningOptions().addInlineSignature(
                SecretKeyRingProtector.unprotectedKeys(),
                key,
                DocumentSignatureType.BINARY_DOCUMENT
            )
        ).setAsciiArmor(true)
        PGPainless.encryptAndOrSign().onOutputStream(out).withOptions(options).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
