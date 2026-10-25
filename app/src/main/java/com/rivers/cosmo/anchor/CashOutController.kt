package com.rivers.cosmo.anchor

import android.content.Context
import com.rivers.cosmo.wallet.StellarNetwork
import com.rivers.cosmo.wallet.WalletBalance
import com.rivers.cosmo.wallet.WalletController
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cashing out to a bank through an anchor (SEP-24). The anchor's own web page collects the bank
 * details and any KYC; once it's ready, Cosmo sends it the crypto, and the anchor pays out the money.
 * Started cash-outs are remembered so their progress can be followed later.
 */
class CashOutController(context: Context, private val wallet: WalletController, network: StellarNetwork) {
    data class CashOut(
        val domain: String,
        val id: String,
        val assetCode: String,
        val assetIssuer: String?,
        val startedAt: Long,
        /** Set once Cosmo has paid the anchor, so the payment is never sent twice. */
        val paymentHash: String? = null,
        val transaction: Sep24.Transaction? = null,
        val error: String? = null
    ) {
        val canPay: Boolean get() = paymentHash == null && transaction?.needsPayment == true
    }

    data class State(val cashOuts: List<CashOut> = emptyList(), val payingId: String? = null)

    private val prefs = context.getSharedPreferences("cosmo_cashouts_${network.name.lowercase()}", Context.MODE_PRIVATE)
    private val anchors = wallet.anchors
    private val lock = Mutex()
    private val _state = MutableStateFlow(State(load()))
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun assets(domain: String): Result<List<Sep24.WithdrawAsset>> = io { anchors.withdrawAssets(domain) }

    /** Starts a cash-out and returns the anchor's page for the user to fill in. */
    suspend fun start(domain: String, balance: WalletBalance, amount: BigDecimal?): Result<String> =
        io { anchors.startWithdrawal(domain, balance, amount) }.map { started ->
            lock.withLock {
                val cashOut = CashOut(domain, started.id, balance.code, balance.issuer, System.currentTimeMillis())
                _state.update { it.copy(cashOuts = (listOf(cashOut) + it.cashOuts).take(MAX_KEPT)) }
                save()
            }
            started.url
        }

    /** Fetches the latest status of every cash-out that isn't finished. */
    suspend fun refresh() {
        val open = _state.value.cashOuts.filter { it.transaction?.finished != true }
        for (cashOut in open) {
            val result = io { anchors.withdrawal(cashOut.domain, cashOut.id) }
            replace(cashOut.id) { current ->
                result.fold(
                    onSuccess = { current.copy(transaction = it, error = null) },
                    onFailure = { current.copy(error = it.message) }
                )
            }
        }
    }

    /** Sends the anchor the funds it's waiting for. */
    suspend fun pay(id: String): Result<String> {
        val cashOut = _state.value.cashOuts.firstOrNull { it.id == id } ?: return Result.failure(AnchorException("That cash-out is gone."))
        val transaction = cashOut.transaction
        if (!cashOut.canPay || transaction == null || _state.value.payingId != null) {
            return Result.failure(AnchorException("This cash-out isn't waiting for a payment."))
        }
        val balance = wallet.state.value.balances.firstOrNull { it.code == cashOut.assetCode && it.issuer == cashOut.assetIssuer }
            ?: return Result.failure(AnchorException("You don't hold any ${cashOut.assetCode}."))
        _state.update { it.copy(payingId = id) }
        val result = wallet.payWithdrawal(transaction, balance)
        result.onSuccess { hash -> replace(id) { it.copy(paymentHash = hash) } }
        _state.update { it.copy(payingId = null) }
        refresh()
        return result
    }

    suspend fun remove(id: String) = lock.withLock {
        _state.update { state -> state.copy(cashOuts = state.cashOuts.filterNot { it.id == id }) }
        save()
    }

    private suspend fun replace(id: String, change: (CashOut) -> CashOut) = lock.withLock {
        _state.update { state -> state.copy(cashOuts = state.cashOuts.map { if (it.id == id) change(it) else it }) }
        save()
    }

    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

    private fun save() {
        val json = JSONArray()
        _state.value.cashOuts.forEach { cashOut ->
            json.put(
                JSONObject()
                    .put("domain", cashOut.domain)
                    .put("id", cashOut.id)
                    .put("assetCode", cashOut.assetCode)
                    .put("assetIssuer", cashOut.assetIssuer ?: JSONObject.NULL)
                    .put("startedAt", cashOut.startedAt)
                    .put("paymentHash", cashOut.paymentHash ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private fun load(): List<CashOut> = runCatching {
        val json = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        List(json.length()) { i ->
            val item = json.getJSONObject(i)
            CashOut(
                domain = item.getString("domain"),
                id = item.getString("id"),
                assetCode = item.getString("assetCode"),
                assetIssuer = item.text("assetIssuer"),
                startedAt = item.getLong("startedAt"),
                paymentHash = item.text("paymentHash")
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "cashouts"
        const val MAX_KEPT = 20
    }
}
