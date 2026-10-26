package com.rivers.cosmo.wallet

import java.math.BigDecimal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stellar.sdk.Account
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.MemoHash
import org.stellar.sdk.MemoId
import org.stellar.sdk.MemoText
import org.stellar.sdk.operations.ChangeTrustOperation
import org.stellar.sdk.operations.PathPaymentStrictSendOperation

class TradingTest {
    private val user = KeyPair.random()
    private val btc = TradeAsset("BTC", "GAUTUYY2THLF7SGITDFMXJVYH3LHDSMGEAKSBU267M2K7A3W543CKUEF", "btc.example.com")
    private val usdc = Asset.createNonNativeAsset("USDC", "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN")
    private val quote = SwapQuote(TradeAsset.XLM, btc, BigDecimal("100"), BigDecimal("0.0004321"), listOf(usdc))

    private fun build(needsTrustline: Boolean, q: SwapQuote = quote) =
        StellarClient.buildSwapTransaction(StellarNetwork.Testnet, Account(user.accountId, 7L), user, q, needsTrustline)

    @Test
    fun tradesToTheUsersOwnAccountWithPriceProtection() {
        val tx = build(needsTrustline = false)
        assertEquals(1, tx.operations.size)
        val op = tx.operations[0] as PathPaymentStrictSendOperation
        assertEquals(user.accountId, op.destination)
        assertEquals(Asset.createNativeAsset(), op.sendAsset)
        assertEquals(0, BigDecimal("100").compareTo(op.sendAmount))
        assertEquals(btc.asset, op.destAsset)
        assertEquals(0, BigDecimal("0.0004277").compareTo(op.destMin)) // 1% below the quote, rounded down
        assertArrayEquals(arrayOf(usdc), op.path)
        assertEquals(1, tx.signatures.size)
    }

    @Test
    fun addsTheTrustlineInTheSameTransaction() {
        val tx = build(needsTrustline = true)
        assertEquals(2, tx.operations.size)
        assertEquals(btc.asset, (tx.operations[0] as ChangeTrustOperation).asset.asset)
        assertTrue(tx.operations[1] is PathPaymentStrictSendOperation)
    }

    @Test
    fun refusesTradingAnAssetForItself() {
        assertThrows(WalletException::class.java) { build(false, quote.copy(to = TradeAsset.XLM)) }
    }

    @Test
    fun computesRatesAndMinimums() {
        assertEquals("0.0000043", quote.rate.toPlainString())
        assertEquals(BigDecimal("98.0100000"), SwapQuote.minimumAfterSlippage(BigDecimal("99")))
        assertEquals("BTC:${btc.issuer}", btc.key)
        assertTrue(btc.matches(WalletBalance("BTC", btc.issuer, BigDecimal.ONE)))
        assertFalse(btc.matches(WalletBalance("BTC", "GOTHER", BigDecimal.ONE)))
    }

    @Test
    fun buildsAnchorMemos() {
        assertEquals("186384", (StellarClient.anchorMemo("id", "186384") as MemoId).id.toString())
        assertEquals("ref-42", (StellarClient.anchorMemo("text", "ref-42") as MemoText).text)
        val hash = StellarClient.anchorMemo("hash", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=") as MemoHash
        assertEquals(32, hash.bytes.size)
        assertThrows(WalletException::class.java) { StellarClient.anchorMemo("id", "-1") }
        assertThrows(WalletException::class.java) { StellarClient.anchorMemo("hash", "c2hvcnQ=") }
        assertThrows(WalletException::class.java) { StellarClient.anchorMemo("text", "x".repeat(29)) }
    }

    @Test
    fun onlyTrustsIssuersTheirWebsiteVouchesFor() {
        val issued = IssuedAsset("BTC", btc.issuer!!, 5000, "https://btc.example.com/.well-known/stellar.toml")
        assertEquals("btc.example.com", AssetDirectory.homeDomain(issued))
        assertNull(AssetDirectory.homeDomain(issued.copy(tomlUrl = "https://btc.example.com/elsewhere/stellar.toml")))
        assertNull(AssetDirectory.homeDomain(issued.copy(tomlUrl = null)))

        val toml = com.rivers.cosmo.anchor.StellarToml.parse(
            "btc.example.com",
            "[[CURRENCIES]]\ncode = \"BTC\"\nissuer = \"${btc.issuer}\"\n"
        )
        assertTrue(AssetDirectory.vouches(toml, issued))
        // A copycat token with the same code but another issuer isn't vouched for.
        assertFalse(AssetDirectory.vouches(toml, issued.copy(issuer = KeyPair.random().accountId)))
    }
}
