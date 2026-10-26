package com.rivers.cosmo.wallet

import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * XLM's price in US dollars, as traded against Circle's USDC on the public Stellar network's DEX.
 * [history] is the closing price of each hour, oldest first, over about the last day.
 */
data class XlmPrice(
    val usd: Double,
    /** Percent change since the first hour in [history]; null without enough trades. */
    val change24h: Double?,
    val history: List<Double>,
    /** Unix milliseconds of the latest trade hour. */
    val updatedAt: Long
) {
    companion object {
        /** Builds a price from Horizon's /trade_aggregations response. Null when there were no trades. */
        fun fromTradeAggregations(json: String): XlmPrice? {
            val records = JSONObject(json).getJSONObject("_embedded").getJSONArray("records")
            if (records.length() == 0) return null
            val rows = List(records.length()) { records.getJSONObject(it) }
                // Older Horizon versions send the timestamp as a number, newer ones as a string.
                .map { it.optString("timestamp").toLong() to it.getString("close").toDouble() }
                .sortedBy { it.first }
            val closes = rows.map { it.second }
            val first = closes.first()
            val last = closes.last()
            val change = if (closes.size > 1 && first > 0) (last - first) / first * 100 else null
            return XlmPrice(last, change, closes, rows.last().first)
        }
    }
}

/**
 * Fetches [XlmPrice] from the public network's Horizon, whatever network the wallet is on: test
 * XLM has no price of its own. Blocking: call off the main thread.
 */
class XlmPriceFeed(private val horizonUrl: String = StellarNetwork.Public.horizonUrl) {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    fun fetch(now: Long = System.currentTimeMillis()): XlmPrice? {
        // Horizon wants the window on whole hours.
        val end = (now / HOUR + 1) * HOUR
        val start = end - 25 * HOUR
        val url = "${horizonUrl.trimEnd('/')}/trade_aggregations".toHttpUrl().newBuilder()
            .addQueryParameter("base_asset_type", "native")
            .addQueryParameter("counter_asset_type", "credit_alphanum4")
            .addQueryParameter("counter_asset_code", "USDC")
            .addQueryParameter("counter_asset_issuer", USDC_ISSUER)
            .addQueryParameter("resolution", HOUR.toString())
            .addQueryParameter("start_time", start.toString())
            .addQueryParameter("end_time", end.toString())
            .addQueryParameter("order", "asc")
            .addQueryParameter("limit", "48")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Horizon returned ${response.code}")
            return XlmPrice.fromTradeAggregations(response.body?.string().orEmpty())
        }
    }

    companion object {
        private const val HOUR = 3_600_000L
        /** Circle's USDC on the public network (centre.io). */
        const val USDC_ISSUER = "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    }
}
