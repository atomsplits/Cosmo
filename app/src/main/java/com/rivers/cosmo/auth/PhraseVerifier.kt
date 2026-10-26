package com.rivers.cosmo.auth

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter

/**
 * What the app sends Supabase instead of the recovery phrase: a one-way value derived from it.
 *
 * PBKDF2-HMAC-SHA256, salted with the Cosmo ID and a label, so it's different for every account
 * and unrelated to the wallet key (which BIP-39 derives from the same phrase with HMAC-SHA512 and
 * the salt "mnemonic"). Knowing the verifier reveals neither the phrase nor the wallet. Supabase
 * stores only a bcrypt hash of it (see supabase/migrations/…_accounts.sql).
 *
 * Bouncy Castle's PBKDF2 is used because Android only has PBKDF2-SHA256 built in from 8.0 on, and
 * every device must derive exactly the same value. Slow on purpose: call it off the main thread.
 */
object PhraseVerifier {
    private const val LABEL = "cosmo-auth-v1:"
    private const val ITERATIONS = 100_000
    private const val BITS = 256
    private const val HEX = "0123456789abcdef"

    /** 64 lowercase hex characters. [phrase] must already be normalized (lowercase, single spaces). */
    fun derive(cosmoId: String, phrase: List<String>): String {
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        val password = phrase.joinToString(" ").toByteArray(Charsets.UTF_8)
        try {
            generator.init(password, (LABEL + cosmoId).toByteArray(Charsets.UTF_8), ITERATIONS)
            val key = (generator.generateDerivedMacParameters(BITS) as KeyParameter).key
            // Spelled out rather than String.format, which some locales would write with other digits.
            return buildString(key.size * 2) {
                key.forEach { byte ->
                    append(HEX[(byte.toInt() shr 4) and 0xf])
                    append(HEX[byte.toInt() and 0xf])
                }
            }
        } finally {
            password.fill(0)
        }
    }
}
