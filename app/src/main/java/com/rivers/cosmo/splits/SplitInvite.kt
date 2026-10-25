package com.rivers.cosmo.splits

import com.rivers.cosmo.wallet.StellarNetwork
import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import org.bouncycastle.util.encoders.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.stellar.sdk.KeyPair

class InvalidInviteException(message: String) : Exception(message)

/**
 * Bill split invites: links like `cosmo://split?d=…&s=…&p=2` (also shown as QR codes) that carry
 * the whole split, so no server is needed. `d` is the split as JSON, `s` is the organizer's
 * Stellar signature over `d`, and the optional `p` says which share the invite is for.
 *
 * The signature proves the invite was made by whoever controls the address being paid, and that
 * nobody changed the amounts or address after it was shared.
 */
object SplitInvite {
    const val SCHEME = "cosmo"
    const val HOST = "split"
    private const val VERSION = 1

    data class Signed(val payload: String, val signature: String)

    data class Opened(val split: BillSplit, val signed: Signed, val shareIndex: Int?)

    fun sign(split: BillSplit, signer: KeyPair): Signed {
        require(signer.accountId == split.organizerAddress) { "Only the organizer can sign an invite." }
        val payload = base64Url(toJson(split).toString().toByteArray(Charsets.UTF_8))
        return Signed(payload, base64Url(signer.sign(payload.toByteArray(Charsets.US_ASCII))))
    }

    fun link(signed: Signed, shareIndex: Int? = null): String =
        "$SCHEME://$HOST?d=${signed.payload}&s=${signed.signature}" + (shareIndex?.let { "&p=$it" } ?: "")

    /** Reads and checks an invite link. Throws [InvalidInviteException] if it's damaged or forged. */
    fun open(link: String): Opened {
        val uri = runCatching { URI(link.trim()) }.getOrNull()
        if (uri?.scheme != SCHEME || uri.host != HOST) throw InvalidInviteException("That isn't a Cosmo bill split invite.")
        val params = uri.rawQuery.orEmpty().split("&").mapNotNull { part ->
            part.split("=", limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k to URLDecoder.decode(v, "UTF-8") }
        }.toMap()
        val signed = Signed(params["d"] ?: throw damaged(), params["s"] ?: throw damaged())
        return Opened(verify(signed), signed, params["p"]?.toIntOrNull())
    }

    /** Checks [signed]'s signature and returns the split it describes. */
    fun verify(signed: Signed): BillSplit {
        val split = try {
            fromJson(JSONObject(String(fromBase64Url(signed.payload), Charsets.UTF_8)))
        } catch (e: RuntimeException) {
            // Bad base64, malformed JSON, missing fields or unreadable amounts.
            throw damaged()
        }
        val valid = runCatching {
            KeyPair.fromAccountId(split.organizerAddress).verify(signed.payload.toByteArray(Charsets.US_ASCII), fromBase64Url(signed.signature))
        }.getOrDefault(false)
        if (!valid) throw InvalidInviteException("This invite's signature doesn't match. It may have been altered, so don't pay it.")
        return split
    }

    private fun damaged() = InvalidInviteException("This invite link is incomplete or damaged.")

    private fun toJson(split: BillSplit) = JSONObject()
        .put("v", VERSION)
        .put("id", split.id)
        .put("t", split.title)
        .put("o", split.organizerCosmoId)
        .put("a", split.organizerAddress)
        .put("n", split.network.name)
        .put("c", split.assetCode)
        .put("i", split.assetIssuer ?: JSONObject.NULL)
        .put("at", split.createdAt)
        .put("s", JSONArray().apply {
            split.shares.forEach { put(JSONObject().put("l", it.label).put("x", it.amount.toPlainString()).put("org", it.isOrganizer)) }
        })

    private fun fromJson(json: JSONObject): BillSplit {
        if (json.optInt("v") != VERSION) throw InvalidInviteException("This invite was made by a newer version of Cosmo. Update the app to open it.")
        val sharesJson = json.getJSONArray("s")
        if (sharesJson.length() == 0 || sharesJson.length() > BillSplit.MAX_SHARES) throw damaged()
        val shares = (0 until sharesJson.length()).map { i ->
            val share = sharesJson.getJSONObject(i)
            val amount = BigDecimal(share.getString("x"))
            if (amount.signum() <= 0 || amount.scale() > 7) throw damaged()
            SplitShare(share.getString("l").take(40), amount, share.optBoolean("org"))
        }
        return BillSplit(
            id = json.getString("id"),
            title = json.getString("t").take(60),
            organizerCosmoId = json.getString("o"),
            organizerAddress = json.getString("a"),
            network = StellarNetwork.entries.firstOrNull { it.name == json.getString("n") } ?: throw damaged(),
            assetCode = json.getString("c"),
            assetIssuer = if (json.isNull("i")) null else json.getString("i"),
            shares = shares,
            createdAt = json.getLong("at")
        )
    }

    private fun base64Url(bytes: ByteArray): String =
        String(Base64.encode(bytes), Charsets.US_ASCII).replace('+', '-').replace('/', '_').trimEnd('=')

    private fun fromBase64Url(text: String): ByteArray {
        val standard = text.replace('-', '+').replace('_', '/')
        return Base64.decode(standard + "=".repeat((4 - standard.length % 4) % 4))
    }
}
