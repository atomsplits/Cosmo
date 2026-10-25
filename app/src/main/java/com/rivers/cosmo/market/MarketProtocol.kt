package com.rivers.cosmo.market

import java.math.BigDecimal
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.json.JSONException
import org.json.JSONObject

/** An item for sale, as the Cosmo server lists it. Must match Listing in server/.../Marketplace.kt. */
data class Listing(
    val id: String,
    val sellerId: String,
    val title: String,
    val description: String,
    /** In XLM. */
    val price: BigDecimal,
    val sellerAddress: String,
    /** "text" or "file". */
    val kind: String,
    val publishedAt: Long = 0
) {
    fun toDraftJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("description", description)
        .put("price", price.toPlainString()).put("sellerAddress", sellerAddress).put("kind", kind)

    companion object {
        const val TEXT = "text"
        const val FILE = "file"

        fun fromJson(json: JSONObject) = Listing(
            id = json.getString("id"),
            sellerId = json.getString("sellerId"),
            title = json.getString("title"),
            description = json.optString("description"),
            price = BigDecimal(json.getString("price")),
            sellerAddress = json.getString("sellerAddress"),
            kind = json.getString("kind"),
            publishedAt = json.optLong("publishedAt")
        )
    }
}

/** Goods sealed with the sale's key: unreadable until the seller reveals it by collecting payment. */
data class SealedGoods(val kind: String, val fileName: String?, val mimeType: String?, val nonce: ByteArray, val ciphertext: ByteArray)

/**
 * The messages buyer and seller apps exchange to make a sale. They travel as end-to-end encrypted,
 * signed chat messages, so only the two of them can read them and each knows who sent what.
 */
sealed interface MarketMessage {
    val orderId: String

    /** Buyer → seller: "I'd like to buy this." */
    data class Buy(override val orderId: String, val listingId: String, val buyerAddress: String) : MarketMessage

    /** Seller → buyer: the terms, with the goods already sealed inside. */
    data class Offer(
        override val orderId: String,
        val listingId: String,
        val title: String,
        val price: BigDecimal,
        val sellerAddress: String,
        val hashLock: ByteArray,
        val deadline: Long,
        val goods: SealedGoods
    ) : MarketMessage

    data class Decline(override val orderId: String, val reason: String) : MarketMessage

    /** Buyer → seller: the escrow is locked; here's its address and the refund it pre-authorizes. */
    data class Funded(override val orderId: String, val escrowId: String, val refundXdr: String) : MarketMessage

    /** Seller → buyer: collected, in this transaction (which reveals the key). */
    data class Released(override val orderId: String, val transactionHash: String) : MarketMessage
}

object MarketProtocol {
    private const val MARKER = "cosmoMarket"
    private const val VERSION = 1
    /** Goods are sent inside one chat message, which the relay caps at 100 KB after encryption. */
    const val MAX_GOODS_BYTES = 48 * 1024

    fun isMarket(text: String): Boolean = text.startsWith("{") && text.contains("\"$MARKER\"")

    fun encode(message: MarketMessage): String {
        val json = JSONObject().put(MARKER, VERSION).put("orderId", message.orderId)
        when (message) {
            is MarketMessage.Buy -> json.put("type", "buy").put("listingId", message.listingId).put("buyerAddress", message.buyerAddress)
            is MarketMessage.Offer -> json.put("type", "offer")
                .put("listingId", message.listingId)
                .put("title", message.title)
                .put("price", message.price.toPlainString())
                .put("sellerAddress", message.sellerAddress)
                .put("hashLock", message.hashLock.toByteString().hex())
                .put("deadline", message.deadline)
                .put("kind", message.goods.kind)
                .put("fileName", message.goods.fileName ?: JSONObject.NULL)
                .put("mimeType", message.goods.mimeType ?: JSONObject.NULL)
                .put("nonce", message.goods.nonce.toByteString().base64())
                .put("ciphertext", message.goods.ciphertext.toByteString().base64())
            is MarketMessage.Decline -> json.put("type", "decline").put("reason", message.reason)
            is MarketMessage.Funded -> json.put("type", "funded").put("escrowId", message.escrowId).put("refundXdr", message.refundXdr)
            is MarketMessage.Released -> json.put("type", "released").put("transactionHash", message.transactionHash)
        }
        return json.toString()
    }

    /** Null for anything malformed or from a newer version this app doesn't understand. */
    fun parse(text: String): MarketMessage? = try {
        val json = JSONObject(text)
        val orderId = json.getString("orderId").takeIf { it.isNotBlank() && it.length <= 64 } ?: throw JSONException("order id")
        if (json.optInt(MARKER) != VERSION) null else when (json.getString("type")) {
            "buy" -> MarketMessage.Buy(orderId, json.getString("listingId"), json.getString("buyerAddress"))
            "offer" -> MarketMessage.Offer(
                orderId = orderId,
                listingId = json.getString("listingId"),
                title = json.getString("title").take(80),
                price = BigDecimal(json.getString("price")),
                sellerAddress = json.getString("sellerAddress"),
                hashLock = json.getString("hashLock").decodeHex().toByteArray().also { require(it.size == 32) },
                deadline = json.getLong("deadline"),
                goods = SealedGoods(
                    kind = json.getString("kind"),
                    fileName = json.optNullable("fileName")?.take(120),
                    mimeType = json.optNullable("mimeType")?.take(100),
                    nonce = json.getString("nonce").decodeBase64()!!.toByteArray(),
                    ciphertext = json.getString("ciphertext").decodeBase64()!!.toByteArray()
                )
            )
            "decline" -> MarketMessage.Decline(orderId, json.getString("reason").take(200))
            "funded" -> MarketMessage.Funded(orderId, json.getString("escrowId"), json.getString("refundXdr"))
            "released" -> MarketMessage.Released(orderId, json.getString("transactionHash"))
            else -> null
        }
    } catch (e: JSONException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: NullPointerException) {
        null
    }

    private fun JSONObject.optNullable(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
