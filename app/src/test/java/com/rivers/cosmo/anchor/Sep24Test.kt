package com.rivers.cosmo.anchor

import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Sep24Test {
    @Test
    fun listsEnabledWithdrawals() {
        val info = JSONObject(
            """{"deposit":{"SRT":{"enabled":true}},
               "withdraw":{"SRT":{"enabled":true,"min_amount":1,"max_amount":"1000"},"USDC":{"enabled":false},"native":{"enabled":true}}}"""
        )
        val assets = Sep24.parseInfo(info)
        assertEquals(listOf("SRT", "native"), assets.map { it.code })
        assertEquals(BigDecimal("1"), assets[0].minAmount)
        assertEquals(BigDecimal("1000"), assets[0].maxAmount)
        assertNull(assets[1].minAmount)
    }

    @Test
    fun readsTheInteractiveUrl() {
        val started = Sep24.parseStarted(JSONObject("""{"type":"interactive_customer_info_needed","url":"https://anchor.example/w?t=1","id":"82fhs729f63dh0v4"}"""))
        assertEquals("82fhs729f63dh0v4", started.id)
        assertEquals("https://anchor.example/w?t=1", started.url)
        assertThrows(AnchorException::class.java) {
            Sep24.parseStarted(JSONObject("""{"type":"interactive_customer_info_needed","url":"http://anchor.example/w","id":"1"}"""))
        }
    }

    @Test
    fun readsAWithdrawalWaitingForPayment() {
        val tx = Sep24.parseTransaction(
            JSONObject(
                """{"transaction":{"id":"82fhs729f63dh0v4","kind":"withdrawal","status":"pending_user_transfer_start",
                   "amount_in":"510.00","amount_out":"490.00","amount_fee":"5","withdraw_anchor_account":"GCSGSR6KQQ5BP2FXVPWRL6SWPUSFWLVONLIBJZUKTVQB5FYJFVL6XOXE",
                   "withdraw_memo":"186384","withdraw_memo_type":"id","more_info_url":"https://anchor.example/tx/1","message":null}}"""
            )
        )
        assertTrue(tx.needsPayment)
        assertFalse(tx.finished)
        assertEquals(BigDecimal("510.00"), tx.amountIn)
        assertEquals("id", tx.withdrawMemoType)
        assertNull(tx.message)
        assertEquals("Ready: send the funds to finish", Sep24.statusLabel(tx.status))
    }

    @Test
    fun knowsWhenAWithdrawalIsDone() {
        val tx = Sep24.parseTransaction(JSONObject("""{"transaction":{"id":"1","status":"completed"}}"""))
        assertTrue(tx.finished)
        assertFalse(tx.needsPayment)
        assertEquals("Pending something new", Sep24.statusLabel("pending_something_new"))
    }
}
