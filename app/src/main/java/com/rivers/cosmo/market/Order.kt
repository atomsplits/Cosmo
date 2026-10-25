package com.rivers.cosmo.market

import com.rivers.cosmo.wallet.EscrowTerms
import java.math.BigDecimal
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** One sale, from this user's side: as the buyer or as the seller. */
data class Order(
    val id: String,
    val buying: Boolean,
    val listingId: String,
    val title: String,
    val price: BigDecimal,
    /** The other party's Cosmo ID. */
    val counterparty: String,
    val buyerAddress: String,
    val sellerAddress: String,
    val status: Status,
    val createdAt: Long,
    val hashLock: ByteArray? = null,
    val deadline: Long? = null,
    /** Seller only: the key that unlocks the goods, revealed on the ledger when collecting payment. */
    val key: ByteArray? = null,
    /** Buyer only: the goods, sealed until the key appears. */
    val sealed: SealedGoods? = null,
    /** Buyer only, between opening and locking the escrow; useless (and dropped) once locked. */
    val escrowSeed: String? = null,
    val escrowId: String? = null,
    val refundXdr: String? = null,
    val releaseHash: String? = null,
    /** Buyer only: the unlocked goods. */
    val goods: Goods? = null,
    /** Buyer: the refund transaction (all the money, or just the deposit after a sale) went through. */
    val refundDone: Boolean = false,
    val note: String? = null
) {
    enum class Status {
        /** Buyer: waiting for the seller's app to send its offer. */
        Requested,
        /** Offer made; waiting for the buyer to pay into escrow. */
        Offered,
        /** Buyer: the escrow account exists but isn't locked yet. */
        Opening,
        /** The escrow is locked; waiting for the seller to collect (which unlocks the goods). */
        Funded,
        /** The seller was paid and the buyer has (or can get) the goods. */
        Completed,
        Refunded,
        Declined
    }

    val terms: EscrowTerms?
        get() = if (hashLock != null && deadline != null) EscrowTerms(buyerAddress, sellerAddress, price, hashLock, deadline) else null

    /** Buyer: can take the refund now (the deadline passed and the escrow's still open). */
    fun canRefund(nowSeconds: Long): Boolean =
        buying && !refundDone && refundXdr != null && deadline != null && nowSeconds >= deadline &&
            (status == Status.Funded || status == Status.Completed)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("buying", buying).put("listingId", listingId).put("title", title)
        .put("price", price.toPlainString()).put("counterparty", counterparty)
        .put("buyerAddress", buyerAddress).put("sellerAddress", sellerAddress)
        .put("status", status.name).put("createdAt", createdAt)
        .putBytes("hashLock", hashLock).put("deadline", deadline ?: JSONObject.NULL)
        .putBytes("key", key)
        .put("sealed", sealed?.let {
            JSONObject().put("kind", it.kind).put("fileName", it.fileName ?: JSONObject.NULL).put("mimeType", it.mimeType ?: JSONObject.NULL)
                .putBytes("nonce", it.nonce).putBytes("ciphertext", it.ciphertext)
        } ?: JSONObject.NULL)
        .put("escrowSeed", escrowSeed ?: JSONObject.NULL)
        .put("escrowId", escrowId ?: JSONObject.NULL)
        .put("refundXdr", refundXdr ?: JSONObject.NULL)
        .put("releaseHash", releaseHash ?: JSONObject.NULL)
        .put("goods", goods?.toJson() ?: JSONObject.NULL)
        .put("refundDone", refundDone)
        .put("note", note ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = Order(
            id = json.getString("id"),
            buying = json.getBoolean("buying"),
            listingId = json.getString("listingId"),
            title = json.getString("title"),
            price = BigDecimal(json.getString("price")),
            counterparty = json.getString("counterparty"),
            buyerAddress = json.getString("buyerAddress"),
            sellerAddress = json.getString("sellerAddress"),
            status = Status.valueOf(json.getString("status")),
            createdAt = json.getLong("createdAt"),
            hashLock = json.bytes("hashLock"),
            deadline = if (json.isNull("deadline")) null else json.getLong("deadline"),
            key = json.bytes("key"),
            sealed = json.optJSONObject("sealed")?.let {
                SealedGoods(it.getString("kind"), it.nullable("fileName"), it.nullable("mimeType"), it.bytes("nonce")!!, it.bytes("ciphertext")!!)
            },
            escrowSeed = json.nullable("escrowSeed"),
            escrowId = json.nullable("escrowId"),
            refundXdr = json.nullable("refundXdr"),
            releaseHash = json.nullable("releaseHash"),
            goods = json.optJSONObject("goods")?.let(::goodsFromJson),
            refundDone = json.optBoolean("refundDone"),
            note = json.nullable("note")
        )

    }
}

internal fun Goods.toJson(): JSONObject =
    JSONObject().put("kind", kind).putBytes("bytes", bytes).put("fileName", fileName ?: JSONObject.NULL).put("mimeType", mimeType ?: JSONObject.NULL)

internal fun goodsFromJson(json: JSONObject) = Goods(json.getString("kind"), json.bytes("bytes")!!, json.nullable("fileName"), json.nullable("mimeType"))

private fun JSONObject.putBytes(key: String, bytes: ByteArray?) = put(key, bytes?.toByteString()?.base64() ?: JSONObject.NULL)
private fun JSONObject.bytes(key: String): ByteArray? = nullable(key)?.decodeBase64()?.toByteArray()
private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
