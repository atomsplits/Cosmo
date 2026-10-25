package com.rivers.cosmo.chat

import com.rivers.cosmo.market.Listing
import org.json.JSONException
import org.json.JSONObject

/**
 * The JSON frames the app exchanges with the chat relay. This must stay in step with
 * server/src/main/kotlin/com/rivers/cosmo/server/Protocol.kt (described in server/README.md).
 */
object ChatProtocol {

    sealed interface Incoming {
        data class Challenge(val nonce: String) : Incoming
        data class Welcome(val userId: String) : Incoming
        data class Key(val userId: String, val publicKey: String?) : Incoming
        data class Message(val id: String, val from: String, val ciphertext: String, val sentAt: Long) : Incoming
        data class Sent(val id: String, val delivered: Boolean) : Incoming
        data class Error(val message: String, val fatal: Boolean, val id: String?) : Incoming
        data class Listings(val requestId: String, val listings: List<Listing>) : Incoming
    }

    fun hello(userId: String, publicKey: String, signature: String): String =
        frame("hello").put("userId", userId).put("publicKey", publicKey).put("signature", signature).toString()

    fun getKey(userId: String): String = frame("get_key").put("userId", userId).toString()

    fun send(id: String, to: String, ciphertext: String): String =
        frame("send").put("id", id).put("to", to).put("ciphertext", ciphertext).toString()

    fun publishListing(listing: Listing): String = frame("publish_listing").put("listing", listing.toDraftJson()).toString()

    fun unpublishListing(id: String): String = frame("unpublish_listing").put("id", id).toString()

    fun browseListings(requestId: String): String = frame("browse_listings").put("requestId", requestId).toString()

    /** Returns null for frames this version of the app doesn't understand. */
    fun parse(text: String): Incoming? = try {
        val json = JSONObject(text)
        when (json.getString("type")) {
            "challenge" -> Incoming.Challenge(json.getString("nonce"))
            "welcome" -> Incoming.Welcome(json.getString("userId"))
            "key" -> Incoming.Key(json.getString("userId"), json.optStringOrNull("publicKey"))
            "message" -> Incoming.Message(
                json.getString("id"),
                json.getString("from"),
                json.getString("ciphertext"),
                json.getLong("sentAt")
            )
            "sent" -> Incoming.Sent(json.getString("id"), json.getBoolean("delivered"))
            "error" -> Incoming.Error(json.getString("message"), json.optBoolean("fatal"), json.optStringOrNull("id"))
            "listings" -> json.getJSONArray("listings").let { array ->
                // A listing this app can't read is skipped rather than hiding the whole marketplace.
                Incoming.Listings(json.getString("requestId"), List(array.length()) { runCatching { Listing.fromJson(array.getJSONObject(it)) }.getOrNull() }.filterNotNull())
            }
            else -> null
        }
    } catch (e: JSONException) {
        null
    }

    private fun frame(type: String) = JSONObject().put("type", type)

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else getString(name)
}
