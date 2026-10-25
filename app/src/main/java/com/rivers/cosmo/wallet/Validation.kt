package com.rivers.cosmo.wallet

import java.math.BigDecimal

/** Checks Stellar wallet addresses: 56 characters starting with "G", including a built-in checksum. */
object StellarAddress {
    private const val LENGTH = 56
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val ACCOUNT_ID_VERSION_BYTE = 6 shl 3 // Encodes as a leading "G".

    fun isValid(address: String): Boolean {
        if (address.length != LENGTH || address.any { it !in BASE32_ALPHABET }) return false
        val bytes = decodeBase32(address)
        if (bytes[0].toInt() and 0xFF != ACCOUNT_ID_VERSION_BYTE) return false
        val payload = bytes.copyOfRange(0, bytes.size - 2)
        // The checksum is stored little-endian in the last two bytes.
        val checksum = (bytes[bytes.size - 2].toInt() and 0xFF) or ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
        return crc16XModem(payload) == checksum
    }

    private fun decodeBase32(text: String): ByteArray {
        val out = ByteArray(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in text) {
            buffer = (buffer shl 5) or BASE32_ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[index++] = (buffer shr bits).toByte()
            }
        }
        return out
    }

    private fun crc16XModem(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return crc
    }
}

private const val MAX_DECIMALS = 7 // The smallest unit on Stellar is 0.0000001.
private val AMOUNT_PATTERN = Regex("\\d+(\\.\\d{1,$MAX_DECIMALS})?")

/** Parses an amount like `12.5`, or returns null if it isn't a positive number with up to 7 decimals. */
fun parseAmount(text: String): BigDecimal? {
    val trimmed = text.trim()
    if (!AMOUNT_PATTERN.matches(trimmed)) return null
    return BigDecimal(trimmed).takeIf { it.signum() > 0 }
}

/** The cryptocurrencies a transfer can be made in. */
enum class Crypto(val code: String, val displayName: String) {
    XLM("XLM", "Stellar Lumens"),
    USDC("USDC", "USD Coin"),
    BTC("BTC", "Bitcoin"),
    ETH("ETH", "Ethereum")
}
