package com.rivers.cosmo.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-39 style 12-word recovery phrases, the same format crypto wallets use:
 * 128 bits of random entropy plus a 4-bit checksum, split into 12 words of 11 bits each.
 */
class SeedPhrase(private val wordList: List<String>) {

    sealed interface Validation {
        data object Valid : Validation
        data class WrongLength(val count: Int) : Validation
        data class UnknownWords(val words: List<String>) : Validation
        data object BadChecksum : Validation
    }

    private val wordIndex: Map<String, Int>

    init {
        require(wordList.size == WORD_LIST_SIZE) { "BIP-39 word list must contain $WORD_LIST_SIZE words" }
        wordIndex = wordList.withIndex().associate { (index, word) -> word to index }
    }

    fun generate(random: SecureRandom = SecureRandom()): List<String> =
        fromEntropy(ByteArray(ENTROPY_BYTES).also(random::nextBytes))

    fun fromEntropy(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) { "Entropy must be $ENTROPY_BYTES bytes" }
        val bits = BooleanArray(TOTAL_BITS)
        for (i in 0 until ENTROPY_BITS) bits[i] = bitAt(entropy, i)
        val checksum = sha256(entropy)
        for (i in 0 until CHECKSUM_BITS) bits[ENTROPY_BITS + i] = bitAt(checksum, i)

        return List(WORD_COUNT) { word ->
            var index = 0
            for (b in 0 until BITS_PER_WORD) {
                index = (index shl 1) or (if (bits[word * BITS_PER_WORD + b]) 1 else 0)
            }
            wordList[index]
        }
    }

    fun validate(words: List<String>): Validation {
        if (words.size != WORD_COUNT) return Validation.WrongLength(words.size)
        val unknown = words.filter { it !in wordIndex }
        if (unknown.isNotEmpty()) return Validation.UnknownWords(unknown)

        val bits = BooleanArray(TOTAL_BITS)
        words.forEachIndexed { word, text ->
            val index = wordIndex.getValue(text)
            for (b in 0 until BITS_PER_WORD) {
                bits[word * BITS_PER_WORD + b] = (index shr (BITS_PER_WORD - 1 - b)) and 1 == 1
            }
        }
        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in 0 until ENTROPY_BITS) {
            if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        }
        val checksum = sha256(entropy)
        for (i in 0 until CHECKSUM_BITS) {
            if (bits[ENTROPY_BITS + i] != bitAt(checksum, i)) return Validation.BadChecksum
        }
        return Validation.Valid
    }

    companion object {
        const val WORD_COUNT = 12
        const val WORD_LIST_ASSET = "bip39_english.txt"

        private const val WORD_LIST_SIZE = 2048
        private const val BITS_PER_WORD = 11
        private const val ENTROPY_BYTES = 16
        private const val ENTROPY_BITS = ENTROPY_BYTES * 8
        private const val CHECKSUM_BITS = ENTROPY_BITS / 32
        private const val TOTAL_BITS = ENTROPY_BITS + CHECKSUM_BITS

        /** Splits typed input into lowercase words, ignoring extra spaces and line breaks. */
        fun normalize(input: String): List<String> =
            input.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

        private fun bitAt(bytes: ByteArray, i: Int): Boolean =
            (bytes[i / 8].toInt() shr (7 - i % 8)) and 1 == 1

        private fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
