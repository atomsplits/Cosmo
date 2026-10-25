package com.rivers.cosmo.server

import com.rivers.cosmo.chat.crypto.Pgp
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bouncycastle.openpgp.PGPSecretKeyRing

class KycTest {
    private val aliceId = "COSMO-AAAA-AAAA"
    private val alice = Pgp.generateKey(aliceId)
    private val now = 1_761_400_000_000L

    private class FakeSumsub : SumsubApi {
        val tokensIssued = mutableListOf<String>()
        var status = KycStatus.IN_REVIEW
        var failing = false
        override fun createAccessToken(userId: String): String {
            tokensIssued += userId
            return "_act-sbx-$userId"
        }
        override fun status(userId: String): KycStatus = if (failing) throw SumsubException("down") else status
    }

    // Expected values computed independently with: printf '%s' "<signing string>" | openssl dgst -sha256 -hmac <secret>
    @Test
    fun signsRequestsTheWaySumsubDocuments() {
        assertEquals(
            "dd43f7a89545090cbb14c4229092b1ac2b55a78116cf0feae945e5656d9ae45d",
            HttpSumsubApi.sign(
                "test-secret", 1607551635, "post",
                "/resources/accessTokens?userId=cfd20712-24a2-4c7d-9ab0-146f3c142335&levelName=basic-kyc-level&ttlInSecs=600",
                body = null
            )
        )
        assertEquals(
            "aa350fd22520d79cf7df111d3e47cf39d4da0d8ffba5b163551c9f81767cff80",
            HttpSumsubApi.sign(
                "test-secret", 1700000000, "POST", "/resources/accessTokens/sdk",
                """{"userId":"COSMO-AAAA-AAAA","levelName":"basic-kyc-level","ttlInSecs":1200}"""
            )
        )
    }

    @Test
    fun mapsReviewResultsToStatuses() {
        assertEquals(KycStatus.APPROVED, HttpSumsubApi.statusOf("completed", "GREEN", null))
        assertEquals(KycStatus.NEEDS_RESUBMISSION, HttpSumsubApi.statusOf("completed", "RED", "RETRY"))
        assertEquals(KycStatus.REJECTED, HttpSumsubApi.statusOf("completed", "RED", "FINAL"))
        assertEquals(KycStatus.IN_REVIEW, HttpSumsubApi.statusOf("pending", null, null))
        assertEquals(KycStatus.IN_REVIEW, HttpSumsubApi.statusOf("onHold", null, null))
        assertEquals(KycStatus.INCOMPLETE, HttpSumsubApi.statusOf("init", null, null))
    }

    @Test
    fun verifiesWebhookSignatures() {
        val body = """{"externalUserId":"COSMO-AAAA-AAAA","type":"applicantReviewed","reviewStatus":"completed","reviewResult":{"reviewAnswer":"GREEN"}}"""
        val digest = "c9a15137ebc5a43092ca6a40cf42081db3c390ac3d943bd0f87490a3c634a8fc"
        assertTrue(SumsubWebhook.isAuthentic(body.toByteArray(), digest, "HMAC_SHA256_HEX", "hook-secret"))
        assertTrue(SumsubWebhook.isAuthentic(body.toByteArray(), digest, null, "hook-secret")) // SHA-256 is the default
        assertFalse(SumsubWebhook.isAuthentic(body.toByteArray(), digest, "HMAC_SHA256_HEX", "wrong-secret"))
        assertFalse(SumsubWebhook.isAuthentic((body + " ").toByteArray(), digest, "HMAC_SHA256_HEX", "hook-secret"))
        assertFalse(SumsubWebhook.isAuthentic(body.toByteArray(), null, null, "hook-secret"))
        assertEquals(aliceId to KycStatus.APPROVED, SumsubWebhook.parse(body))
        assertNull(SumsubWebhook.parse("""{"type":"applicantCreated"}"""))
    }

    @Test
    fun readsConfigFromTheEnvironment() {
        assertNull(SumsubConfig.fromEnvironment(emptyMap()))
        val config = SumsubConfig.fromEnvironment(mapOf("SUMSUB_APP_TOKEN" to "sbx:abc", "SUMSUB_SECRET_KEY" to "s"))!!
        assertEquals("basic-kyc-level", config.levelName)
        assertNull(config.webhookSecret)
    }

    @Test
    fun issuesTokensOnlyToTheKeyOwner() = kycTest { client, sumsub ->
        val ok = client.kycPost("/kyc/token", request("token", aliceId, alice))
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("""{"token":"_act-sbx-$aliceId"}""", ok.bodyAsText())

        // Someone else's key, a signature meant for another action, and a stale request are all refused.
        val mallory = Pgp.generateKey(aliceId)
        assertEquals(HttpStatusCode.Unauthorized, client.kycPost("/kyc/token", request("token", aliceId, mallory)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.kycPost("/kyc/token", request("status", aliceId, alice)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.kycPost("/kyc/token", request("token", aliceId, alice, now - 10 * 60_000)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.kycPost("/kyc/token", request("token", "COSMO-ZZZZ-ZZZZ", Pgp.generateKey("COSMO-ZZZZ-ZZZZ"))).status)
        assertEquals(HttpStatusCode.BadRequest, client.kycPost("/kyc/token", "not json").status)
        assertEquals(listOf(aliceId), sumsub.tokensIssued)
    }

    @Test
    fun reportsStatusAndFallsBackToTheLastWebhook() = kycTest { client, sumsub ->
        assertEquals("""{"status":"IN_REVIEW"}""", client.kycPost("/kyc/status", request("status", aliceId, alice)).bodyAsText())

        val body = """{"externalUserId":"$aliceId","type":"applicantReviewed","reviewStatus":"completed","reviewResult":{"reviewAnswer":"GREEN"}}"""
        assertEquals(HttpStatusCode.Unauthorized, client.post("/kyc/webhook") { header("x-payload-digest", "00"); setBody(body) }.status)
        val accepted = client.post("/kyc/webhook") {
            header("x-payload-digest", "c9a15137ebc5a43092ca6a40cf42081db3c390ac3d943bd0f87490a3c634a8fc")
            header("x-payload-digest-alg", "HMAC_SHA256_HEX")
            setBody(body.replace("\$aliceId", aliceId))
        }
        assertEquals(HttpStatusCode.OK, accepted.status)

        sumsub.failing = true
        assertEquals("""{"status":"APPROVED"}""", client.kycPost("/kyc/status", request("status", aliceId, alice)).bodyAsText())
    }

    @Test
    fun saysWhenSumsubIsNotConfigured() = testApplication {
        application { cosmoRelay(Relay(), Kyc(InMemoryKeyRegistry(), api = null, webhookSecret = null)) }
        val response = client.kycPost("/kyc/token", "{}")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue("SUMSUB_APP_TOKEN" in response.bodyAsText())
    }

    private fun request(action: String, userId: String, key: PGPSecretKeyRing, timestamp: Long = now): String {
        val signature = Pgp.sign(Kyc.signedText(action, userId, timestamp), key)
        return ProtocolJson.encodeToString(KycRequest.serializer(), KycRequest(userId, timestamp, signature))
    }

    private fun kycTest(block: suspend (HttpClient, FakeSumsub) -> Unit) = testApplication {
        val relay = Relay()
        val publicKey = Pgp.publicKeyOf(alice)
        relay.registry.claim(RegisteredKey(aliceId, Pgp.armor(publicKey), org.pgpainless.key.OpenPgpFingerprint.of(publicKey).toString()))
        val sumsub = FakeSumsub()
        application { cosmoRelay(relay, Kyc(relay.registry, sumsub, webhookSecret = "hook-secret", clock = { now })) }
        block(client, sumsub)
    }

    private suspend fun HttpClient.kycPost(path: String, body: String): HttpResponse = post(path) { setBody(body) }
}
