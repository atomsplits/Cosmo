package com.rivers.cosmo.wallet

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Wallet {
    /** The example address from Stellar's docs, used in previews and tests. */
    const val DEMO_ADDRESS = "GAAZI4TCR3TY5OJHCTJC2A4QSY6CJWJH5IAJTGKIN2ER7LBNVKOCCWN7"
}

/** e.g. `1,250.50 XLM`. Stellar amounts have up to 7 decimal places, so those are kept. */
fun formatAmount(amount: BigDecimal, code: String): String =
    "${DecimalFormat("#,##0.00#####", DecimalFormatSymbols.getInstance(Locale.US)).format(amount)} $code"

private const val SHORT_EDGE = 4

/** e.g. `GAAZ…CWN7`: the first and last four characters of a wallet address. */
fun shortenAddress(address: String): String =
    if (address.length <= SHORT_EDGE * 2) address else "${address.take(SHORT_EDGE)}…${address.takeLast(SHORT_EDGE)}"
