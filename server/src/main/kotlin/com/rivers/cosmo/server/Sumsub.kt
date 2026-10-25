package com.rivers.cosmo.server

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where a user is in identity verification, as the app shows it. */
@Serializable
enum class KycStatus {
    /** No applicant exists in Sumsub yet. */
    NOT_STARTED,
    /** Started but documents haven't all been submitted. */
    INCOMPLETE,
    /** Submitted; Sumsub (or a reviewer) is checking. */
    IN_REVIEW,
    APPROVED,
    /** Rejected, but the user may fix the problem and resubmit. */
    NEEDS_RESUBMISSION,
    /** Rejected for good. */
    REJECTED
}

/**
 * Sumsub credentials, read from environment variables so they never live in code or in the app.
 * Use a sandbox App Token (starts with "sbx:") while developing.
 */
data class SumsubConfig(
    val appToken: String,
    val secretKey: String,
    val levelName: String,
    /** The separate secret used to sign webhook deliveries; null disables the webhook endpoint. */
    val webhookSecret: String?,
    val baseUrl: String = "https://api.sumsub.com"
) {
    companion object {
        /** Null when SUMSUB_APP_TOKEN or SUMSUB_SECRET_KEY isn't set, which turns KYC off. */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): SumsubConfig? {
            val appToken = env["SUMSUB_APP_TOKEN"]?.takeIf { it.isNotBlank() } ?: return null
            val secretKey = env["SUMSUB_SECRET_KEY"]?.takeIf { it.isNotBlank() } ?: return null
            return SumsubConfig(
                appToken = appToken,
                secretKey = secretKey,
                levelName = env["SUMSUB_LEVEL_NAME"]?.takeIf { it.isNotBlank() } ?: "basic-kyc-level",
                webhookSecret = env["SUMSUB_WEBHOOK_SECRET"]?.takeIf { it.isNotBlank() }
            )
        }
    }
}

/** What the server needs from Sumsub; an interface so tests can stand in for the real API. */
interface SumsubApi {
    /** A short-lived access token that lets the app's SDK verify [userId] (our Cosmo ID). */
    fun createAccessToken(userId: String): String

    /** The user's current verification status, looked up by their Cosmo ID. */
    fun status(userId: String): KycStatus
}

class SumsubException(message: String) : Exception(message)

/**
 * Calls Sumsub's REST API, signing each request as described in
 * https://docs.sumsub.com/reference/authentication.
 */
class HttpSumsubApi(
    private val config: SumsubConfig,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) : SumsubApi {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun createAccessToken(userId: String): String {
        val body = """{"userId":${quote(userId)},"levelName":${quote(config.levelName)},"ttlInSecs":$TOKEN_TTL_SECONDS}"""
        val response = send("POST", "/resources/accessTokens/sdk", body)
        if (response.statusCode() != 200) throw SumsubException("Sumsub refused the token request (${response.statusCode()}): ${response.body()}")
        return json.parseToJsonElement(response.body()).jsonObject["token"]?.jsonPrimitive?.contentOrNull
            ?: throw SumsubException("Sumsub's token response had no token.")
    }

    override fun status(userId: String): KycStatus {
        // Looks the applicant up by our own ID ("externalUserId"), since we never store Sumsub's.
        val path = "/resources/applicants/-;externalUserId=${URLEncoder.encode(userId, Charsets.UTF_8)}/one"
        val response = send("GET", path, body = null)
        return when (response.statusCode()) {
            200 -> statusOf(json.parseToJsonElement(response.body()).jsonObject["review"] as? JsonObject)
            404 -> KycStatus.NOT_STARTED
            else -> throw SumsubException("Sumsub refused the status request (${response.statusCode()}): ${response.body()}")
        }
    }

    private fun send(method: String, pathWithQuery: String, body: String?): HttpResponse<String> {
        val timestamp = clock()
        val request = HttpRequest.newBuilder(URI.create(config.baseUrl + pathWithQuery))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("X-App-Token", config.appToken)
            .header("X-App-Access-Ts", timestamp.toString())
            .header("X-App-Access-Sig", sign(config.secretKey, timestamp, method, pathWithQuery, body))
            .apply {
                if (body == null) {
                    method(method, HttpRequest.BodyPublishers.noBody())
                } else {
                    header("Content-Type", "application/json")
                    method(method, HttpRequest.BodyPublishers.ofString(body))
                }
            }
            .build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw SumsubException("Couldn't reach Sumsub: ${e.message}")
        }
    }

    private fun quote(text: String) = JsonPrimitive(text).toString()

    companion object {
        private const val TOKEN_TTL_SECONDS = 1200

        /**
         * Sumsub's request signature: lowercase hex HMAC-SHA256, keyed by the secret, of
         * timestamp + METHOD + path-with-query + body (nothing for an empty body).
         */
        fun sign(secretKey: String, timestamp: Long, method: String, pathWithQuery: String, body: String?): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
            mac.update("$timestamp${method.uppercase()}$pathWithQuery".toByteArray(Charsets.UTF_8))
            body?.let { mac.update(it.toByteArray(Charsets.UTF_8)) }
            return mac.doFinal().toHex()
        }

        /** Maps an applicant's (or webhook's) `review` object to a [KycStatus]. */
        fun statusOf(review: JsonObject?): KycStatus {
            review ?: return KycStatus.INCOMPLETE
            val reviewStatus = review["reviewStatus"]?.jsonPrimitive?.contentOrNull
            val result = review["reviewResult"] as? JsonObject
            return statusOf(
                reviewStatus = reviewStatus,
                reviewAnswer = result?.get("reviewAnswer")?.jsonPrimitive?.contentOrNull,
                rejectType = result?.get("reviewRejectType")?.jsonPrimitive?.contentOrNull
            )
        }

        fun statusOf(reviewStatus: String?, reviewAnswer: String?, rejectType: String?): KycStatus = when {
            reviewAnswer == "GREEN" -> KycStatus.APPROVED
            reviewAnswer == "RED" && rejectType == "RETRY" -> KycStatus.NEEDS_RESUBMISSION
            reviewAnswer == "RED" -> KycStatus.REJECTED
            reviewStatus in setOf("pending", "queued", "prechecked", "onHold", "awaitingService", "awaitingUser") ->
                KycStatus.IN_REVIEW
            else -> KycStatus.INCOMPLETE // "init", or anything newer we don't know yet
        }
    }
}

/**
 * Checks a Sumsub webhook delivery's `x-payload-digest` header: an HMAC of the raw request body
 * keyed by the webhook secret, with the algorithm named in `x-payload-digest-alg`.
 */
object SumsubWebhook {
    private val algorithms = mapOf(
        "HMAC_SHA1_HEX" to "HmacSHA1",
        "HMAC_SHA256_HEX" to "HmacSHA256",
        "HMAC_SHA512_HEX" to "HmacSHA512"
    )

    fun isAuthentic(rawBody: ByteArray, digest: String?, algorithmHeader: String?, secret: String): Boolean {
        digest ?: return false
        val algorithm = algorithms[algorithmHeader ?: "HMAC_SHA256_HEX"] ?: return false
        val mac = Mac.getInstance(algorithm).apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), algorithm)) }
        val expected = mac.doFinal(rawBody).toHex()
        // Constant-time comparison, so the digest can't be guessed byte by byte from response timing.
        return MessageDigest.isEqual(expected.toByteArray(), digest.lowercase().toByteArray())
    }

    /** The Cosmo ID and new status in a verification webhook, or null for events that don't change it. */
    fun parse(rawBody: String): Pair<String, KycStatus>? {
        val event = runCatching { Json.parseToJsonElement(rawBody).jsonObject }.getOrNull() ?: return null
        val userId = event["externalUserId"]?.jsonPrimitive?.contentOrNull ?: return null
        val result = event["reviewResult"] as? JsonObject
        val status = HttpSumsubApi.statusOf(
            reviewStatus = event["reviewStatus"]?.jsonPrimitive?.contentOrNull,
            reviewAnswer = result?.get("reviewAnswer")?.jsonPrimitive?.contentOrNull,
            rejectType = result?.get("reviewRejectType")?.jsonPrimitive?.contentOrNull
        )
        return userId to status
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
