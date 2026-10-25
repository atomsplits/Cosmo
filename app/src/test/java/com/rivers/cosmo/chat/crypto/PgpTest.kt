package com.rivers.cosmo.chat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PgpTest {
    private val alice = Pgp.generateKey("COSMO-AAAA-AAAA")
    private val bob = Pgp.generateKey("COSMO-BBBB-BBBB")
    private val mallory = Pgp.generateKey("COSMO-MMMM-MMMM")

    @Test
    fun keysCarryTheCosmoIdAndSurviveArmoring() {
        val publicKey = Pgp.readPublicKey(Pgp.armor(Pgp.publicKeyOf(alice)))
        assertEquals(listOf("COSMO-AAAA-AAAA"), Pgp.userIds(publicKey))
        assertEquals(Pgp.fingerprint(Pgp.publicKeyOf(alice)), Pgp.fingerprint(publicKey))
        val secretKey = Pgp.readSecretKey(Pgp.armor(alice))
        assertEquals(Pgp.fingerprint(Pgp.publicKeyOf(alice)), Pgp.fingerprint(Pgp.publicKeyOf(secretKey)))
    }

    @Test
    fun recipientAndSenderCanBothReadAVerifiedMessage() {
        val message = Pgp.encryptAndSign("hello bob 👋", Pgp.publicKeyOf(bob), alice)
        assertTrue(message.startsWith("-----BEGIN PGP MESSAGE-----"))

        val forBob = Pgp.decryptAndVerify(message, bob, Pgp.publicKeyOf(alice))
        assertEquals("hello bob 👋", forBob.text)
        assertTrue(forBob.verified)

        // The sender can read their own sent messages too.
        assertEquals("hello bob 👋", Pgp.decryptAndVerify(message, alice, Pgp.publicKeyOf(alice)).text)
    }

    @Test
    fun messagesAreUnverifiedWithTheWrongOrUnknownSenderKey() {
        val message = Pgp.encryptAndSign("hi", Pgp.publicKeyOf(bob), alice)
        assertFalse(Pgp.decryptAndVerify(message, bob, Pgp.publicKeyOf(mallory)).verified)
        assertFalse(Pgp.decryptAndVerify(message, bob, null).verified)
    }

    @Test(expected = Exception::class)
    fun othersCannotDecrypt() {
        val message = Pgp.encryptAndSign("secret", Pgp.publicKeyOf(bob), alice)
        Pgp.decryptAndVerify(message, mallory, null)
    }

    @Test
    fun signaturesAreArmored() {
        assertTrue(Pgp.sign("nonce", alice).startsWith("-----BEGIN PGP MESSAGE-----"))
    }
}
