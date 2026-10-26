package com.rivers.cosmo.market

import android.content.Context
import com.rivers.cosmo.chat.ChatService
import com.rivers.cosmo.util.backgroundScope
import com.rivers.cosmo.wallet.Escrow
import com.rivers.cosmo.wallet.StellarAddress
import com.rivers.cosmo.wallet.StellarNetwork
import com.rivers.cosmo.wallet.WalletController
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Util

class MarketException(message: String) : Exception(message)

/**
 * The marketplace for one user on one network: browsing, selling and buying digital goods for XLM.
 *
 * A sale: the buyer asks (Buy); the seller's app seals the goods with a fresh key and sends them
 * with the terms (Offer); the buyer locks the price in a Stellar escrow (Funded); the seller's app
 * checks the escrow and collects, which publishes the key; the buyer's app reads it from the
 * ledger and unlocks the goods. If the seller never collects, the buyer refunds after the deadline.
 * The seller's app does its part automatically whenever it's running.
 */
class MarketplaceController(
    context: Context,
    private val userId: String,
    private val chat: ChatService,
    private val wallet: WalletController,
    private val network: StellarNetwork
) {
    data class State(
        val listings: List<Listing> = emptyList(),
        val browsing: Boolean = false,
        val browseError: String? = null,
        val myListings: List<Listing> = emptyList(),
        val orders: List<Order> = emptyList(),
        /** Orders with an action in progress. */
        val busy: Set<String> = emptySet()
    )

    private val store = MarketStore(context, userId, network.name.lowercase())
    private val scope = backgroundScope("marketplace", Dispatchers.IO)
    private val lock = Mutex()
    private val retried = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val _state = MutableStateFlow(State(myListings = store.listings(), orders = store.orders().sortedByDescending { it.createdAt }))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        scope.launch {
            chat.marketMessages.collect { (from, text) -> MarketProtocol.parse(text)?.let { handle(from, it) } }
        }
        // The server keeps listings in memory, so put this user's back whenever the connection (re)opens.
        scope.launch {
            chat.state.map { it.connection }.distinctUntilChanged().collect { connection ->
                if (connection == ChatService.Connection.Online) {
                    _state.value.myListings.forEach { chat.publishListing(it) }
                    browse()
                }
            }
        }
    }

    fun browse() {
        scope.launch {
            _state.update { it.copy(browsing = true, browseError = null) }
            chat.browseListings()
                .onSuccess { listings -> _state.update { it.copy(browsing = false, listings = listings) } }
                .onFailure { e -> _state.update { it.copy(browsing = false, browseError = e.message) } }
        }
    }

    // Selling

    suspend fun createListing(title: String, description: String, price: BigDecimal, goods: Goods): Result<Unit> = runCatching {
        val address = wallet.accountId ?: throw MarketException("Your wallet key isn't on this device.")
        if (goods.bytes.isEmpty()) throw MarketException("Add what you're selling.")
        if (goods.bytes.size > MarketProtocol.MAX_GOODS_BYTES) throw MarketException("Items can be at most 48 KB.")
        if (title.isBlank() || title.length > 80) throw MarketException("Titles need 1 to 80 characters.")
        if (price.signum() <= 0 || price.scale() > 7) throw MarketException("Enter a price in XLM.")
        val listing = Listing(UUID.randomUUID().toString(), userId, title.trim(), description.trim().take(1000), price, address, goods.kind)
        store.saveGoods(listing.id, goods)
        lock.withLock {
            _state.update { it.copy(myListings = it.myListings + listing) }
            store.saveListings(_state.value.myListings)
        }
        if (!chat.publishListing(listing)) throw MarketException("Saved. It'll be listed when you're connected to the Cosmo server.")
        browse()
    }

    suspend fun deleteListing(id: String) {
        chat.unpublishListing(id)
        lock.withLock {
            _state.update { state -> state.copy(myListings = state.myListings.filterNot { it.id == id }) }
            store.saveListings(_state.value.myListings)
        }
        store.deleteGoods(id)
        browse()
    }

    // Buying

    suspend fun buy(listing: Listing): Result<Unit> = runCatching {
        if (listing.sellerId == userId) throw MarketException("That's your own listing.")
        val address = wallet.accountId ?: throw MarketException("Your wallet key isn't on this device.")
        val order = Order(
            id = UUID.randomUUID().toString(),
            buying = true,
            listingId = listing.id,
            title = listing.title,
            price = listing.price,
            counterparty = listing.sellerId,
            buyerAddress = address,
            sellerAddress = listing.sellerAddress,
            status = Order.Status.Requested,
            createdAt = System.currentTimeMillis()
        )
        upsert(order)
        chat.sendMarket(listing.sellerId, MarketProtocol.encode(MarketMessage.Buy(order.id, listing.id, address)))
            .onFailure { upsert(order.copy(status = Order.Status.Declined, note = it.message)) }
            .getOrThrow()
    }

    /** Buyer: pays into escrow for an offer. Safe to retry: an escrow that was opened but not locked is reused. */
    suspend fun pay(orderId: String): Result<Unit> = action(orderId) { order ->
        val terms = order.terms ?: throw MarketException("There's no offer to pay yet.")
        if (!order.buying || (order.status != Order.Status.Offered && order.status != Order.Status.Opening)) throw MarketException("This order can't be paid now.")
        if (System.currentTimeMillis() / 1000 > terms.deadline - PAY_CUTOFF_SECONDS) throw MarketException("This offer has expired. Ask to buy again.")
        val xlm = wallet.state.value.account?.let { (it as? com.rivers.cosmo.wallet.AccountState.Active)?.xlm } ?: BigDecimal.ZERO
        if (order.status == Order.Status.Offered && xlm < Escrow.startingBalance(terms.price) + FEE_ALLOWANCE) {
            throw MarketException("You need ${Escrow.startingBalance(terms.price).toPlainString()} XLM: the price plus a 2.51 XLM deposit you get back.")
        }
        // Keep the escrow's key until it's locked, so an interrupted payment can be finished.
        val escrow = order.escrowSeed?.let { KeyPair.fromSecretSeed(it) } ?: KeyPair.random()
        val opening = order.copy(status = Order.Status.Opening, escrowSeed = String(escrow.secretSeed), escrowId = escrow.accountId)
        upsert(opening)
        val alreadyOpened = order.status == Order.Status.Opening && wallet.escrowAccount(escrow.accountId).getOrNull() != null
        val (escrowId, refundXdr) = wallet.fundEscrow(terms, escrow, alreadyOpened).getOrThrow()
        val funded = opening.copy(status = Order.Status.Funded, escrowSeed = null, escrowId = escrowId, refundXdr = refundXdr)
        upsert(funded)
        chat.sendMarket(order.counterparty, MarketProtocol.encode(MarketMessage.Funded(order.id, escrowId, refundXdr)))
            .onFailure { throw MarketException("Paid into escrow, but couldn't tell the seller yet. Cosmo will retry; you can refund after the deadline if needed.") }
    }

    /** Buyer: takes the refund after the deadline (everything, or the deposit if the seller was paid). */
    suspend fun refund(orderId: String): Result<Unit> = action(orderId) { order ->
        if (!order.canRefund(System.currentTimeMillis() / 1000)) throw MarketException("A refund isn't available yet.")
        wallet.refundEscrow(order.refundXdr!!).getOrThrow()
        val completed = order.status == Order.Status.Completed
        upsert(order.copy(refundDone = true, status = if (completed) Order.Status.Completed else Order.Status.Refunded))
    }

    /** Seller: tries again to collect an escrow (e.g. after a network error). */
    suspend fun retryRelease(orderId: String): Result<Unit> = action(orderId) { order -> release(order) }

    /**
     * Follows open orders: unlocks goods once the seller has collected; once per app session,
     * re-sends the buyer's funding notice (in case it was lost) and retries a seller's collection.
     */
    suspend fun refresh() {
        for (order in _state.value.orders) {
            if (order.status != Order.Status.Funded || order.id in _state.value.busy) continue
            if (order.buying) {
                checkDelivery(order)
                val current = _state.value.orders.firstOrNull { it.id == order.id }
                if (current?.status == Order.Status.Funded && retried.add(order.id) && order.escrowId != null && order.refundXdr != null) {
                    chat.sendMarket(order.counterparty, MarketProtocol.encode(MarketMessage.Funded(order.id, order.escrowId, order.refundXdr)))
                }
            } else if (retried.add(order.id)) {
                action(order.id) { release(it) }
            }
        }
    }

    fun close() = scope.cancel()

    private suspend fun checkDelivery(order: Order) {
        val terms = order.terms ?: return
        val escrowId = order.escrowId ?: return
        val key = wallet.revealedKey(escrowId, terms, order.releaseHash).getOrNull() ?: return
        val sealed = order.sealed ?: return
        val goods = runCatching { GoodsCrypto.open(sealed, key) }.getOrNull()
        upsert(
            order.copy(
                status = Order.Status.Completed,
                goods = goods,
                note = if (goods == null) "The seller was paid, but the item they sent doesn't open with the key. Contact them." else null
            )
        )
    }

    private suspend fun handle(from: String, message: MarketMessage) {
        val existing = _state.value.orders.firstOrNull { it.id == message.orderId }
        if (existing != null && existing.counterparty != from) return // only the other party of an order may update it
        when (message) {
            is MarketMessage.Buy -> if (existing == null) offer(from, message)
            is MarketMessage.Offer -> if (existing?.buying == true && existing.status == Order.Status.Requested) acceptOffer(existing, message)
            is MarketMessage.Decline -> if (existing?.buying == true && existing.status == Order.Status.Requested) {
                upsert(existing.copy(status = Order.Status.Declined, note = message.reason))
            }
            is MarketMessage.Funded -> if (existing?.buying == false && existing.status == Order.Status.Offered) {
                val funded = existing.copy(escrowId = message.escrowId, refundXdr = message.refundXdr, status = Order.Status.Funded)
                upsert(funded)
                runCatching { release(funded) }
            }
            is MarketMessage.Released -> if (existing?.buying == true && existing.status == Order.Status.Funded) {
                val updated = existing.copy(releaseHash = message.transactionHash)
                upsert(updated)
                checkDelivery(updated)
            }
        }
    }

    /** Seller: answers a Buy with the sealed goods and the terms. */
    private suspend fun offer(buyer: String, request: MarketMessage.Buy) {
        val listing = _state.value.myListings.firstOrNull { it.id == request.listingId }
        val goods = listing?.let { store.goods(it.id) }
        val sellerAddress = wallet.accountId
        val decline = when {
            listing == null || goods == null -> "This item isn't for sale any more."
            sellerAddress == null -> "The seller's wallet isn't available."
            !StellarAddress.isValid(request.buyerAddress) -> "Invalid buyer address."
            else -> null
        }
        if (decline != null) {
            chat.sendMarket(buyer, MarketProtocol.encode(MarketMessage.Decline(request.orderId, decline)))
            return
        }
        val key = GoodsCrypto.newKey()
        val deadline = System.currentTimeMillis() / 1000 + ESCROW_WINDOW_SECONDS
        val order = Order(
            id = request.orderId,
            buying = false,
            listingId = listing!!.id,
            title = listing.title,
            price = listing.price,
            counterparty = buyer,
            buyerAddress = request.buyerAddress,
            sellerAddress = sellerAddress!!,
            status = Order.Status.Offered,
            createdAt = System.currentTimeMillis(),
            hashLock = Util.hash(key),
            deadline = deadline,
            key = key
        )
        upsert(order)
        val offer = MarketMessage.Offer(order.id, listing.id, listing.title, listing.price, sellerAddress, order.hashLock!!, deadline, GoodsCrypto.seal(goods!!, key))
        chat.sendMarket(buyer, MarketProtocol.encode(offer))
    }

    /** Buyer: checks an offer matches what was listed before showing it. */
    private suspend fun acceptOffer(order: Order, offer: MarketMessage.Offer) {
        val now = System.currentTimeMillis() / 1000
        val problem = when {
            offer.listingId != order.listingId -> "The offer was for a different item."
            offer.price.compareTo(order.price) != 0 -> "The seller changed the price."
            offer.sellerAddress != order.sellerAddress -> "The offer would pay a different account than the listing."
            offer.deadline < now + MIN_WINDOW_SECONDS || offer.deadline > now + MAX_WINDOW_SECONDS -> "The offer's deadline isn't reasonable."
            offer.goods.ciphertext.size > MarketProtocol.MAX_GOODS_BYTES + 16 -> "The item is too large."
            else -> null
        }
        upsert(
            if (problem != null) order.copy(status = Order.Status.Declined, note = problem)
            else order.copy(status = Order.Status.Offered, hashLock = offer.hashLock, deadline = offer.deadline, sealed = offer.goods)
        )
    }

    /** Seller: verifies the escrow and collects, which reveals the key to the buyer. */
    private suspend fun release(order: Order) {
        val terms = order.terms ?: throw MarketException("This sale has no terms.")
        val escrowId = order.escrowId ?: throw MarketException("The buyer hasn't paid yet.")
        val key = order.key ?: throw MarketException("The sale's key is missing.")
        val result = wallet.releaseEscrow(escrowId, order.refundXdr.orEmpty(), terms, key)
        result.onSuccess { hash ->
            upsert(order.copy(status = Order.Status.Completed, releaseHash = hash, note = null))
            chat.sendMarket(order.counterparty, MarketProtocol.encode(MarketMessage.Released(order.id, hash)))
        }.onFailure { e ->
            upsert(order.copy(note = e.message))
            throw MarketException(e.message ?: "Couldn't collect the payment.")
        }
    }

    private suspend fun action(orderId: String, block: suspend (Order) -> Unit): Result<Unit> {
        val order = _state.value.orders.firstOrNull { it.id == orderId } ?: return Result.failure(MarketException("That order is gone."))
        if (orderId in _state.value.busy) return Result.failure(MarketException("Already working on it."))
        _state.update { it.copy(busy = it.busy + orderId) }
        return try {
            runCatching { block(order) }
        } finally {
            _state.update { it.copy(busy = it.busy - orderId) }
        }
    }

    private suspend fun upsert(order: Order) = lock.withLock {
        _state.update { state ->
            val all = (listOf(order) + state.orders.filterNot { it.id == order.id }).sortedByDescending { it.createdAt }
            // Orders with money in flight are never dropped: anyone can send buy requests, and a
            // flood of them mustn't push out a sale the seller still has to collect.
            val active = all.filter { it.status == Order.Status.Opening || it.status == Order.Status.Funded }
            val rest = all.filterNot { it in active }.take((MAX_ORDERS - active.size).coerceAtLeast(0))
            state.copy(orders = (active + rest).sortedByDescending { it.createdAt })
        }
        store.saveOrders(_state.value.orders)
    }

    private companion object {
        /** How long the seller has to collect once they've made an offer. */
        const val ESCROW_WINDOW_SECONDS = 48 * 3600L
        /** Buyers can't pay this close to the deadline: the seller might not have time to collect. */
        const val PAY_CUTOFF_SECONDS = 2 * 3600L
        const val MIN_WINDOW_SECONDS = 3600L
        const val MAX_WINDOW_SECONDS = 8 * 24 * 3600L
        val FEE_ALLOWANCE = BigDecimal("0.01")
        const val MAX_ORDERS = 100
    }
}
