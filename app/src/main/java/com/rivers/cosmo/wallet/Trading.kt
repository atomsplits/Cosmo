package com.rivers.cosmo.wallet

import java.math.BigDecimal
import java.math.RoundingMode
import org.stellar.sdk.Asset

/**
 * An asset that can be traded on Stellar's built-in exchange. Anything other than XLM is a token
 * issued by someone ([issuer]), e.g. BTC from an anchor that holds real bitcoin to back it, and is
 * only worth what that issuer redeems it for. [homeDomain] is the website that vouches for it.
 */
data class TradeAsset(val code: String, val issuer: String?, val homeDomain: String? = null, val name: String? = null) {
    val isNative: Boolean get() = issuer == null
    val asset: Asset get() = if (issuer == null) Asset.createNativeAsset() else Asset.createNonNativeAsset(code, issuer)
    val key: String get() = "$code:${issuer.orEmpty()}"

    fun matches(balance: WalletBalance) = balance.code == code && balance.issuer == issuer

    companion object {
        val XLM = TradeAsset("XLM", null, name = "Stellar Lumens")
        fun of(balance: WalletBalance) = TradeAsset(balance.code, balance.issuer)
    }
}

/** The DEX's current best price for selling [sendAmount] of [from] for [to]. */
data class SwapQuote(
    val from: TradeAsset,
    val to: TradeAsset,
    val sendAmount: BigDecimal,
    val receiveAmount: BigDecimal,
    /** Assets the trade passes through between the two, chosen by Horizon for the best price. */
    val path: List<Asset>
) {
    /** The least the trade may deliver if the price moves before it goes through. */
    val minimumReceived: BigDecimal get() = minimumAfterSlippage(receiveAmount)

    /** How much of [to] one unit of [from] buys. */
    val rate: BigDecimal get() = receiveAmount.divide(sendAmount, 7, RoundingMode.DOWN).stripTrailingZeros()

    companion object {
        /** Trades fail instead of filling more than 1% below the quote. */
        val SLIPPAGE: BigDecimal = BigDecimal("0.01")

        fun minimumAfterSlippage(amount: BigDecimal): BigDecimal =
            amount.multiply(BigDecimal.ONE - SLIPPAGE).setScale(7, RoundingMode.DOWN)
    }
}

/** Codes offered as one-tap choices in the trade screen. */
val POPULAR_TRADE_CODES = listOf("BTC", "ETH", "LTC", "USDC", "EURC")
