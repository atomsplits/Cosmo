package com.rivers.cosmo.market

import android.content.Context
import com.rivers.cosmo.security.DeviceSecretStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's listings, the goods behind them, and their orders, encrypted on this device (sale
 * keys and purchased goods are secrets). The "market" folder is excluded from backups.
 */
class MarketStore(context: Context, userId: String, network: String) {
    private val secrets = DeviceSecretStore(context, "market")
    private val prefix = "$userId-$network"

    fun listings(): List<Listing> = readArray("$prefix-listings").map { Listing.fromJson(it) }

    fun saveListings(listings: List<Listing>) = writeArray("$prefix-listings", listings.map { it.toDraftJson().put("sellerId", it.sellerId).put("publishedAt", it.publishedAt) })

    fun goods(listingId: String): Goods? = runCatching {
        goodsFromJson(JSONObject(String(secrets.read("$prefix-goods-$listingId") ?: return null, Charsets.UTF_8)))
    }.getOrNull()

    fun saveGoods(listingId: String, goods: Goods) =
        secrets.write("$prefix-goods-$listingId", goods.toJson().toString().toByteArray(Charsets.UTF_8))

    fun deleteGoods(listingId: String) = secrets.delete("$prefix-goods-$listingId")

    fun orders(): List<Order> = readArray("$prefix-orders").mapNotNull { runCatching { Order.fromJson(it) }.getOrNull() }

    fun saveOrders(orders: List<Order>) = writeArray("$prefix-orders", orders.map { it.toJson() })

    private fun readArray(name: String): List<JSONObject> = runCatching {
        val array = JSONArray(String(secrets.read(name) ?: return emptyList(), Charsets.UTF_8))
        List(array.length()) { array.getJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun writeArray(name: String, items: List<JSONObject>) {
        secrets.write(name, JSONArray(items).toString().toByteArray(Charsets.UTF_8))
    }
}
