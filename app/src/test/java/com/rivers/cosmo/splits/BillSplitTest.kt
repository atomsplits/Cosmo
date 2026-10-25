package com.rivers.cosmo.splits

import com.rivers.cosmo.wallet.PaymentRecord
import com.rivers.cosmo.wallet.StellarKeys
import com.rivers.cosmo.wallet.StellarNetwork
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stellar.sdk.KeyPair

class BillSplitTest {
    private val organizer = StellarKeys.fromPhrase("illness spike retreat truth genius clock brain pass fit cave bargain toe".split(" "))
    private val friend = KeyPair.random()

    // The example from the request: a 2,500 trip fund for you and 4 friends.
    private val trip = BillSplit(
        id = "7KQ2M9XD",
        title = "Trip fund",
        organizerCosmoId = "COSMO-AAAA-AAAA",
        organizerAddress = organizer.accountId,
        network = StellarNetwork.Testnet,
        assetCode = "XLM",
        assetIssuer = null,
        shares = listOf("You", "Alex", "Sam", "Jo", "Kim").zip(SplitMath.equalShares(BigDecimal("2500"), 5)).mapIndexed { i, (name, amount) ->
            SplitShare(name, amount, isOrganizer = i == 0)
        },
        createdAt = 1_761_400_000_000L
    )

    @Test
    fun splitsEquallyToTheLastStroop() {
        assertEquals(List(5) { BigDecimal("500") }, SplitMath.equalShares(BigDecimal("2500"), 5))
        val thirds = SplitMath.equalShares(BigDecimal("100"), 3)
        assertEquals(listOf(BigDecimal("33.3333334"), BigDecimal("33.3333333"), BigDecimal("33.3333333")), thirds)
        assertEquals(0, BigDecimal("100").compareTo(thirds.fold(BigDecimal.ZERO, BigDecimal::add)))
        assertEquals(0, BigDecimal("2500").compareTo(trip.total))
    }

    @Test
    fun memosFitInAStellarMemo() {
        assertEquals("CS-7KQ2M9XD-4", trip.memoFor(4))
        assertTrue(trip.memoFor(BillSplit.MAX_SHARES - 1).toByteArray().size <= 28)
        assertEquals(8, BillSplit.newId().length)
    }

    @Test
    fun invitesRoundTripThroughALink() {
        val signed = SplitInvite.sign(trip, organizer)
        val link = SplitInvite.link(signed, shareIndex = 2)
        assertTrue(link.startsWith("cosmo://split?d="))
        val opened = SplitInvite.open(link)
        assertEquals(trip, opened.split)
        assertEquals(2, opened.shareIndex)
        assertNull(SplitInvite.open(SplitInvite.link(signed)).shareIndex)
    }

    @Test
    fun rejectsAlteredOrForgedInvites() {
        val signed = SplitInvite.sign(trip, organizer)
        // Someone swaps in their own address and re-encodes the invite without the organizer's key.
        val forged = SplitInvite.Signed(SplitInvite.sign(trip.copy(organizerAddress = friend.accountId), friend).payload, signed.signature)
        assertThrows(InvalidInviteException::class.java) { SplitInvite.verify(forged) }
        // A signature from the wrong key for this address.
        assertThrows(IllegalArgumentException::class.java) { SplitInvite.sign(trip, friend) }
        assertThrows(InvalidInviteException::class.java) { SplitInvite.open("https://example.com/split?d=abc&s=def") }
        assertThrows(InvalidInviteException::class.java) { SplitInvite.open("cosmo://split?d=not-base64!!&s=x") }
        assertThrows(InvalidInviteException::class.java) { SplitInvite.open(SplitInvite.link(signed).replace("&s=", "&s=AAAA")) }
    }

    private fun payment(incoming: Boolean, counterparty: String, amount: String, memo: String?, asset: String = "XLM") =
        PaymentRecord("id", incoming, counterparty, BigDecimal(amount), asset, "2026-10-25T14:00:00Z", "hash", memo = memo)

    @Test
    fun organizerSeesWhoPaid() {
        val payments = listOf(
            payment(true, friend.accountId, "500", trip.memoFor(1)),
            payment(true, "GSAM", "200", trip.memoFor(2)), // paid part of their share
            payment(true, "GJO", "500", "something else"), // no split memo: not counted
            payment(true, "GKIM", "500", trip.memoFor(4), asset = "USDC") // wrong asset: not counted
        )
        val statuses = SplitStatus.compute(trip, organizer.accountId, payments)
        assertTrue(statuses[0]!!.complete) // the organizer's own share
        assertEquals(SplitStatus.ShareStatus(1, BigDecimal("500"), complete = true, payer = friend.accountId), statuses[1])
        assertEquals(false, statuses[2]!!.complete)
        assertEquals(0, BigDecimal("200").compareTo(statuses[2]!!.paid))
        assertEquals(false, statuses[3]!!.complete)
        assertEquals(false, statuses[4]!!.complete)
        assertEquals(0, BigDecimal("1200").compareTo(SplitStatus.collected(statuses)))
    }

    @Test
    fun participantSeesTheirOwnPayment() {
        val payments = listOf(payment(false, organizer.accountId, "500", trip.memoFor(1)))
        val statuses = SplitStatus.compute(trip, friend.accountId, payments)
        assertTrue(statuses[1]!!.complete)
        assertNull(statuses[2]) // can't see other people's payments
    }
}
