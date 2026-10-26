package com.rivers.cosmo.anchor

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.stellar.sdk.Account
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.Sep10Challenge
import org.stellar.sdk.TimeBounds
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.Asset

class WebAuthTest {
    private val server = KeyPair.random()
    private val user = KeyPair.random()
    private val network = Network.TESTNET
    private val toml = AnchorToml(
        domain = "anchor.example.com",
        networkPassphrase = network.networkPassphrase,
        signingKey = server.accountId,
        webAuthEndpoint = "https://auth.example.com/auth",
        transferServerSep24 = null,
        transferServer = null,
        kycServer = null,
        currencies = emptyList()
    )

    private fun challenge(
        signer: KeyPair = server,
        client: String = user.accountId,
        homeDomain: String = "anchor.example.com",
        webAuthDomain: String = "auth.example.com"
    ): String {
        val now = System.currentTimeMillis() / 1000
        return Sep10Challenge.newChallenge(signer, network, client, homeDomain, webAuthDomain, TimeBounds(now, now + 300))
            .toEnvelopeXdrBase64()
    }

    @Test
    fun signsGenuineChallenges() {
        val signed = WebAuth.signChallenge(challenge(), toml, network, user)
        val signers = Sep10Challenge.verifyChallengeTransactionSigners(
            signed, server.accountId, network, "anchor.example.com", "auth.example.com", setOf(user.accountId)
        )
        assertEquals(setOf(user.accountId), signers)
    }

    @Test
    fun refusesChallengesFromTheWrongServerKey() {
        assertThrows(AnchorException::class.java) { WebAuth.signChallenge(challenge(signer = KeyPair.random()), toml, network, user) }
    }

    @Test
    fun refusesChallengesForAnotherAccount() {
        assertThrows(AnchorException::class.java) {
            WebAuth.signChallenge(challenge(client = KeyPair.random().accountId), toml, network, user)
        }
    }

    @Test
    fun refusesChallengesForAnotherDomain() {
        assertThrows(AnchorException::class.java) { WebAuth.signChallenge(challenge(homeDomain = "evil.example.com"), toml, network, user) }
        assertThrows(AnchorException::class.java) { WebAuth.signChallenge(challenge(webAuthDomain = "evil.example.com"), toml, network, user) }
    }

    @Test
    fun refusesRealTransactionsDisguisedAsChallenges() {
        // A payment out of the user's account, signed by the "server": must never be signed.
        val payment = TransactionBuilder(Account(user.accountId, 100L), network)
            .addOperation(
                PaymentOperation.builder().destination(server.accountId).asset(Asset.createNativeAsset()).amount(BigDecimal("1000")).build()
            )
            .setBaseFee(100)
            .setTimeout(300)
            .build()
            .apply { sign(server) }
        assertThrows(AnchorException::class.java) { WebAuth.signChallenge(payment.toEnvelopeXdrBase64(), toml, network, user) }
    }

    @Test
    fun readsTokenExpiry() {
        // {"alg":"HS256"} . {"sub":"G…","exp":1761400000} . signature
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJHLi4uIiwiZXhwIjoxNzYxNDAwMDAwfQ.c2ln"
        assertEquals(1761400000L, WebAuth.expiresAt(jwt))
        assertNull(WebAuth.expiresAt("not-a-jwt"))
    }
}
