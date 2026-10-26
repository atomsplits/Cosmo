package com.rivers.cosmo.anchor

import android.content.Context
import com.rivers.cosmo.kyc.KycStatus
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
 * Moving money between Stellar and the outside world through anchors (SEP-24): cashing out to a
 * bank, and buying XLM with a card. The anchor's own web page collects bank or card details (and
 * any KYC); Cosmo never sees them. Started transfers are remembered so their progress can be
 * followed later.
 */
class AnchorTransfersController(context: Context, private val wallet: WalletController, network: StellarNetwork) {
    enum class Kind { Withdrawal, Purchase }

    data class Transfer(
        val kind: Kind,
        val domain: String,
        val id: String,
        val assetCode: String,
        val assetIssuer: String?,
        val startedAt: Long,
        /** Withdrawals: set once Cosmo has paid the anchor, so the payment is never sent twice. */
        val paymentHash: String? = null,
        val transaction: Sep24.Transaction? = null,
        val error: String? = null
    ) {
        val canPay: Boolean get() = kind == Kind.Withdrawal && paymentHash == null && transaction?.needsPayment == true
    }

    data class State(val transfers: List<Transfer> = emptyList(), val payingId: String? = null) {
        val cashOuts: List<Transfer> get() = transfers.filter { it.kind == Kind.Withdrawal }
        val purchases: List<Transfer> get() = transfers.filter { it.kind == Kind.Purchase }
    }

    // The file name predates purchases; kept so existing cash-outs still show up.
    private val prefs = context.getSharedPreferences("cosmo_cashouts_${network.name.lowercase()}", Context.MODE_PRIVATE)
    private val anchors = wallet.anchors
    private val lock = Mutex()
    private val _state = MutableStateFlow(State(load()))
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun withdrawAssets(domain: String): Result<List<Sep24.TransferAsset>> = io { anchors.withdrawAssets(domain) }

    /** Starts a cash-out and returns the anchor's page for the user to fill in. */
    suspend fun startWithdrawal(domain: String, balance: WalletBalance, amount: BigDecimal?): Result<String> =
        io { anchors.startWithdrawal(domain, balance, amount) }.map { started ->
            record(Transfer(Kind.Withdrawal, domain, started.id, balance.code, balance.issuer, System.currentTimeMillis()))
            started.url
        }

    /**
     * Starts buying XLM from [domain] and returns the anchor's page, where the user pays by card.
     * Only for users the anchor has verified (SEP-12 status ACCEPTED): the status is fetched from
     * the anchor right here, so this can't be skipped by the app's screens getting out of date.
     */
    suspend fun startPurchase(domain: String, amount: BigDecimal?): Result<String> =
        io {
            val status = anchors.customer(domain).status
            if (status != KycStatus.ACCEPTED) {
                throw AnchorException(PurchaseRules.kycMessage(domain, status))
            }
            val xlm = anchors.depositAssets(domain).firstOrNull { it.isXlm }
                ?: throw AnchorException("$domain doesn't sell XLM.")
            PurchaseRules.amountProblem(amount, xlm)?.let { throw AnchorException(it) }
            anchors.startDeposit(domain, xlm, amount)
        }.map { started ->
            record(Transfer(Kind.Purchase, domain, started.id, "XLM", null, System.currentTimeMillis()))
            started.url
        }

    /** Fetches the latest status of every transfer that isn't finished. */
    suspend fun refresh() {
        val open = _state.value.transfers.filter { it.transaction?.finished != true }
        var completedPurchase = false
        for (transfer in open) {
            val result = io { anchors.transaction(transfer.domain, transfer.id) }
            if (transfer.kind == Kind.Purchase && result.getOrNull()?.status == "completed") completedPurchase = true
            replace(transfer.id) { current ->
                result.fold(
                    onSuccess = { current.copy(transaction = it, error = null) },
                    onFailure = { current.copy(error = it.message) }
                )
            }
        }
        // The XLM has arrived; show it in the balance right away.
        if (completedPurchase) wallet.refresh()
    }

    /** Sends the anchor the funds a cash-out is waiting for. */
    suspend fun pay(id: String): Result<String> {
        val transfer = _state.value.transfers.firstOrNull { it.id == id } ?: return Result.failure(AnchorException("That cash-out is gone."))
        val transaction = transfer.transaction
        if (!transfer.canPay || transaction == null || _state.value.payingId != null) {
            return Result.failure(AnchorException("This cash-out isn't waiting for a payment."))
        }
        val balance = wallet.state.value.balances.firstOrNull { it.code == transfer.assetCode && it.issuer == transfer.assetIssuer }
            ?: return Result.failure(AnchorException("You don't hold any ${transfer.assetCode}."))
        _state.update { it.copy(payingId = id) }
        val result = wallet.payWithdrawal(transaction, balance)
        result.onSuccess { hash -> replace(id) { it.copy(paymentHash = hash) } }
        _state.update { it.copy(payingId = null) }
        refresh()
        return result
    }

    suspend fun remove(id: String) = lock.withLock {
        _state.update { state -> state.copy(transfers = state.transfers.filterNot { it.id == id }) }
        save()
    }

    private suspend fun record(transfer: Transfer) = lock.withLock {
        _state.update { it.copy(transfers = (listOf(transfer) + it.transfers).take(MAX_KEPT)) }
        save()
    }

    private suspend fun replace(id: String, change: (Transfer) -> Transfer) = lock.withLock {
        _state.update { state -> state.copy(transfers = state.transfers.map { if (it.id == id) change(it) else it }) }
        save()
    }

    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

    private fun save() {
        val json = JSONArray()
        _state.value.transfers.forEach { transfer ->
            json.put(
                JSONObject()
                    .put("kind", transfer.kind.name)
                    .put("domain", transfer.domain)
                    .put("id", transfer.id)
                    .put("assetCode", transfer.assetCode)
                    .put("assetIssuer", transfer.assetIssuer ?: JSONObject.NULL)
                    .put("startedAt", transfer.startedAt)
                    .put("paymentHash", transfer.paymentHash ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private fun load(): List<Transfer> = runCatching {
        val json = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        List(json.length()) { i ->
            val item = json.getJSONObject(i)
            Transfer(
                kind = Kind.entries.firstOrNull { it.name == item.optString("kind") } ?: Kind.Withdrawal,
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
        const val MAX_KEPT = 30
    }
}

/** The checks on buying XLM, kept apart so they can be tested without an anchor. */
object PurchaseRules {
    fun kycMessage(domain: String, status: KycStatus?): String = when (status) {
        KycStatus.PROCESSING -> "$domain is still checking your identity. You can buy XLM once it's verified."
        KycStatus.REJECTED -> "$domain couldn't verify your identity, so it can't sell to you."
        else -> "Verify your identity with $domain before buying XLM."
    }

    fun amountProblem(amount: BigDecimal?, asset: Sep24.TransferAsset): String? = when {
        amount == null -> null
        amount.signum() <= 0 -> "Enter an amount of XLM to buy."
        asset.minAmount != null && amount < asset.minAmount -> "The minimum is ${asset.minAmount.toPlainString()} XLM."
        asset.maxAmount != null && amount > asset.maxAmount -> "The maximum is ${asset.maxAmount.toPlainString()} XLM."
        else -> null
    }
}
