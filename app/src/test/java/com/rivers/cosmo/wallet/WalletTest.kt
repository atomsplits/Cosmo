package com.rivers.cosmo.wallet

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletTest {
    @Test
    fun shortensAddresses() {
        assertEquals("GAAZ…CWN7", shortenAddress(Wallet.DEMO_ADDRESS))
        assertEquals("GABC", shortenAddress("GABC"))
    }

    @Test
    fun formatsAmounts() {
        assertEquals("1,250.50 XLM", formatAmount(BigDecimal("1250.5"), "XLM"))
        assertEquals("0.00 XLM", formatAmount(BigDecimal.ZERO, "XLM"))
        assertEquals("12.0000001 USDC", formatAmount(BigDecimal("12.0000001"), "USDC"))
    }
}
