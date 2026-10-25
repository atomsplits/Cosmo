package com.rivers.cosmo.anchor

import com.rivers.cosmo.wallet.StellarNetwork
import com.rivers.cosmo.wallet.WalletBalance
import java.io.Closeable
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.stellar.sdk.KeyPair

/**
 * Talks to Stellar anchors (the businesses that move money on and off Stellar) for one network:
 * reads their stellar.toml, signs in with SEP-10, and makes SEP-24 and SEP-12 calls. Every call
 * blocks on the network, so call them from a background thread.
 */
class AnchorService(private val network: StellarNetwork, private val signer: () -> KeyPair?) : Closeable {
    private class Token(val value: String, val expiresAt: Long)

    private val http = AnchorHttp()
    private val tomls = ConcurrentHashMap<String, AnchorToml>()
    private val tokens = ConcurrentHashMap<String, Token>()

    fun toml(domain: String): AnchorToml = tomls[domain] ?: StellarToml.parse(domain, http.getText(StellarToml.url(domain), StellarToml.MAX_BYTES)).also { toml ->
        if (toml.networkPassphrase != null && toml.networkPassphrase != network.passphrase.networkPassphrase) {
            throw AnchorException("$domain is for a different Stellar network than the one Cosmo is using.")
        }
        tomls[domain] = toml
    }

    /** Reads a stellar.toml at any domain, e.g. an asset issuer's, without the network check or caching. */
    fun tomlUnchecked(domain: String): AnchorToml = StellarToml.parse(domain, http.getText(StellarToml.url(domain), StellarToml.MAX_BYTES))

    // SEP-24

    /** What the anchor pays out, limited to XLM and tokens its stellar.toml says it issues, so the right one gets paid. */
    fun withdrawAssets(domain: String): List<Sep24.WithdrawAsset> {
        val server = sep24Server(domain)
        val currencies = toml(domain).currencies
        return Sep24.parseInfo(http.getJson("$server/info")).mapNotNull { asset ->
            if (asset.code == "native") return@mapNotNull asset
            val issuer = currencies.firstOrNull { it.code == asset.code && it.issuer != null }?.issuer ?: return@mapNotNull null
            asset.copy(issuer = issuer)
        }
    }

    fun startWithdrawal(domain: String, balance: WalletBalance, amount: BigDecimal?): Sep24.Started {
        val server = sep24Server(domain)
        val payload = JSONObject()
            .put("asset_code", if (balance.isNative) "native" else balance.code)
            .put("account", account().accountId)
            .put("lang", Locale.getDefault().language)
        balance.issuer?.let { payload.put("asset_issuer", it) }
        amount?.let { payload.put("amount", it.toPlainString()) }
        return Sep24.parseStarted(http.postJson("$server/transactions/withdraw/interactive", payload, token(domain)))
    }

    fun withdrawal(domain: String, id: String): Sep24.Transaction {
        val url = "${sep24Server(domain)}/transaction".toHttpUrl().newBuilder().addQueryParameter("id", id).build().toString()
        return Sep24.parseTransaction(http.getJson(url, token(domain)))
    }

    // SEP-12

    fun customer(domain: String): Sep12.Customer = Sep12.parseCustomer(http.getJson("${customerServer(domain)}/customer", token(domain)))

    fun updateCustomer(domain: String, fields: Map<String, String>, files: Map<String, Upload>) {
        http.putMultipart("${customerServer(domain)}/customer", fields, files, token(domain))
    }

    override fun close() = http.close()

    // SEP-10

    private fun token(domain: String): String {
        val now = System.currentTimeMillis() / 1000
        tokens[domain]?.takeIf { it.expiresAt - now > TOKEN_MARGIN_SECONDS }?.let { return it.value }
        val toml = toml(domain)
        val endpoint = toml.webAuthEndpoint?.takeIf { toml.supportsWebAuth }
            ?: throw AnchorException("$domain doesn't support signing in with a Stellar wallet.")
        val keyPair = account()
        val challengeUrl = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("account", keyPair.accountId)
            .addQueryParameter("home_domain", domain)
            .build().toString()
        val challenge = http.getJson(challengeUrl).optString("transaction").takeIf { it.isNotBlank() }
            ?: throw AnchorException("$domain didn't send a sign-in request.")
        val signed = WebAuth.signChallenge(challenge, toml, network.passphrase, keyPair)
        val token = http.postJson(endpoint, JSONObject().put("transaction", signed)).optString("token").takeIf { it.isNotBlank() }
            ?: throw AnchorException("$domain didn't accept the sign-in.")
        tokens[domain] = Token(token, WebAuth.expiresAt(token) ?: (now + DEFAULT_TOKEN_SECONDS))
        return token
    }

    private fun account(): KeyPair = signer() ?: throw AnchorException("This device doesn't have your wallet key.")

    private fun sep24Server(domain: String) =
        toml(domain).transferServerSep24 ?: throw AnchorException("$domain doesn't offer cash-outs from a wallet (SEP-24).")

    private fun customerServer(domain: String) =
        toml(domain).customerServer ?: throw AnchorException("$domain doesn't offer identity verification (SEP-12).")

    private companion object {
        const val TOKEN_MARGIN_SECONDS = 60L
        const val DEFAULT_TOKEN_SECONDS = 15 * 60L
    }
}
