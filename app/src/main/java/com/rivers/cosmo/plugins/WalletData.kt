package com.rivers.cosmo.plugins

import com.rivers.cosmo.kyc.KycStatus
import com.rivers.cosmo.wallet.AccountState
import com.rivers.cosmo.wallet.WalletController
import org.json.JSONArray
import org.json.JSONObject

/**
 * The read-only snapshot of the wallet that plugins receive as `data` in render(). Only public
 * information goes in here: never keys, the recovery phrase, or chat messages.
 * Amounts are strings so they keep all 7 decimal places; `value` is the same as a JS number.
 */
object WalletData {
    fun json(cosmoId: String, wallet: WalletController.State, kyc: KycStatus?): String {
        val active = wallet.account as? AccountState.Active
        return JSONObject()
            .put("cosmoId", cosmoId)
            .put("network", wallet.network.name.lowercase())
            .put("address", wallet.address ?: JSONObject.NULL)
            .put("activated", active != null)
            .put("xlm", active?.xlm?.toPlainString() ?: "0")
            .put("balances", JSONArray().apply {
                active?.balances?.forEach { balance ->
                    put(
                        JSONObject()
                            .put("code", balance.code)
                            .put("issuer", balance.issuer ?: JSONObject.NULL)
                            .put("amount", balance.amount.toPlainString())
                            .put("value", balance.amount.toDouble())
                    )
                }
            })
            .put("payments", JSONArray().apply {
                wallet.payments.forEach { payment ->
                    put(
                        JSONObject()
                            .put("incoming", payment.incoming)
                            .put("amount", payment.amount.toPlainString())
                            .put("value", payment.amount.toDouble())
                            .put("asset", payment.assetCode)
                            .put("counterparty", payment.counterparty)
                            .put("date", payment.createdAt)
                    )
                }
            })
            .put("kyc", kyc?.name ?: JSONObject.NULL)
            .toString()
    }
}
