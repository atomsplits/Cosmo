package com.rivers.cosmo.wallet

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** A Stellar wallet: its public address (starts with "G") and its balance in lumens (XLM). */
data class Wallet(val id: String, val balanceXlm: BigDecimal) {

    /** e.g. `GAAZ…CWN7`, for showing the ID in a small space. */
    val shortId: String
        get() = shortenAddress(id)

    /** e.g. `1,250.50 XLM`. Stellar balances have up to 7 decimal places, so those are kept. */
    val formattedBalance: String
        get() = "${DecimalFormat("#,##0.00#####", DecimalFormatSymbols.getInstance(Locale.US)).format(balanceXlm)} XLM"

    companion object {
        // TODO: Replace with the user's real address (derived from their recovery phrase) and a
        //  balance fetched from the Stellar network. This uses the example address from Stellar's docs.
        val demo = Wallet(
            id = "GAAZI4TCR3TY5OJHCTJC2A4QSY6CJWJH5IAJTGKIN2ER7LBNVKOCCWN7",
            balanceXlm = BigDecimal("1250.5")
        )
    }
}

private const val SHORT_EDGE = 4

/** e.g. `GAAZ…CWN7`: the first and last four characters of a wallet address. */
fun shortenAddress(address: String): String =
    if (address.length <= SHORT_EDGE * 2) address else "${address.take(SHORT_EDGE)}…${address.takeLast(SHORT_EDGE)}"
