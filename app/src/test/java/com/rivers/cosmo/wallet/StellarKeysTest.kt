package com.rivers.cosmo.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class StellarKeysTest {
    // Test 1 from https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0005.md
    private val phrase = "illness spike retreat truth genius clock brain pass fit cave bargain toe".split(" ")

    @Test
    fun matchesSep5TestVector() {
        val account0 = StellarKeys.fromPhrase(phrase)
        assertEquals("GDRXE2BQUC3AZNPVFSCEZ76NJ3WWL25FYFK6RGZGIEKWE4SOOHSUJUJ6", account0.accountId)
        assertEquals("SBGWSG6BTNCKCOB3DIFBGCVMUPQFYPA2G4O34RMTB343OYPXU5DJDVMN", String(account0.secretSeed))
    }

    @Test
    fun differentAccountsGetDifferentKeys() {
        val account1 = StellarKeys.fromPhrase(phrase, accountNumber = 1)
        assertEquals(true, account1.accountId != StellarKeys.fromPhrase(phrase).accountId)
        assertEquals(true, StellarAddress.isValid(account1.accountId))
    }
}
