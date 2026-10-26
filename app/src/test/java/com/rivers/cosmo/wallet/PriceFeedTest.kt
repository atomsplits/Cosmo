package com.rivers.cosmo.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceFeedTest {
    // Shaped like Horizon's /trade_aggregations response (other fields trimmed).
    private val response = """
        {"_embedded":{"records":[
          {"timestamp":"1793001600000","trade_count":"12","open":"0.1180","close":"0.1200","high":"0.1210","low":"0.1175"},
          {"timestamp":1792998000000,"trade_count":"9","open":"0.1190","close":"0.1150","high":"0.1195","low":"0.1140"},
          {"timestamp":"1793005200000","trade_count":"4","open":"0.1200","close":"0.1265","high":"0.1270","low":"0.1199"}
        ]}}
    """.trimIndent()

    @Test
    fun readsHourlyClosesOldestFirst() {
        val price = XlmPrice.fromTradeAggregations(response)!!
        assertEquals(listOf(0.1150, 0.1200, 0.1265), price.history)
        assertEquals(0.1265, price.usd, 0.0)
        assertEquals(10.0, price.change24h!!, 1e-9)
        assertEquals(1793005200000L, price.updatedAt)
    }

    @Test
    fun noTradesMeansNoPrice() {
        assertNull(XlmPrice.fromTradeAggregations("""{"_embedded":{"records":[]}}"""))
    }

    @Test
    fun oneHourHasNoChange() {
        val single = """{"_embedded":{"records":[{"timestamp":"1793005200000","close":"0.13"}]}}"""
        assertNull(XlmPrice.fromTradeAggregations(single)!!.change24h)
    }
}
