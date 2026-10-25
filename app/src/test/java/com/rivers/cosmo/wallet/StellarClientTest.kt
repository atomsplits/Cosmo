package com.rivers.cosmo.wallet

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stellar.sdk.Account
import org.stellar.sdk.MemoText
import org.stellar.sdk.Network
import org.stellar.sdk.Transaction
import org.stellar.sdk.operations.CreateAccountOperation
import org.stellar.sdk.operations.PaymentOperation

// Builds and signs real transactions offline; nothing here talks to the network.
class StellarClientTest {
    private val sender = StellarKeys.fromPhrase("illness spike retreat truth genius clock brain pass fit cave bargain toe".split(" "))
    private val recipient = Wallet.DEMO_ADDRESS
    private val xlm = WalletBalance("XLM", null, BigDecimal("100"))
    private val usdc = WalletBalance("USDC", "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5", BigDecimal("50"))

    private fun build(
        balance: WalletBalance = xlm,
        amount: String = "12.5",
        memo: String? = null,
        destinationExists: Boolean = true,
        network: StellarNetwork = StellarNetwork.Testnet
    ): Transaction = StellarClient.buildTransaction(
        network, Account(sender.accountId, 41L), sender, recipient, balance, BigDecimal(amount), memo, destinationExists
    )

    @Test
    fun buildsASignedXlmPayment() {
        val transaction = build(memo = "invoice 7")
        val payment = transaction.operations.single() as PaymentOperation
        assertEquals(recipient, payment.destination)
        assertEquals(0, BigDecimal("12.5").compareTo(payment.amount))
        assertEquals("invoice 7", String((transaction.memo as MemoText).bytes))
        assertEquals(42L, transaction.sequenceNumber)
        assertEquals(Network.TESTNET, transaction.network)
        assertEquals(1, transaction.signatures.size)
        assertTrue(sender.verify(transaction.hash(), transaction.signatures.single().signature.signature))
    }

    @Test
    fun signsForTheChosenNetwork() {
        assertEquals(Network.PUBLIC, build(network = StellarNetwork.Public).network)
    }

    @Test
    fun createsTheAccountWhenTheRecipientIsNotActivated() {
        val create = build(amount = "5", destinationExists = false).operations.single() as CreateAccountOperation
        assertEquals(recipient, create.destination)
        assertEquals(0, BigDecimal("5").compareTo(create.startingBalance))
    }

    @Test
    fun refusesPaymentsThatCannotSucceed() {
        assertThrows(WalletException::class.java) { build(amount = "0.5", destinationExists = false) }
        assertThrows(WalletException::class.java) { build(balance = usdc, destinationExists = false) }
        assertThrows(WalletException::class.java) { build(memo = "this memo is far too long to fit on stellar") }
    }

    @Test
    fun sendsOtherAssets() {
        val payment = build(balance = usdc).operations.single() as PaymentOperation
        assertEquals(usdc.asset, payment.asset)
    }
}
