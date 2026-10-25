package com.rivers.cosmo.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.pgpainless.PGPainless
import org.slf4j.LoggerFactory

@Serializable
data class KycRequest(val userId: String, val timestamp: Long, val signature: String)

@Serializable
data class KycTokenResponse(val token: String)

@Serializable
data class KycStatusResponse(val status: KycStatus)

@Serializable
data class KycErrorResponse(val error: String)

/**
 * Identity verification through Sumsub. The app never sees the Sumsub secret: it asks this server
 * for a short-lived SDK access token, proving who it is by signing the request with the same PGP
 * key it logs in to chat with.
 *
 * Results arrive through Sumsub's webhook (the source of truth) and can also be read on demand.
 */
class Kyc(
    private val registry: KeyRegistry,
    private val api: SumsubApi?,
    private val webhookSecret: String?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val log = LoggerFactory.getLogger(Kyc::class.java)
    // TODO: Store verification results in Supabase so they survive restarts.
    private val webhookStatuses = ConcurrentHashMap<String, KycStatus>()

    fun routes(route: Route) = with(route) {
        post("/kyc/token") {
            val api = api ?: return@post call.respondError(HttpStatusCode.ServiceUnavailable, NOT_CONFIGURED)
            val userId = authenticate(call, "token") ?: return@post
            try {
                val token = withContext(Dispatchers.IO) { api.createAccessToken(userId) }
                call.respondJson(HttpStatusCode.OK, KycTokenResponse.serializer(), KycTokenResponse(token))
            } catch (e: SumsubException) {
                log.warn("Token request for {} failed: {}", userId, e.message)
                call.respondError(HttpStatusCode.BadGateway, "Couldn't start verification. Try again in a moment.")
            }
        }

        post("/kyc/status") {
            val api = api ?: return@post call.respondError(HttpStatusCode.ServiceUnavailable, NOT_CONFIGURED)
            val userId = authenticate(call, "status") ?: return@post
            val status = try {
                withContext(Dispatchers.IO) { api.status(userId) }
            } catch (e: SumsubException) {
                log.warn("Status request for {} failed: {}", userId, e.message)
                webhookStatuses[userId]
                    ?: return@post call.respondError(HttpStatusCode.BadGateway, "Couldn't check your verification status.")
            }
            call.respondJson(HttpStatusCode.OK, KycStatusResponse.serializer(), KycStatusResponse(status))
        }

        post("/kyc/webhook") {
            val secret = webhookSecret ?: return@post call.respondError(HttpStatusCode.NotFound, "Webhook not configured.")
            val raw = call.receiveText()
            val authentic = SumsubWebhook.isAuthentic(
                raw.toByteArray(Charsets.UTF_8),
                call.request.headers["x-payload-digest"],
                call.request.headers["x-payload-digest-alg"],
                secret
            )
            if (!authentic) return@post call.respondError(HttpStatusCode.Unauthorized, "Invalid signature.")
            SumsubWebhook.parse(raw)?.let { (userId, status) ->
                webhookStatuses[userId] = status
                log.info("Verification status for {} is now {}", userId, status)
            }
            call.respondText("ok")
        }
    }

    /**
     * Checks that the request was signed by the key registered for its Cosmo ID, recently.
     * Responds with an error and returns null if not.
     */
    private suspend fun authenticate(call: ApplicationCall, action: String): String? {
        val request = try {
            ProtocolJson.decodeFromString(KycRequest.serializer(), call.receiveText())
        } catch (e: SerializationException) {
            call.respondError(HttpStatusCode.BadRequest, "Malformed request.")
            return null
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, "Malformed request.")
            return null
        }
        if (Math.abs(clock() - request.timestamp) > MAX_CLOCK_SKEW_MS) {
            call.respondError(HttpStatusCode.Unauthorized, "This request has expired. Check your phone's clock.")
            return null
        }
        val registered = registry.get(request.userId)
        if (registered == null) {
            call.respondError(HttpStatusCode.Unauthorized, "Open the Chat tab once so the server knows your key, then try again.")
            return null
        }
        val publicKey = PGPainless.readKeyRing().publicKeyRing(registered.armoredPublicKey)
        val signed = publicKey != null && withContext(Dispatchers.Default) {
            HelloVerifier.signedBy(request.signature, signedText(action, request.userId, request.timestamp), publicKey)
        }
        if (!signed) {
            call.respondError(HttpStatusCode.Unauthorized, "The request signature is invalid.")
            return null
        }
        return request.userId
    }

    companion object {
        private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
        private const val NOT_CONFIGURED = "Identity verification isn't set up on this server (SUMSUB_APP_TOKEN and SUMSUB_SECRET_KEY)."

        /** The text the app signs; including the action stops a status request being replayed as a token request. */
        fun signedText(action: String, userId: String, timestamp: Long) = "cosmo-kyc:$action:$userId:$timestamp"
    }
}

private suspend fun <T> ApplicationCall.respondJson(status: HttpStatusCode, serializer: KSerializer<T>, value: T) =
    respondText(ProtocolJson.encodeToString(serializer, value), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) =
    respondJson(status, KycErrorResponse.serializer(), KycErrorResponse(message))
