package com.rivers.cosmo.anchor

import java.math.BigDecimal
import org.json.JSONObject

/** SEP-24, hosted withdrawals: the anchor's web page collects bank details and KYC, then Cosmo pays it. */
object Sep24 {
    /** What the anchor lets users withdraw, by asset code ("native" is XLM); [issuer] comes from its stellar.toml. */
    data class WithdrawAsset(val code: String, val minAmount: BigDecimal?, val maxAmount: BigDecimal?, val issuer: String? = null)

    /** The anchor's page to open for this withdrawal. */
    data class Started(val id: String, val url: String)

    data class Transaction(
        val id: String,
        val status: String,
        val amountIn: BigDecimal?,
        val amountOut: BigDecimal?,
        val amountFee: BigDecimal?,
        val withdrawAnchorAccount: String?,
        val withdrawMemo: String?,
        val withdrawMemoType: String?,
        val moreInfoUrl: String?,
        val message: String?
    ) {
        /** The anchor is waiting for the user's Stellar payment. */
        val needsPayment: Boolean get() = status == "pending_user_transfer_start" && withdrawAnchorAccount != null
        val finished: Boolean get() = status in FINISHED
    }

    private val FINISHED = setOf("completed", "refunded", "expired", "error", "no_market", "too_small", "too_large")

    fun parseInfo(json: JSONObject): List<WithdrawAsset> {
        val withdraw = json.optJSONObject("withdraw") ?: return emptyList()
        return withdraw.keys().asSequence().mapNotNull { code ->
            val asset = withdraw.optJSONObject(code) ?: return@mapNotNull null
            if (!asset.optBoolean("enabled", false)) return@mapNotNull null
            WithdrawAsset(code, asset.decimal("min_amount"), asset.decimal("max_amount"))
        }.sortedBy { it.code }.toList()
    }

    fun parseStarted(json: JSONObject): Started {
        val url = json.optString("url").takeIf { it.startsWith("https://") }
        val id = json.optString("id").takeIf { it.isNotBlank() }
        if (json.optString("type") != "interactive_customer_info_needed" || url == null || id == null) {
            throw AnchorException("The anchor didn't return a page to continue the withdrawal.")
        }
        return Started(id, url)
    }

    fun parseTransaction(json: JSONObject): Transaction {
        val tx = json.optJSONObject("transaction") ?: throw AnchorException("The anchor sent an unexpected response.")
        return Transaction(
            id = tx.getString("id"),
            status = tx.optString("status", "unknown"),
            amountIn = tx.decimal("amount_in"),
            amountOut = tx.decimal("amount_out"),
            amountFee = tx.decimal("amount_fee"),
            withdrawAnchorAccount = tx.text("withdraw_anchor_account"),
            withdrawMemo = tx.text("withdraw_memo"),
            withdrawMemoType = tx.text("withdraw_memo_type"),
            moreInfoUrl = tx.text("more_info_url")?.takeIf { it.startsWith("https://") },
            message = tx.text("message")
        )
    }

    /** Plain words for SEP-24's transaction statuses. */
    fun statusLabel(status: String): String = when (status) {
        "incomplete" -> "Waiting for you to finish on the anchor's page"
        "pending_user_transfer_start" -> "Ready: send the funds to finish"
        "pending_user_transfer_complete" -> "Funds received by the anchor"
        "pending_anchor" -> "The anchor is processing it"
        "pending_stellar" -> "Waiting for the Stellar network"
        "pending_external" -> "On its way to your bank"
        "pending_trust" -> "Waiting for a trustline"
        "pending_user" -> "The anchor needs something from you"
        "completed" -> "Completed"
        "refunded" -> "Refunded"
        "expired" -> "Expired"
        "no_market", "too_small", "too_large" -> "The anchor couldn't process this amount"
        "error" -> "Failed"
        else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

internal fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun JSONObject.decimal(key: String): BigDecimal? = text(key)?.let { runCatching { BigDecimal(it) }.getOrNull() }
