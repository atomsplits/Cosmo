package com.rivers.cosmo.anchor

import com.moandjiezana.toml.Toml
import java.net.URI

/** A failure talking to an anchor, already worded for the user. */
class AnchorException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One asset an organization lists in its stellar.toml. [issuer] is null for assets without one (rare). */
data class TomlCurrency(
    val code: String,
    val issuer: String?,
    val name: String?,
    val description: String?,
    /** What the token stands for off Stellar, e.g. "crypto" with anchorAsset "BTC", or "fiat" and "USD". */
    val anchorAssetType: String?,
    val anchorAsset: String?
)

/**
 * The parts of an organization's stellar.toml (SEP-1) that Cosmo uses: where its sign-in, hosted
 * deposit/withdrawal and KYC servers are, and the assets it issues.
 */
data class AnchorToml(
    val domain: String,
    val networkPassphrase: String?,
    /** The key that signs SEP-10 challenges. */
    val signingKey: String?,
    val webAuthEndpoint: String?,
    /** SEP-24: hosted deposits and withdrawals, e.g. cashing out to a bank. */
    val transferServerSep24: String?,
    /** SEP-6, only used here as the SEP-12 fallback. */
    val transferServer: String?,
    val kycServer: String?,
    val currencies: List<TomlCurrency>
) {
    /** SEP-12 lives at KYC_SERVER, or at the SEP-6 TRANSFER_SERVER when that isn't set. */
    val customerServer: String? get() = kycServer ?: transferServer
    val supportsWebAuth: Boolean get() = signingKey != null && webAuthEndpoint != null
}

object StellarToml {
    /** SEP-1 caps the file at 100 KB. */
    const val MAX_BYTES = 100 * 1024

    fun url(domain: String) = "https://$domain/.well-known/stellar.toml"

    /**
     * Turns what the user typed ("https://TestAnchor.stellar.org/", "testanchor.stellar.org") into a
     * bare lowercase host, or null if it isn't one.
     */
    fun normalizeDomain(input: String): String? {
        val trimmed = input.trim().lowercase()
        if (trimmed.isEmpty()) return null
        val host = runCatching { URI(if ("://" in trimmed) trimmed else "https://$trimmed").host }.getOrNull() ?: return null
        val label = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
        return host.takeIf { Regex("$label(\\.$label)+").matches(it) }
    }

    fun parse(domain: String, text: String): AnchorToml {
        val toml = try {
            Toml().read(text)
        } catch (e: RuntimeException) {
            throw AnchorException("$domain's stellar.toml couldn't be read.", e)
        }
        fun string(key: String, from: Toml = toml): String? =
            runCatching { from.getString(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        // Only https endpoints are used: these carry sign-in tokens and personal details.
        fun endpoint(key: String): String? = string(key)?.trimEnd('/')?.takeIf { it.startsWith("https://") }

        val currencies = runCatching { toml.getTables("CURRENCIES") }.getOrNull().orEmpty().mapNotNull { table ->
            val code = string("code", table) ?: return@mapNotNull null
            TomlCurrency(
                code = code,
                issuer = string("issuer", table),
                name = string("name", table),
                description = string("desc", table),
                anchorAssetType = string("anchor_asset_type", table),
                anchorAsset = string("anchor_asset", table)
            )
        }
        return AnchorToml(
            domain = domain,
            networkPassphrase = string("NETWORK_PASSPHRASE"),
            signingKey = string("SIGNING_KEY"),
            webAuthEndpoint = endpoint("WEB_AUTH_ENDPOINT"),
            transferServerSep24 = endpoint("TRANSFER_SERVER_SEP0024"),
            transferServer = endpoint("TRANSFER_SERVER"),
            kycServer = endpoint("KYC_SERVER"),
            currencies = currencies
        )
    }
}
