package com.rivers.cosmo.plugins

import com.rivers.cosmo.kyc.KycStatus
import com.rivers.cosmo.wallet.AccountState
import com.rivers.cosmo.wallet.PaymentRecord
import com.rivers.cosmo.wallet.StellarNetwork
import com.rivers.cosmo.wallet.Wallet
import com.rivers.cosmo.wallet.WalletBalance
import com.rivers.cosmo.wallet.WalletController
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the documented example plugins (docs/plugins/examples) against realistic wallet data. */
class ExamplePluginsTest {
    private val storage = object : PluginStorage {
        val values = HashMap<String, String>()
        override fun get(pluginId: String, key: String) = values["$pluginId:$key"]
        override fun set(pluginId: String, key: String, value: String?) {
            if (value == null) values.remove("$pluginId:$key") else values["$pluginId:$key"] = value
        }
        override fun keyCount(pluginId: String) = values.keys.count { it.startsWith("$pluginId:") }
    }
    private val engine = PluginEngine(storage)

    // Unit tests run with the app module as the working directory.
    private fun example(name: String) = File("../docs/plugins/examples/$name").readText()

    private val data = WalletData.json(
        cosmoId = "COSMO-AAAA-AAAA",
        wallet = WalletController.State(
            network = StellarNetwork.Testnet,
            address = Wallet.DEMO_ADDRESS,
            loading = false,
            account = AccountState.Active(listOf(WalletBalance("XLM", null, BigDecimal("250.5")))),
            payments = listOf(
                PaymentRecord("1", incoming = true, counterparty = "GB", amount = BigDecimal("100"), assetCode = "XLM", createdAt = "2026-10-25T14:00:00Z", transactionHash = "h1"),
                PaymentRecord("2", incoming = false, counterparty = "GC", amount = BigDecimal("30.25"), assetCode = "XLM", createdAt = "2026-10-25T15:00:00Z", transactionHash = "h2")
            )
        ),
        kyc = KycStatus.ACCEPTED
    )

    @Test
    fun walletDataHasTheDocumentedShape() {
        val json = org.json.JSONObject(data)
        assertEquals("testnet", json.getString("network"))
        assertEquals("250.5", json.getString("xlm"))
        assertEquals(30.25, json.getJSONArray("payments").getJSONObject(1).getDouble("value"), 0.0)
        assertEquals("ACCEPTED", json.getString("kyc"))
        assertTrue(json.getJSONArray("balances").getJSONObject(0).isNull("issuer"))
    }

    @Test
    fun savingsGoal() {
        engine.load("goal", example("savings-goal.js"))
        val column = engine.render("goal/goal", data) as UiNode.Column
        assertEquals(UiNode.Text("Goal: 1000 XLM", "title"), column.children[0])
        assertEquals(UiNode.Progress(0.2505f), column.children[1])
        val plus = ((column.children[3] as UiNode.Row).children[1] as UiNode.Button).action
        engine.click("goal/goal", plus)
        assertEquals(UiNode.Text("Goal: 1100 XLM", "title"), (engine.render("goal/goal", data) as UiNode.Column).children[0])
        assertEquals("1100", storage.values["goal:goal"])
    }

    @Test
    fun activitySummary() {
        engine.load("summary", example("activity-summary.js"))
        val column = engine.render("summary/summary", data) as UiNode.Column
        assertEquals(UiNode.Text("Received: 100.00 XLM", "body", "#2E7D32"), column.children[1])
        assertEquals(UiNode.Text("Net: 69.75 XLM", "label"), column.children[4])
    }

    @Test
    fun tipJarOnlySuggestsPayments() {
        engine.load("tips", example("tip-jar.js"))
        val row = (engine.render("tips/tips", data) as UiNode.Column).children[1] as UiNode.Row
        val fiveXlm = row.children[1] as UiNode.Button
        assertEquals(
            listOf(PluginEffect.SuggestPayment(Wallet.DEMO_ADDRESS, "5", "Tip")),
            engine.click("tips/tips", fiveXlm.action)
        )
    }
}
