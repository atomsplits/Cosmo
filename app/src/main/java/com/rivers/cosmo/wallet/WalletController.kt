package com.rivers.cosmo.wallet

import java.math.BigDecimal
import com.rivers.cosmo.splits.BillSplit
import com.rivers.cosmo.splits.SplitInvite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.stellar.sdk.KeyPair

/**
 * The signed-in user's Stellar wallet on one network: their address, balances and recent payments,
 * kept fresh while the app is open, plus sending.
 */
class WalletController(
    private val keys: WalletKeyStore,
    private val cosmoId: String,
    network: StellarNetwork
) {
    data class State(
        val network: StellarNetwork,
        val address: String? = null,
        /** True when this device doesn't have the user's recovery phrase, so it can't sign anything. */
        val keyMissing: Boolean = false,
        val loading: Boolean = true,
        val account: AccountState? = null,
        val payments: List<PaymentRecord> = emptyList(),
        val error: String? = null,
        val funding: Boolean = false
    ) {
        val balances: List<WalletBalance> get() = (account as? AccountState.Active)?.balances.orEmpty()
    }

    private val client = StellarClient(network)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()
    private val _state = MutableStateFlow(State(network))
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var keyPair: KeyPair? = null

    fun start() {
        scope.launch {
            val loaded = runCatching { keys.keyPair(cosmoId) }.getOrNull()
            if (loaded == null) {
                _state.update { it.copy(keyMissing = true, loading = false) }
                return@launch
            }
            keyPair = loaded
            _state.update { it.copy(address = loaded.accountId) }
            // Polling keeps the balance current when payments arrive; Horizon is cheap to ask.
            while (isActive) {
                load()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun refresh() {
        scope.launch { load() }
    }

    /** Test network only: gets free test XLM, which also activates the account. */
    fun fundWithFriendbot() {
        val address = keyPair?.accountId ?: return
        scope.launch {
            _state.update { it.copy(funding = true, error = null) }
            val result = runCatching { client.fundWithFriendbot(address) }
            _state.update { it.copy(funding = false, error = result.exceptionOrNull()?.let(::describe)) }
            load()
        }
    }

    /** Sends a payment and returns its transaction hash. */
    suspend fun send(destination: String, balance: WalletBalance, amount: BigDecimal, memo: String?): Result<String> {
        val signer = keyPair ?: return Result.failure(WalletException("This device doesn't have your wallet key."))
        val result = withContext(Dispatchers.IO) {
            runCatching { client.send(signer, destination, balance, amount, memo) }
        }
        refresh()
        return result.recoverCatching { throw WalletException(describe(it), it) }
    }

    /** Signs a bill split invite as the organizer, or returns null if this device doesn't have the wallet key. */
    fun signInvite(split: BillSplit): SplitInvite.Signed? = keyPair?.let { SplitInvite.sign(split, it) }

    /** A longer payment history (with memos) than the dashboard shows, for matching bill split payments. */
    suspend fun paymentsForSplits(): List<PaymentRecord> {
        val address = keyPair?.accountId ?: return emptyList()
        return withContext(Dispatchers.IO) { client.recentPayments(address, limit = SPLIT_HISTORY) }
    }

    fun close() {
        scope.cancel()
        client.close()
    }

    private suspend fun load() {
        val address = keyPair?.accountId ?: return
        refreshLock.withLock {
            _state.update { it.copy(loading = true) }
            try {
                val account = client.account(address)
                val payments = if (account is AccountState.Active) client.recentPayments(address) else emptyList()
                _state.update { it.copy(loading = false, account = account, payments = payments, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = describe(e)) }
            }
        }
    }

    private fun describe(e: Throwable): String =
        (e as? WalletException)?.message ?: "Something went wrong talking to the Stellar network."

    private companion object {
        const val REFRESH_INTERVAL_MS = 30_000L
        const val SPLIT_HISTORY = 200 // the most Horizon returns in one page
    }
}
