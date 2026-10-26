package com.rivers.cosmo.server

import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/** What a seller sends to list something. The seller's Cosmo ID comes from their login, not from here. */
@Serializable
data class ListingDraft(
    val id: String,
    val title: String,
    val description: String,
    /** In XLM, as a decimal string with up to 7 places. */
    val price: String,
    /** The Stellar account that gets paid. */
    val sellerAddress: String,
    /** "text" or "file". The goods themselves never touch the server. */
    val kind: String
)

@Serializable
data class Listing(
    val id: String,
    val sellerId: String,
    val title: String,
    val description: String,
    val price: String,
    val sellerAddress: String,
    val kind: String,
    val publishedAt: Long
)

/**
 * Listings for digital goods sold for XLM. Only the shop window lives here: goods go from seller to
 * buyer end-to-end encrypted over chat, and payment goes through an escrow account on Stellar.
 * Kept in memory for now. TODO: store them in Supabase.
 */
class Marketplace(private val clock: () -> Long = System::currentTimeMillis) {
    private val listings = ConcurrentHashMap<String, Listing>()

    /** Returns an error to show the seller, or null once it's listed. */
    fun publish(sellerId: String, draft: ListingDraft): String? {
        validate(draft)?.let { return it }
        synchronized(this) {
            val existing = listings[draft.id]
            if (existing != null && existing.sellerId != sellerId) return "That listing ID is taken."
            if (existing == null) {
                if (listings.values.count { it.sellerId == sellerId } >= MAX_PER_SELLER) return "You can list up to $MAX_PER_SELLER items."
                if (listings.size >= MAX_TOTAL) return "The marketplace is full right now."
            }
            listings[draft.id] = Listing(
                id = draft.id,
                sellerId = sellerId,
                title = draft.title.trim(),
                description = draft.description.trim(),
                price = BigDecimal(draft.price).stripTrailingZeros().toPlainString(),
                sellerAddress = draft.sellerAddress,
                kind = draft.kind,
                publishedAt = existing?.publishedAt ?: clock()
            )
        }
        return null
    }

    fun unpublish(sellerId: String, id: String) {
        listings.computeIfPresent(id) { _, listing -> if (listing.sellerId == sellerId) null else listing }
    }

    fun browse(): List<Listing> = listings.values.sortedByDescending { it.publishedAt }.take(MAX_RESULTS)

    private fun validate(draft: ListingDraft): String? {
        val price = draft.price.toBigDecimalOrNull()
        return when {
            draft.id.isBlank() || draft.id.length > MAX_ID -> "Invalid listing ID."
            draft.title.isBlank() || draft.title.length > MAX_TITLE -> "Titles need 1 to $MAX_TITLE characters."
            draft.description.length > MAX_DESCRIPTION -> "Descriptions can be at most $MAX_DESCRIPTION characters."
            price == null || price.signum() <= 0 || price.scale() > 7 || price > MAX_PRICE -> "Invalid price."
            !ADDRESS.matches(draft.sellerAddress) -> "Invalid Stellar address."
            draft.kind != "text" && draft.kind != "file" -> "Invalid item type."
            else -> null
        }
    }

    private companion object {
        const val MAX_ID = 64
        const val MAX_TITLE = 80
        const val MAX_DESCRIPTION = 1000
        const val MAX_PER_SELLER = 25
        const val MAX_TOTAL = 5000
        const val MAX_RESULTS = 200
        val MAX_PRICE = BigDecimal("1000000000")
        val ADDRESS = Regex("G[A-Z2-7]{55}")
    }
}
