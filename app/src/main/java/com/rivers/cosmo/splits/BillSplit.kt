package com.rivers.cosmo.splits

import com.rivers.cosmo.wallet.PaymentRecord
import com.rivers.cosmo.wallet.StellarNetwork
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom

/** One person's part of a bill split. */
data class SplitShare(val label: String, val amount: BigDecimal, val isOrganizer: Boolean = false)

/**
 * A shared bill or fund, e.g. a 2,500 trip fund split between five friends. Everyone pays their
 * share to the organizer's Stellar address with the share's memo, which is how payments are matched.
 */
data class BillSplit(
    /** 8 characters, unique enough to tell one person's splits apart. */
    val id: String,
    val title: String,
    val organizerCosmoId: String,
    val organizerAddress: String,
    val network: StellarNetwork,
    /** "XLM", or a token code like "USDC" together with [assetIssuer]. */
    val assetCode: String,
    val assetIssuer: String?,
    val shares: List<SplitShare>,
    val createdAt: Long
) {
    val total: BigDecimal get() = shares.fold(BigDecimal.ZERO) { sum, share -> sum + share.amount }

    /** The memo a payment for share [index] must carry, e.g. "CS-7KQ2M9XD-3" (fits Stellar's 28 bytes). */
    fun memoFor(index: Int) = "CS-$id-$index"

    companion object {
        const val MAX_SHARES = 50
        private const val ID_ALPHABET = "ABCDEFGHJKMNPQRSTWXYZ23456789"

        fun newId(random: SecureRandom = SecureRandom()): String = String(CharArray(8) { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] })
    }
}

object SplitMath {
    /** Stellar's smallest unit is 0.0000001. */
    private const val DECIMALS = 7

    /**
     * Splits [total] into [count] shares that add up to exactly [total]: everyone gets the same
     * amount, and any leftover units of 0.0000001 go to the first shares, one each.
     */
    fun equalShares(total: BigDecimal, count: Int): List<BigDecimal> {
        require(count > 0) { "Need at least one share" }
        val units = total.setScale(DECIMALS, RoundingMode.DOWN).unscaledValue()
        val base = units.divide(count.toBigInteger())
        val remainder = units.mod(count.toBigInteger()).toInt()
        return List(count) { i ->
            val share = BigDecimal(base + if (i < remainder) 1.toBigInteger() else 0.toBigInteger(), DECIMALS).stripTrailingZeros()
            // stripTrailingZeros turns 500 into 5E+2; keep whole numbers as plain 500.
            if (share.scale() < 0) share.setScale(0) else share
        }
    }
}

/** How much of each share has been paid, worked out from Stellar payments and their memos. */
object SplitStatus {
    data class ShareStatus(val index: Int, val paid: BigDecimal, val complete: Boolean, val payer: String? = null)

    /**
     * Statuses for a split's shares as seen from the wallet at [myAddress], using its recent
     * [payments]. The organizer sees every share; a participant can only see their own payments,
     * so other shares are null (unknown). The organizer's own share counts as covered.
     */
    fun compute(split: BillSplit, myAddress: String, payments: List<PaymentRecord>): List<ShareStatus?> {
        val isOrganizer = myAddress == split.organizerAddress
        return split.shares.mapIndexed { index, share ->
            if (share.isOrganizer) return@mapIndexed ShareStatus(index, share.amount, complete = true)
            val matching = payments.filter { payment ->
                payment.memo == split.memoFor(index) &&
                    payment.assetCode == split.assetCode && payment.assetIssuer == split.assetIssuer &&
                    if (isOrganizer) payment.incoming else !payment.incoming && payment.counterparty == split.organizerAddress
            }
            if (!isOrganizer && matching.isEmpty()) return@mapIndexed null
            val paid = matching.fold(BigDecimal.ZERO) { sum, payment -> sum + payment.amount }
            ShareStatus(index, paid, complete = paid >= share.amount, payer = matching.firstOrNull()?.counterparty?.takeIf { isOrganizer })
        }
    }

    /** Total paid so far across shares whose status is known. */
    fun collected(statuses: List<ShareStatus?>): BigDecimal =
        statuses.filterNotNull().fold(BigDecimal.ZERO) { sum, status -> sum + status.paid }
}
