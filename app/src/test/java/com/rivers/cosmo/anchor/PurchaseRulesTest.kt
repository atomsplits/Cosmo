package com.rivers.cosmo.anchor

import com.rivers.cosmo.kyc.KycStatus
import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseRulesTest {
    @Test
    fun readsWhatTheAnchorSells() {
        val info = JSONObject(
            """{"deposit":{"native":{"enabled":true,"min_amount":"10","max_amount":"5000"},"SRT":{"enabled":true},"USDC":{"enabled":false}},
               "withdraw":{"SRT":{"enabled":true}}}"""
        )
        val deposits = Sep24.parseInfo(info, "deposit")
        assertEquals(listOf("SRT", "native"), deposits.map { it.code })
        assertTrue(deposits.single { it.code == "native" }.isXlm)
        assertFalse(deposits.single { it.code == "SRT" }.isXlm)
        assertEquals(listOf("SRT"), Sep24.parseInfo(info).map { it.code }) // withdrawals by default
        // Some anchors still call it "XLM".
        assertTrue(Sep24.TransferAsset("XLM", null, null).isXlm)
        assertFalse(Sep24.TransferAsset("XLM", null, null, issuer = "GISSUER").isXlm)
    }

    @Test
    fun explainsWhyBuyingIsLocked() {
        assertEquals("Verify your identity with a.example before buying XLM.", PurchaseRules.kycMessage("a.example", KycStatus.NEEDS_INFO))
        assertEquals("Verify your identity with a.example before buying XLM.", PurchaseRules.kycMessage("a.example", null))
        assertTrue(PurchaseRules.kycMessage("a.example", KycStatus.PROCESSING).contains("still checking"))
        assertTrue(PurchaseRules.kycMessage("a.example", KycStatus.REJECTED).contains("can't sell to you"))
    }

    @Test
    fun checksTheAmountAgainstTheAnchorsLimits() {
        val xlm = Sep24.TransferAsset("native", BigDecimal("10"), BigDecimal("5000"))
        assertNull(PurchaseRules.amountProblem(null, xlm)) // chosen on the anchor's page instead
        assertNull(PurchaseRules.amountProblem(BigDecimal("100"), xlm))
        assertEquals("The minimum is 10 XLM.", PurchaseRules.amountProblem(BigDecimal("5"), xlm))
        assertEquals("The maximum is 5000 XLM.", PurchaseRules.amountProblem(BigDecimal("5001"), xlm))
        assertEquals("Enter an amount of XLM to buy.", PurchaseRules.amountProblem(BigDecimal.ZERO, xlm))
    }

    @Test
    fun describesPurchaseProgress() {
        assertEquals("Waiting for your card payment", Sep24.statusLabel("pending_user_transfer_start", deposit = true))
        assertEquals("Ready: send the funds to finish", Sep24.statusLabel("pending_user_transfer_start"))
        assertEquals("Completed", Sep24.statusLabel("completed", deposit = true))
    }
}
