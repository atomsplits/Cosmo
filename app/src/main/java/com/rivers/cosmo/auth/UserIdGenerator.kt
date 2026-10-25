package com.rivers.cosmo.auth

import java.security.SecureRandom

/** Generates user IDs like `COSMO-7KQ2-M9XD`, so nobody has to pick a username. */
object UserIdGenerator {
    // Leaves out characters that are easy to mix up when written down: 0/O, 1/I/L and U/V.
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTWXYZ23456789"
    private const val PREFIX = "COSMO"
    private val PATTERN = Regex("$PREFIX(-[$ALPHABET]{4}){2}")

    fun generate(random: SecureRandom = SecureRandom()): String =
        (listOf(PREFIX) + List(2) { group(random) }).joinToString("-")

    /** True for IDs in the generated format; pass the input through [normalize] first. */
    fun isValid(userId: String): Boolean = PATTERN.matches(userId)

    /** Accepts IDs typed in lowercase or with stray spaces. */
    fun normalize(input: String): String = input.filterNot { it.isWhitespace() }.uppercase()

    private fun group(random: SecureRandom): String =
        String(CharArray(4) { ALPHABET[random.nextInt(ALPHABET.length)] })
}
