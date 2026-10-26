package com.rivers.cosmo.wallet

import com.rivers.cosmo.util.backgroundScope
import java.math.BigDecimal
import com.rivers.cosmo.anchor.AnchorService
import com.rivers.cosmo.anchor.Sep24
import com.rivers.cosmo.splits.BillSplit
import com.rivers.cosmo.splits.SplitInvite
import kotlinx.coroutines.Dispatchers
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

    /** Anchors sign the user in with this wallet's key (SEP-10); the key itself never leaves this class. */
    val anchors = AnchorService(network) { keyPair }
    private val directory = AssetDirectory(client, anchors)
    private val scope = backgroundScope("wallet", Dispatchers.IO)
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

    /** The DEX's best price for selling [amount] of [from] for [to]; a null value means there's no market for it. */
    suspend fun quote(from: TradeAsset, amount: BigDecimal, to: TradeAsset): Result<SwapQuote?> = withContext(Dispatchers.IO) {
        runCatching { client.quoteSwap(from, amount, to) }.recoverCatching { throw WalletException(describe(it), it) }
    }

    /** Trades at the quoted price (or up to 1% worse) and returns the transaction hash. */
    suspend fun swap(quote: SwapQuote): Result<String> {
        val signer = keyPair ?: return Result.failure(WalletException("This device doesn't have your wallet key."))
        val result = withContext(Dispatchers.IO) {
            runCatching { client.swap(signer, quote, _state.value.balances) }
        }
        refresh()
        return result.recoverCatching { throw WalletException(describe(it), it) }
    }

    /** Verified tokens with this code, e.g. BTC, from the user's anchors and their issuers' websites. */
    suspend fun findAssets(code: String, anchorDomains: List<String>): Result<List<TradeAsset>> = withContext(Dispatchers.IO) {
        runCatching { directory.find(code.trim().uppercase(), anchorDomains) }.recoverCatching { throw WalletException(describe(it), it) }
    }

    /** Pays an anchor for a SEP-24 withdrawal, to the account and memo it gave, and returns the transaction hash. */
    suspend fun payWithdrawal(withdrawal: Sep24.Transaction, balance: WalletBalance): Result<String> {
        val signer = keyPair ?: return Result.failure(WalletException("This device doesn't have your wallet key."))
        val destination = withdrawal.withdrawAnchorAccount
        val amount = withdrawal.amountIn
        if (!withdrawal.needsPayment || destination == null || !StellarAddress.isValid(destination) || amount == null || amount.signum() <= 0) {
            return Result.failure(WalletException("The anchor hasn't said where to send the funds yet."))
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val memo = withdrawal.withdrawMemo?.let { StellarClient.anchorMemo(withdrawal.withdrawMemoType, it) }
                client.send(signer, destination, balance, amount, memo = null, typedMemo = memo)
            }
        }
        refresh()
        return result.recoverCatching { throw WalletException(describe(it), it) }
    }

    /** The wallet's own address, for marketplace terms; null until the key has loaded. */
    val accountId: String? get() = keyPair?.accountId

    /** Buyer: opens and locks an escrow for [terms], returning the escrow address and the refund (as XDR). */
    suspend fun fundEscrow(terms: EscrowTerms, escrow: KeyPair, alreadyOpened: Boolean): Result<Pair<String, String>> =
        withSigner { signer ->
            if (!alreadyOpened) client.openEscrow(signer, escrow, terms)
            escrow.accountId to client.lockEscrow(signer, escrow, terms).toEnvelopeXdrBase64()
        }

    /** Seller: checks the escrow is locked as agreed, then collects the price. Returns the transaction hash. */
    suspend fun releaseEscrow(escrowId: String, refundXdr: String, terms: EscrowTerms, preimage: ByteArray): Result<String> =
        withSigner { signer ->
            val state = client.escrowAccount(escrowId) ?: throw WalletException("The escrow account doesn't exist.")
            val refund = runCatching { org.stellar.sdk.AbstractTransaction.fromEnvelopeXdr(refundXdr, client.network.passphrase) }
                .getOrNull() as? org.stellar.sdk.Transaction ?: throw WalletException("The buyer's refund is damaged.")
            Escrow.checkLocked(state, terms, refund, System.currentTimeMillis() / 1000)?.let { throw WalletException(it) }
            client.releaseEscrow(signer, escrowId, terms, preimage)
        }

    suspend fun refundEscrow(refundXdr: String): Result<String> = withSigner { client.refundEscrow(refundXdr) }

    suspend fun escrowAccount(escrowId: String): Result<Escrow.AccountState?> = io { client.escrowAccount(escrowId) }

    suspend fun revealedKey(escrowId: String, terms: EscrowTerms, releaseHash: String?): Result<ByteArray?> =
        io { client.revealedKey(escrowId, terms, releaseHash) }

    private suspend fun <T> withSigner(block: (KeyPair) -> T): Result<T> {
        val signer = keyPair ?: return Result.failure(WalletException("This device doesn't have your wallet key."))
        val result = io { block(signer) }
        refresh()
        return result
    }

    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching(block).recoverCatching { throw WalletException(describe(it), it) }
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
        anchors.close()
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
