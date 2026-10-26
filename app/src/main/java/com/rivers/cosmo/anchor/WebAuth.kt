package com.rivers.cosmo.anchor

import java.net.URI
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.Sep10Challenge
import org.stellar.sdk.exception.InvalidSep10ChallengeException

/**
 * SEP-10 sign-in: the anchor sends a transaction that can never be submitted (sequence number 0),
 * and signing it proves this wallet owns the account. It must be checked before signing, or a
 * malicious server could slip a real payment in instead.
 */
object WebAuth {
    fun signChallenge(challengeXdr: String, toml: AnchorToml, network: Network, keyPair: KeyPair): String {
        val signingKey = toml.signingKey ?: throw AnchorException("${toml.domain} doesn't support signing in with a Stellar wallet.")
        val webAuthDomain = toml.webAuthEndpoint?.let { runCatching { URI(it).host }.getOrNull() }
            ?: throw AnchorException("${toml.domain} doesn't support signing in with a Stellar wallet.")
        val challenge = try {
            Sep10Challenge.readChallengeTransaction(challengeXdr, signingKey, network, toml.domain, webAuthDomain)
        } catch (e: InvalidSep10ChallengeException) {
            throw AnchorException("${toml.domain} sent a sign-in request that doesn't check out, so Cosmo didn't sign it.", e)
        } catch (e: IllegalArgumentException) {
            throw AnchorException("${toml.domain} sent a sign-in request that doesn't check out, so Cosmo didn't sign it.", e)
        }
        if (challenge.clientAccountId != keyPair.accountId) {
            throw AnchorException("${toml.domain} asked to sign in as a different account, so Cosmo didn't sign it.")
        }
        return challenge.transaction.apply { sign(keyPair) }.toEnvelopeXdrBase64()
    }

    /** When a SEP-10 token expires, in seconds since 1970, from its `exp` claim; null if it can't be read. */
    fun expiresAt(jwt: String): Long? = runCatching {
        val payload = jwt.split('.')[1]
        JSONObject(payload.decodeBase64()!!.utf8()).getLong("exp")
    }.getOrNull()
}
