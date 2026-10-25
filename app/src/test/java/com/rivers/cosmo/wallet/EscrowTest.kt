package com.rivers.cosmo.wallet

import java.math.BigDecimal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stellar.sdk.Account
import org.stellar.sdk.KeyPair
import org.stellar.sdk.StrKey
import org.stellar.sdk.Transaction
import org.stellar.sdk.Util
import org.stellar.sdk.xdr.DecoratedSignature
import org.stellar.sdk.operations.AccountMergeOperation
import org.stellar.sdk.operations.CreateAccountOperation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.operations.SetOptionsOperation

class EscrowTest {
    private val network = StellarNetwork.Testnet
    private val buyer = KeyPair.random()
    private val seller = KeyPair.random()
    private val escrow = KeyPair.random()
    private val key = ByteArray(32) { it.toByte() }
    private val now = 1_800_000_000L
    private val terms = EscrowTerms(buyer.accountId, seller.accountId, BigDecimal("25"), Util.hash(key), now + 48 * 3600)
    private val escrowSequence = 123L shl 32

    private val refund = Escrow.buildRefund(network, escrow.accountId, escrowSequence, terms)

    /** The escrow as it is after the buyer locked it. */
    private val locked = Escrow.AccountState(
        accountId = escrow.accountId,
        sequence = escrowSequence,
        xlm = Escrow.startingBalance(terms.price),
        low = 2, medium = 2, high = 2,
        signers = mapOf(
            escrow.accountId to 0,
            seller.accountId to 1,
            StrKey.encodeSha256Hash(terms.hashLock) to 1,
            StrKey.encodePreAuthTx(refund.hash()) to 2
        )
    )

    /**
     * The signature weight a transaction carries for the escrow, counted the way Stellar does:
     * account signers by a valid ed25519 signature, hash signers by their preimage, pre-auth
     * signers by the transaction's own hash.
     */
    private fun weight(
        tx: Transaction,
        state: Escrow.AccountState,
        signatures: List<DecoratedSignature> = tx.signatures
    ): Int = state.signers.entries.sumOf { (signer, weight) ->
        val signed = when (signer[0]) {
            'G' -> signatures.any { KeyPair.fromAccountId(signer).verify(tx.hash(), it.signature.signature) }
            'X' -> signatures.any { Util.hash(it.signature.signature).contentEquals(StrKey.decodeSha256Hash(signer)) }
            'T' -> StrKey.decodePreAuthTx(signer).contentEquals(tx.hash())
            else -> false
        }
        if (signed) weight else 0
    }

    @Test
    fun opensTheEscrowWithTheSellerAndHashLockAsSigners() {
        val open = Escrow.buildOpen(network, Account(buyer.accountId, 9L), buyer, escrow, terms)
        val create = open.operations[0] as CreateAccountOperation
        assertEquals(escrow.accountId, create.destination)
        assertEquals(0, BigDecimal("27.51").compareTo(create.startingBalance))
        val signers = open.operations.drop(1).map { it as SetOptionsOperation }
        assertTrue(signers.all { it.sourceAccount == escrow.accountId && it.signerWeight == 1 })
        assertEquals(listOf(seller.accountId, StrKey.encodeSha256Hash(terms.hashLock)), signers.map { it.signer!!.encodedSignerKey })
        assertEquals(2, open.signatures.size) // buyer, and the escrow's key while it still counts
    }

    @Test
    fun refundsOnlyToTheBuyerAndOnlyAfterTheDeadline() {
        assertEquals(escrow.accountId, refund.sourceAccount)
        assertEquals(escrowSequence + 1, refund.sequenceNumber)
        assertEquals(buyer.accountId, (refund.operations.single() as AccountMergeOperation).destination)
        assertEquals(terms.deadline, refund.timeBounds.minTime.toLong())
        assertEquals(0L, refund.timeBounds.maxTime.toLong())
        assertTrue(refund.signatures.isEmpty())
        // Pre-authorized, it carries the full weight on its own.
        assertEquals(2, weight(refund, locked))
    }

    @Test
    fun lockingDisablesTheEscrowKeyAndRequiresTwoVotes() {
        val lock = Escrow.buildLock(network, Account(buyer.accountId, 10L), buyer, escrow, refund)
        val (preAuth, settings) = lock.operations.map { it as SetOptionsOperation }
        assertEquals(StrKey.encodePreAuthTx(refund.hash()), preAuth.signer!!.encodedSignerKey)
        assertEquals(2, preAuth.signerWeight)
        assertEquals(0, settings.masterKeyWeight)
        assertEquals(listOf(2, 2, 2), listOf(settings.lowThreshold, settings.mediumThreshold, settings.highThreshold))
    }

    @Test
    fun theSellerCanOnlyCollectByRevealingTheKey() {
        val release = Escrow.buildRelease(network, Account(seller.accountId, 5L), seller, escrow.accountId, terms, key)
        val payment = release.operations[0] as PaymentOperation
        assertEquals(escrow.accountId, payment.sourceAccount)
        assertEquals(seller.accountId, payment.destination)
        assertEquals(0, terms.price.compareTo(payment.amount))
        // Both of the seller's votes are removed, so only the refund (the deposit) remains.
        val removed = release.operations.drop(1).map { it as SetOptionsOperation }
        assertTrue(removed.all { it.signerWeight == 0 })
        assertEquals(terms.deadline - Escrow.RELEASE_MARGIN_SECONDS, release.timeBounds.maxTime.toLong())
        assertEquals(2, weight(release, locked))

        // The key is on the ledger for the buyer to find.
        assertArrayEquals(key, Escrow.preimage(release.toEnvelopeXdrBase64(), network, terms.hashLock))
        assertNull(Escrow.preimage(refund.toEnvelopeXdrBase64(), network, terms.hashLock))
    }

    @Test
    fun noOneCanMoveTheMoneyAlone() {
        val release = Escrow.buildRelease(network, Account(seller.accountId, 5L), seller, escrow.accountId, terms, key)
        // The seller without the key: one vote of two.
        assertEquals(1, weight(release, locked, listOf(seller.signDecorated(release.hash()))))
        // The buyer, even with the escrow's original (disabled) key: nothing.
        assertEquals(0, weight(release, locked, listOf(buyer.signDecorated(release.hash()), escrow.signDecorated(release.hash()))))
        assertThrows(IllegalArgumentException::class.java) {
            Escrow.buildRelease(network, Account(seller.accountId, 5L), seller, escrow.accountId, terms, ByteArray(32))
        }
    }

    @Test
    fun theSellerChecksTheLockBeforeRevealingTheKey() {
        assertNull(Escrow.checkLocked(locked, terms, refund, now))
        val problems = listOf(
            locked.copy(signers = locked.signers + (buyer.accountId to 1)) to "signers",
            locked.copy(signers = locked.signers + (escrow.accountId to 1)) to "signers",
            locked.copy(medium = 1) to "locked",
            locked.copy(xlm = BigDecimal("26")) to "full price",
            locked.copy(sequence = escrowSequence + 1) to "refund"
        )
        for ((state, expected) in problems) assertTrue(expected, Escrow.checkLocked(state, terms, refund, now)!!.contains(expected))

        val toSomeoneElse = Escrow.buildRefund(network, escrow.accountId, escrowSequence, terms.copy(buyer = KeyPair.random().accountId))
        val tampered = locked.copy(signers = locked.signers - StrKey.encodePreAuthTx(refund.hash()) + (StrKey.encodePreAuthTx(toSomeoneElse.hash()) to 2))
        assertTrue(Escrow.checkLocked(tampered, terms, toSomeoneElse, now)!!.contains("buyer"))

        val early = Escrow.buildRefund(network, escrow.accountId, escrowSequence, terms.copy(deadline = now))
        val earlyState = locked.copy(signers = locked.signers - StrKey.encodePreAuthTx(refund.hash()) + (StrKey.encodePreAuthTx(early.hash()) to 2))
        assertTrue(Escrow.checkLocked(earlyState, terms, early, now)!!.contains("before the deadline"))

        assertTrue(Escrow.checkLocked(locked, terms, refund, terms.deadline - 60)!!.contains("too close"))
    }
}
