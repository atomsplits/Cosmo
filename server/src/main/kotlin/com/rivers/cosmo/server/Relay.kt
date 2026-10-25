package com.rivers.cosmo.server

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

/**
 * Relays end-to-end encrypted chat messages between connected users. The server only ever sees
 * PGP ciphertext: it can't read messages, only route them by Cosmo ID.
 */
class Relay(
    /** The public key each Cosmo ID logged in with. */
    val registry: KeyRegistry = InMemoryKeyRegistry(),
    private val marketplace: Marketplace = Marketplace(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val log = LoggerFactory.getLogger(Relay::class.java)
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()
    private val offline = ConcurrentHashMap<String, ArrayDeque<ServerFrame.Message>>()

    fun isOnline(userId: String): Boolean = sessions.containsKey(userId)

    suspend fun handle(session: WebSocketSession) {
        val userId = logIn(session) ?: return
        sessions.put(userId, session)?.close(CloseReason(CloseReason.Codes.NORMAL, "Logged in elsewhere"))
        log.info("{} connected ({} online)", userId, sessions.size)
        try {
            session.sendFrame(ServerFrame.Welcome(userId))
            deliverQueued(userId, session)
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                when (val parsed = parse(frame)) {
                    is ClientFrame.GetKey -> session.sendFrame(ServerFrame.Key(parsed.userId, registry.get(parsed.userId)?.armoredPublicKey))
                    is ClientFrame.Send -> relay(userId, parsed, session)
                    is ClientFrame.Hello -> session.sendFrame(ServerFrame.Error("Already logged in."))
                    is ClientFrame.PublishListing ->
                        marketplace.publish(userId, parsed.listing)?.let { session.sendFrame(ServerFrame.Error(it, id = parsed.listing.id)) }
                    is ClientFrame.UnpublishListing -> marketplace.unpublish(userId, parsed.id)
                    is ClientFrame.BrowseListings -> session.sendFrame(ServerFrame.Listings(parsed.requestId.take(MAX_ID_LENGTH), marketplace.browse()))
                    null -> session.sendFrame(ServerFrame.Error("Unrecognised frame."))
                }
            }
        } finally {
            sessions.remove(userId, session)
            log.info("{} disconnected ({} online)", userId, sessions.size)
        }
    }

    /** Sends a challenge and checks the signed reply. Returns the logged-in Cosmo ID, or null after closing. */
    private suspend fun logIn(session: WebSocketSession): String? {
        val nonce = ByteArray(32).also(random::nextBytes).let { Base64.getEncoder().encodeToString(it) }
        session.sendFrame(ServerFrame.Challenge(nonce))

        val hello = withTimeoutOrNull(LOGIN_TIMEOUT_MS) { session.incoming.firstText() }?.let(::parse)
        if (hello !is ClientFrame.Hello) return session.reject("Expected a hello frame.")

        val key = try {
            withContext(Dispatchers.Default) { HelloVerifier.verify(hello, nonce) }
        } catch (e: IllegalArgumentException) {
            return session.reject(e.message ?: "Log-in failed.")
        }
        val holder = registry.claim(key)
        if (holder.fingerprint != key.fingerprint) {
            return session.reject("${key.userId} is already registered to a different key.")
        }
        return key.userId
    }

    private suspend fun relay(from: String, send: ClientFrame.Send, session: WebSocketSession) {
        val error = when {
            send.id.isEmpty() || send.id.length > MAX_ID_LENGTH -> "Invalid message ID."
            send.ciphertext.length > MAX_CIPHERTEXT_LENGTH -> "Message is too large."
            !send.ciphertext.startsWith(PGP_MESSAGE_HEADER) -> "Messages must be PGP encrypted."
            registry.get(send.to) == null -> "${send.to} hasn't used Cosmo chat yet."
            else -> null
        }
        if (error != null) {
            session.sendFrame(ServerFrame.Error(error, id = send.id))
            return
        }

        val message = ServerFrame.Message(send.id, from, send.ciphertext, clock())
        val recipient = sessions[send.to]
        val delivered = recipient != null && runCatching { recipient.sendFrame(message) }.isSuccess
        if (!delivered) queue(send.to, message)
        session.sendFrame(ServerFrame.Sent(send.id, delivered))
    }

    private fun queue(userId: String, message: ServerFrame.Message) {
        val queue = offline.getOrPut(userId) { ArrayDeque() }
        synchronized(queue) {
            if (queue.size >= MAX_QUEUED_PER_USER) queue.removeFirst()
            queue.addLast(message)
        }
    }

    private suspend fun deliverQueued(userId: String, session: WebSocketSession) {
        val queue = offline.remove(userId) ?: return
        val messages = synchronized(queue) { queue.toList() }
        messages.forEach { session.sendFrame(it) }
    }

    private fun parse(frame: Frame.Text): ClientFrame? =
        try {
            ProtocolJson.decodeFromString(ClientFrame.serializer(), frame.readText())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    private suspend fun WebSocketSession.reject(reason: String): String? {
        sendFrame(ServerFrame.Error(reason, fatal = true))
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason.take(MAX_CLOSE_REASON_LENGTH)))
        return null
    }

    private suspend fun ReceiveChannel<Frame>.firstText(): Frame.Text? {
        for (frame in this) if (frame is Frame.Text) return frame
        return null
    }

    private companion object {
        const val LOGIN_TIMEOUT_MS = 30_000L
        const val MAX_ID_LENGTH = 64
        // Room for marketplace deliveries (up to 48 KB of goods, encrypted twice) as well as chat.
        const val MAX_CIPHERTEXT_LENGTH = 100 * 1024
        const val MAX_QUEUED_PER_USER = 100
        const val MAX_CLOSE_REASON_LENGTH = 120 // Close reasons are limited to 123 bytes.
        const val PGP_MESSAGE_HEADER = "-----BEGIN PGP MESSAGE-----"
    }
}

suspend fun WebSocketSession.sendFrame(frame: ServerFrame) {
    send(Frame.Text(ProtocolJson.encodeToString(ServerFrame.serializer(), frame)))
}
