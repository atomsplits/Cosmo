package com.rivers.cosmo.wallet

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletTest {
    @Test
    fun shortensId() {
        assertEquals("GAAZ…CWN7", Wallet.demo.shortId)
        assertEquals("GABC", Wallet("GABC", BigDecimal.ZERO).shortId)
    }

    @Test
    fun formatsBalance() {
        assertEquals("1,250.50 XLM", Wallet.demo.formattedBalance)
        assertEquals("0.00 XLM", Wallet("G", BigDecimal.ZERO).formattedBalance)
        assertEquals("12.0000001 XLM", Wallet("G", BigDecimal("12.0000001")).formattedBalance)
    }
}
