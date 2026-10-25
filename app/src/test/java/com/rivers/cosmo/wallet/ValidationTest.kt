package com.rivers.cosmo.wallet

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {
    @Test
    fun acceptsRealStellarAddresses() {
        assertTrue(StellarAddress.isValid(Wallet.demo.id))
        assertTrue(StellarAddress.isValid("GBRPYHIL2CI3FNQ4BXLFMNDLFJUNPU2HY3ZMFSHONUCEOASW7QC7OX2H"))
    }

    @Test
    fun rejectsBadStellarAddresses() {
        val good = Wallet.demo.id
        assertFalse(StellarAddress.isValid(""))
        assertFalse(StellarAddress.isValid(good.dropLast(1)))
        assertFalse(StellarAddress.isValid(good.lowercase()))
        // One changed character breaks the checksum.
        assertFalse(StellarAddress.isValid(good.dropLast(1) + "A"))
        // A secret key ("S...") is not a wallet address.
        assertFalse(StellarAddress.isValid("SBGWSG6BTNCKCOB3DIFBGCVMUPQFYPA2G4O34RMTB343OYPXU5DJDVMN"))
    }

    @Test
    fun parsesAmounts() {
        assertEquals(BigDecimal("12.5"), parseAmount(" 12.5 "))
        assertEquals(BigDecimal("0.0000001"), parseAmount("0.0000001"))
        assertNull(parseAmount(""))
        assertNull(parseAmount("0"))
        assertNull(parseAmount("-3"))
        assertNull(parseAmount("1.00000001"))
        assertNull(parseAmount("1e5"))
        assertNull(parseAmount("abc"))
    }
}
