package com.rivers.cosmo.wallet

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.stellar.sdk.KeyPair

/**
 * Turns a 12-word recovery phrase into a Stellar key pair the standard way (SEP-0005), so the
 * same phrase restores the same wallet in any other Stellar wallet app.
 */
object StellarKeys {
    private const val BIP39_ITERATIONS = 2048
    private const val BIP39_SEED_BITS = 512

    /** The key pair for account [accountNumber] (path m/44'/148'/n'); wallets use account 0 by default. */
    fun fromPhrase(words: List<String>, accountNumber: Int = 0): KeyPair =
        KeyPair.fromBip39Seed(bip39Seed(words), accountNumber)

    /**
     * BIP-39's phrase-to-seed step: PBKDF2-HMAC-SHA512 with the salt "mnemonic" (no passphrase).
     * Done with Bouncy Castle because Android only has HmacSHA512 PBKDF2 from version 8.0. The English
     * word list is plain ASCII, so BIP-39's Unicode normalization step changes nothing.
     */
    private fun bip39Seed(words: List<String>): ByteArray {
        val generator = PKCS5S2ParametersGenerator(SHA512Digest())
        generator.init(words.joinToString(" ").toByteArray(Charsets.UTF_8), "mnemonic".toByteArray(Charsets.UTF_8), BIP39_ITERATIONS)
        return (generator.generateDerivedParameters(BIP39_SEED_BITS) as KeyParameter).key
    }
}
