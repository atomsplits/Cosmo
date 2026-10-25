package com.rivers.cosmo.wallet

import com.rivers.cosmo.anchor.AnchorService
import com.rivers.cosmo.anchor.AnchorToml
import com.rivers.cosmo.anchor.StellarToml
import java.net.URI

/**
 * Finds tokens to trade by code, e.g. every BTC on Stellar. Anyone can issue a token called BTC,
 * so only ones vouched for are offered: listed by an anchor from Settings, or by the website the
 * issuer names as its home domain, whose stellar.toml must list that exact token (SEP-1).
 */
class AssetDirectory(private val client: StellarClient, private val anchors: AnchorService) {

    /** Blocks on the network. The user's anchors come first, then other verified issuers by popularity. */
    fun find(code: String, anchorDomains: List<String>): List<TradeAsset> {
        val fromAnchors = anchorDomains.flatMap { domain ->
            val toml = runCatching { anchors.toml(domain) }.getOrNull() ?: return@flatMap emptyList()
            toml.currencies
                .filter { it.code.equals(code, ignoreCase = true) && it.issuer != null }
                .map { TradeAsset(it.code, it.issuer, domain, it.name) }
        }
        val others = mutableListOf<TradeAsset>()
        for (candidate in client.issuersOf(code).filter { it.tomlUrl != null }.take(CANDIDATES)) {
            if (others.size >= MAX_VERIFIED) break
            val domain = homeDomain(candidate) ?: continue
            val toml = runCatching { anchors.tomlUnchecked(domain) }.getOrNull() ?: continue
            if (vouches(toml, candidate)) {
                others += TradeAsset(candidate.code, candidate.issuer, domain, toml.currencies.first { it.issuer == candidate.issuer }.name)
            }
        }
        return (fromAnchors + others).distinctBy { it.key }
    }

    companion object {
        private const val CANDIDATES = 10
        private const val MAX_VERIFIED = 4

        /** The domain from Horizon's toml link, only if it's a plain https://<domain>/.well-known/stellar.toml. */
        fun homeDomain(asset: IssuedAsset): String? {
            val url = asset.tomlUrl ?: return null
            val host = runCatching { URI(url).host }.getOrNull()?.let(StellarToml::normalizeDomain) ?: return null
            return host.takeIf { url == StellarToml.url(it) }
        }

        /** Whether the issuer's website lists this exact token. */
        fun vouches(toml: AnchorToml, asset: IssuedAsset): Boolean =
            toml.currencies.any { it.code == asset.code && it.issuer == asset.issuer }
    }
}
