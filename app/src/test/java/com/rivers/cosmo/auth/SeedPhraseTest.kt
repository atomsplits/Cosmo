package com.rivers.cosmo.auth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedPhraseTest {
    private val seedPhrase = SeedPhrase(File("src/main/assets/${SeedPhrase.WORD_LIST_ASSET}").readLines())

    // Official BIP-39 test vectors: https://github.com/trezor/python-mnemonic/blob/master/vectors.json
    @Test
    fun matchesBip39TestVectors() {
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            seedPhrase.fromEntropy(ByteArray(16)).joinToString(" ")
        )
        assertEquals(
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            seedPhrase.fromEntropy(ByteArray(16) { 0x7f }).joinToString(" ")
        )
        assertEquals(
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            seedPhrase.fromEntropy(ByteArray(16) { -1 }).joinToString(" ")
        )
    }

    @Test
    fun generatedPhrasesAreValid() {
        repeat(100) {
            val words = seedPhrase.generate()
            assertEquals(SeedPhrase.WORD_COUNT, words.size)
            assertEquals(SeedPhrase.Validation.Valid, seedPhrase.validate(words))
        }
    }

    @Test
    fun rejectsBadPhrases() {
        val valid = seedPhrase.fromEntropy(ByteArray(16))
        assertEquals(SeedPhrase.Validation.WrongLength(11), seedPhrase.validate(valid.drop(1)))
        assertEquals(
            SeedPhrase.Validation.UnknownWords(listOf("notaword")),
            seedPhrase.validate(valid.dropLast(1) + "notaword")
        )
        // "abandon" in last place is a real word but fails the checksum.
        assertEquals(SeedPhrase.Validation.BadChecksum, seedPhrase.validate(valid.dropLast(1) + "abandon"))
    }

    @Test
    fun normalizesTypedInput() {
        assertEquals(listOf("zoo", "wrong"), SeedPhrase.normalize("  Zoo\n  WRONG "))
        assertTrue(SeedPhrase.normalize("   ").isEmpty())
    }

    @Test
    fun userIdsHaveExpectedShape() {
        repeat(100) {
            val id = UserIdGenerator.generate()
            assertTrue(id, Regex("COSMO-[A-Z2-9]{4}-[A-Z2-9]{4}").matches(id))
            assertEquals(id, UserIdGenerator.normalize(" ${id.lowercase()} "))
        }
    }
}
