package com.rivers.cosmo.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StellarTomlTest {
    // Shaped like testanchor.stellar.org's file.
    private val sample = """
        ACCOUNTS = ["GCSGSR6KQQ5BP2FXVPWRL6SWPUSFWLVONLIBJZUKTVQB5FYJFVL6XOXE"]
        NETWORK_PASSPHRASE = "Test SDF Network ; September 2015"
        SIGNING_KEY = "GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
        WEB_AUTH_ENDPOINT = "https://testanchor.stellar.org/auth"
        TRANSFER_SERVER_SEP0024 = "https://testanchor.stellar.org/sep24/"
        TRANSFER_SERVER = "https://testanchor.stellar.org/sep6"
        KYC_SERVER = "http://insecure.example.com/kyc"

        [DOCUMENTATION]
        ORG_NAME = "Stellar Development Foundation"

        [[CURRENCIES]]
        code = "SRT"
        issuer = "GCDNJUBQSX7AJWLJACMJ7I4BC3Z47BQUTMHEICZLE6MU4KQBRYG5JY6B"
        status = "test"
        desc = "A fake anchored asset to use with this example anchor server."
        anchor_asset_type = "other"

        [[CURRENCIES]]
        code = "BTC"
        issuer = "GAUTUYY2THLF7SGITDFMXJVYH3LHDSMGEAKSBU267M2K7A3W543CKUEF"
        name = "Bitcoin"
        anchor_asset_type = "crypto"
        anchor_asset = "BTC"

        [[CURRENCIES]]
        name = "no code, so skipped"
    """.trimIndent()

    @Test
    fun readsEndpointsAndCurrencies() {
        val toml = StellarToml.parse("testanchor.stellar.org", sample)
        assertEquals("Test SDF Network ; September 2015", toml.networkPassphrase)
        assertEquals("GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR", toml.signingKey)
        assertEquals("https://testanchor.stellar.org/auth", toml.webAuthEndpoint)
        assertEquals("https://testanchor.stellar.org/sep24", toml.transferServerSep24) // trailing slash dropped
        assertTrue(toml.supportsWebAuth)
        assertEquals(listOf("SRT", "BTC"), toml.currencies.map { it.code })
        assertEquals("Bitcoin", toml.currencies[1].name)
        assertEquals("crypto", toml.currencies[1].anchorAssetType)
    }

    @Test
    fun ignoresInsecureEndpoints() {
        val toml = StellarToml.parse("testanchor.stellar.org", sample)
        assertNull(toml.kycServer)
        // Falls back to the SEP-6 server for SEP-12.
        assertEquals("https://testanchor.stellar.org/sep6", toml.customerServer)
    }

    @Test
    fun rejectsInvalidToml() {
        assertThrows(AnchorException::class.java) { StellarToml.parse("bad.example", "SIGNING_KEY = = nope") }
    }

    @Test
    fun normalizesDomains() {
        assertEquals("testanchor.stellar.org", StellarToml.normalizeDomain("  https://TestAnchor.Stellar.org/sep24  "))
        assertEquals("testanchor.stellar.org", StellarToml.normalizeDomain("testanchor.stellar.org"))
        assertNull(StellarToml.normalizeDomain("localhost"))
        assertNull(StellarToml.normalizeDomain("not a domain"))
        assertNull(StellarToml.normalizeDomain(""))
        assertEquals("https://example.com/.well-known/stellar.toml", StellarToml.url("example.com"))
    }
}
