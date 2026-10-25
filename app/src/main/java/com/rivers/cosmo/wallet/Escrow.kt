package com.rivers.cosmo.wallet

import java.math.BigDecimal
import org.stellar.sdk.AbstractTransaction
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.SignerKey
import org.stellar.sdk.StrKey
import org.stellar.sdk.TimeBounds
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.TransactionBuilderAccount
import org.stellar.sdk.TransactionPreconditions
import org.stellar.sdk.Util
import org.stellar.sdk.operations.AccountMergeOperation
import org.stellar.sdk.operations.CreateAccountOperation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.operations.SetOptionsOperation

/** What buyer and seller agreed for one sale. */
data class EscrowTerms(
    val buyer: String,
    val seller: String,
    /** In XLM. */
    val price: BigDecimal,
    /** SHA-256 of the key that unlocks the goods: the seller has to reveal that key to get paid. */
    val hashLock: ByteArray,
    /** Unix seconds. The seller must collect before this; from then on the buyer can take a refund. */
    val deadline: Long
) {
    init {
        require(hashLock.size == 32) { "The hash lock must be a SHA-256 hash." }
    }
}

/**
 * Escrow for marketplace sales, using a Stellar account that neither side controls alone: a
 * hash- and time-locked contract built from multisig, a pre-authorized transaction and time bounds.
 *
 * 1. Open (buyer): create the escrow account with the price plus [DEPOSIT], and make the seller
 *    and the hash lock its signers, one vote each.
 * 2. Lock (buyer): pre-authorize a refund that only becomes valid at the deadline (two votes),
 *    switch off the escrow's own key, and require two votes for everything. Now not even the
 *    buyer can touch the money.
 * 3. Release (seller): the price can only leave with both the seller's signature and the hash
 *    lock's preimage, which is the key to the goods the seller already sent, encrypted. Getting
 *    paid therefore publishes that key on the ledger, where the buyer's app picks it up.
 * 4. Refund (buyer, after the deadline): the pre-authorized transaction closes the escrow into the
 *    buyer's account: everything if the seller never collected, or just the deposit if they did.
 */
object Escrow {
    /** Two base reserves for the account plus one for each of its three extra signers, plus fees. */
    val DEPOSIT = BigDecimal("2.51")
    private val RESERVE = BigDecimal("2.5")
    /** The seller won't release this close to the deadline, so a release can't race the refund. */
    const val RELEASE_MARGIN_SECONDS = 15 * 60L
    private const val THRESHOLD = 2
    private const val BASE_FEE = 1_000L
    private const val SUBMIT_WINDOW_SECONDS = 180L

    fun startingBalance(price: BigDecimal): BigDecimal = price + DEPOSIT

    /** Step 1, signed by the buyer and the new escrow account's (soon disabled) key. */
    fun buildOpen(network: StellarNetwork, buyerAccount: TransactionBuilderAccount, buyer: KeyPair, escrow: KeyPair, terms: EscrowTerms): Transaction =
        TransactionBuilder(buyerAccount, network.passphrase)
            .addOperation(CreateAccountOperation.builder().destination(escrow.accountId).startingBalance(startingBalance(terms.price)).build())
            .addOperation(signer(escrow.accountId, SignerKey.fromEd25519PublicKey(terms.seller), 1))
            .addOperation(signer(escrow.accountId, SignerKey.fromSha256Hash(terms.hashLock), 1))
            .setBaseFee(BASE_FEE)
            .setTimeout(SUBMIT_WINDOW_SECONDS)
            .build()
            .apply {
                sign(buyer)
                sign(escrow)
            }

    /**
     * The refund: closes the escrow into the buyer's account, valid from the deadline on. It's
     * never signed; the escrow pre-authorizes its hash instead. [escrowSequence] is the escrow
     * account's current sequence number (it changes when the account is created on the ledger).
     */
    fun buildRefund(network: StellarNetwork, escrowId: String, escrowSequence: Long, terms: EscrowTerms): Transaction =
        TransactionBuilder(org.stellar.sdk.Account(escrowId, escrowSequence), network.passphrase)
            .addOperation(AccountMergeOperation.builder().destination(terms.buyer).build())
            .setBaseFee(BASE_FEE)
            .addPreconditions(TransactionPreconditions.builder().timeBounds(TimeBounds(terms.deadline, 0)).build())
            .build()

    /** Step 2, signed by the buyer and the escrow's key, which this disables. */
    fun buildLock(network: StellarNetwork, buyerAccount: TransactionBuilderAccount, buyer: KeyPair, escrow: KeyPair, refund: Transaction): Transaction =
        TransactionBuilder(buyerAccount, network.passphrase)
            .addOperation(signer(escrow.accountId, SignerKey.fromPreAuthTx(refund), THRESHOLD))
            .addOperation(
                SetOptionsOperation.builder()
                    .sourceAccount(escrow.accountId)
                    .masterKeyWeight(0)
                    .lowThreshold(THRESHOLD)
                    .mediumThreshold(THRESHOLD)
                    .highThreshold(THRESHOLD)
                    .build()
            )
            .setBaseFee(BASE_FEE)
            .setTimeout(SUBMIT_WINDOW_SECONDS)
            .build()
            .apply {
                sign(buyer)
                sign(escrow)
            }

    /**
     * Step 3: pays the seller, revealing [preimage], and removes both of the seller's votes so the
     * deposit can only go back to the buyer. The seller pays the fee.
     */
    fun buildRelease(
        network: StellarNetwork,
        sellerAccount: TransactionBuilderAccount,
        seller: KeyPair,
        escrowId: String,
        terms: EscrowTerms,
        preimage: ByteArray
    ): Transaction {
        require(Util.hash(preimage).contentEquals(terms.hashLock)) { "That key doesn't match the hash lock." }
        require(seller.accountId == terms.seller) { "Only the seller can release the escrow." }
        return TransactionBuilder(sellerAccount, network.passphrase)
            .addOperation(
                PaymentOperation.builder().sourceAccount(escrowId).destination(terms.seller)
                    .asset(Asset.createNativeAsset()).amount(terms.price).build()
            )
            .addOperation(signer(escrowId, SignerKey.fromEd25519PublicKey(terms.seller), 0))
            .addOperation(signer(escrowId, SignerKey.fromSha256Hash(terms.hashLock), 0))
            .setBaseFee(BASE_FEE)
            .addPreconditions(
                TransactionPreconditions.builder()
                    .timeBounds(TimeBounds(0, terms.deadline - RELEASE_MARGIN_SECONDS))
                    .build()
            )
            .build()
            .apply {
                sign(seller)
                sign(preimage) // a hash-x "signature" is the preimage itself
            }
    }

    /** An escrow account as Horizon reports it. Signer keys are strkeys: G… (account), X… (hash), T… (pre-auth). */
    data class AccountState(
        val accountId: String,
        val sequence: Long,
        val xlm: BigDecimal,
        val low: Int,
        val medium: Int,
        val high: Int,
        val signers: Map<String, Int>
    )

    /**
     * What the seller checks before revealing the key: that the escrow is locked exactly as
     * agreed, holds enough, and refunds only to the buyer and only after the deadline. Returns a
     * problem to report, or null when it's safe to release.
     */
    fun checkLocked(state: AccountState, terms: EscrowTerms, refund: Transaction, nowSeconds: Long): String? {
        val refundSigner = StrKey.encodePreAuthTx(refund.hash())
        val expectedSigners = mapOf(
            terms.seller to 1,
            StrKey.encodeSha256Hash(terms.hashLock) to 1,
            refundSigner to THRESHOLD
        )
        // Horizon lists the escrow's own key too; it must be switched off.
        val signers = state.signers.filterValues { it > 0 }
        val merge = refund.operations.singleOrNull() as? AccountMergeOperation
        val refundFrom = refund.preconditions?.timeBounds?.minTime?.toLong() ?: 0L
        return when {
            nowSeconds >= terms.deadline - RELEASE_MARGIN_SECONDS -> "It's too close to the deadline to deliver safely."
            state.low != THRESHOLD || state.medium != THRESHOLD || state.high != THRESHOLD -> "The escrow isn't locked."
            signers != expectedSigners -> "The escrow's signers aren't the agreed ones."
            state.xlm < terms.price + RESERVE -> "The escrow doesn't hold the full price."
            refund.sourceAccount != state.accountId || refund.sequenceNumber != state.sequence + 1 -> "The refund doesn't match the escrow."
            merge == null || merge.destination != terms.buyer || (merge.sourceAccount ?: state.accountId) != state.accountId ->
                "The refund doesn't go back to the buyer."
            refundFrom < terms.deadline -> "The refund could be taken before the deadline."
            else -> null
        }
    }

    /** Finds the key the seller revealed when collecting payment, in a transaction from the ledger. */
    fun preimage(envelopeXdr: String, network: StellarNetwork, hashLock: ByteArray): ByteArray? = runCatching {
        AbstractTransaction.fromEnvelopeXdr(envelopeXdr, network.passphrase).signatures
            .map { it.signature.signature }
            .firstOrNull { Util.hash(it).contentEquals(hashLock) }
    }.getOrNull()

    private fun signer(escrowId: String, key: SignerKey, weight: Int) =
        SetOptionsOperation.builder().sourceAccount(escrowId).signer(key).signerWeight(weight).build()
}
