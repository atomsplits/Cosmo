package com.rivers.cosmo.chat

import android.content.Context
import com.rivers.cosmo.chat.crypto.KeyVault
import com.rivers.cosmo.chat.crypto.Pgp
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.bouncycastle.openpgp.PGPSecretKeyRing

/**
 * End-to-end encrypted chat for one signed-in user.
 *
 * On [start] it loads (or generates) the user's PGP key, connects to the relay at [serverUrl] and
 * proves it owns that key. Messages are encrypted to the recipient and signed before they leave
 * the phone, so the relay only ever sees ciphertext. Conversations are kept in memory for now.
 */
class ChatService(
    context: Context,
    private val userId: String,
    private val serverUrl: String
) {
    enum class Connection { Connecting, Online, Offline }

    enum class Status { Sending, Delivered, Queued, Failed, Received }

    data class ChatMessage(
        val id: String,
        val from: String,
        val text: String,
        val sentAt: Long,
        val outgoing: Boolean,
        val status: Status,
        /** For received messages: whether the signature matched the sender's pinned key. */
        val verified: Boolean = true
    )

    data class Conversation(
        val contactId: String,
        val fingerprint: String?,
        /** Set when the server offered a different key than the one pinned for this contact. */
        val keyChanged: Boolean = false,
        val messages: List<ChatMessage> = emptyList()
    )

    data class State(
        val connection: Connection = Connection.Connecting,
        val ownFingerprint: String? = null,
        val conversations: Map<String, Conversation> = emptyMap(),
        val error: String? = null
    )

    private val vault = KeyVault(context)
    private val contacts = ContactKeys(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val pendingKeyRequests = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopReconnecting = false
    private lateinit var ownKey: PGPSecretKeyRing

    // TODO: Swap for a Supabase-backed directory once public keys are stored there.
    private val keyDirectory = PublicKeyDirectory { cosmoId -> fetchKeyFromRelay(cosmoId) }

    fun start() {
        scope.launch {
            try {
                ownKey = withContext(Dispatchers.IO) { vault.loadOrCreate(userId) }
            } catch (e: Exception) {
                _state.update { it.copy(connection = Connection.Offline, error = "Couldn't load your chat key: ${e.message}") }
                return@launch
            }
            _state.update { it.copy(ownFingerprint = Pgp.fingerprint(Pgp.publicKeyOf(ownKey))) }
            connectLoop()
        }
    }

    /** Tries to connect again after the server refused the connection. */
    fun reconnect() {
        if (!::ownKey.isInitialized || !stopReconnecting) return
        stopReconnecting = false
        scope.launch { connectLoop() }
    }

    fun close() {
        stopReconnecting = true
        socket?.close(NORMAL_CLOSURE, null)
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    /** Looks up [contactId]'s key and checks it against the pinned one, then opens the conversation. */
    suspend fun openConversation(contactId: String): Result<Unit> = runCatching {
        require(contactId != userId) { "That's your own Cosmo ID." }
        if (state.value.conversations[contactId]?.keyChanged == false) return@runCatching

        val armoredKey = keyDirectory.fetch(contactId)
            ?: throw IllegalStateException("$contactId hasn't used Cosmo chat yet.")
        val conversation = when (val pin = contacts.pin(contactId, armoredKey)) {
            is ContactKeys.PinResult.Trusted -> Conversation(contactId, pin.fingerprint)
            is ContactKeys.PinResult.Changed -> Conversation(contactId, pin.newFingerprint, keyChanged = true)
        }
        _state.update { state ->
            val existing = state.conversations[contactId]
            state.copy(conversations = state.conversations + (contactId to conversation.copy(messages = existing?.messages.orEmpty())))
        }
    }

    fun send(contactId: String, text: String) {
        val conversation = state.value.conversations[contactId] ?: return
        if (conversation.keyChanged || text.isBlank()) return
        val message = ChatMessage(UUID.randomUUID().toString(), userId, text.trim(), System.currentTimeMillis(), outgoing = true, Status.Sending)
        addMessage(contactId, message)

        scope.launch {
            val sent = runCatching {
                val recipientKey = checkNotNull(contacts.pinned(contactId))
                val ciphertext = Pgp.encryptAndSign(message.text, recipientKey, ownKey)
                socket?.send(ChatProtocol.send(message.id, contactId, ciphertext)) == true
            }.getOrDefault(false)
            if (!sent) setStatus(message.id, Status.Failed)
        }
    }

    private suspend fun connectLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.isActive && !stopReconnecting) {
            _state.update { it.copy(connection = Connection.Connecting) }
            val wasOnline = connectOnce()
            _state.update { it.copy(connection = Connection.Offline) }
            if (stopReconnecting) break
            backoffMs = if (wasOnline) INITIAL_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            delay(backoffMs)
        }
    }

    /** Connects and handles frames until the connection closes. Returns whether it got logged in. */
    private suspend fun connectOnce(): Boolean {
        val frames = Channel<ChatProtocol.Incoming>(Channel.UNLIMITED)
        val webSocket = client.newWebSocket(Request.Builder().url(serverUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                ChatProtocol.parse(text)?.let { frames.trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                frames.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.update { it.copy(error = "Can't reach the chat server at $serverUrl (${t.message}).") }
                frames.close()
            }
        })

        var loggedIn = false
        try {
            for (frame in frames) {
                when (frame) {
                    is ChatProtocol.Incoming.Challenge -> {
                        val publicKey = Pgp.armor(Pgp.publicKeyOf(ownKey))
                        webSocket.send(ChatProtocol.hello(userId, publicKey, Pgp.sign(frame.nonce, ownKey)))
                    }
                    is ChatProtocol.Incoming.Welcome -> {
                        loggedIn = true
                        socket = webSocket
                        _state.update { it.copy(connection = Connection.Online, error = null) }
                    }
                    is ChatProtocol.Incoming.Key -> pendingKeyRequests.remove(frame.userId)?.complete(frame.publicKey)
                    // Decrypting may need to look up the sender's key, which arrives on this same
                    // connection, so it can't block this loop.
                    is ChatProtocol.Incoming.Message -> scope.launch { receive(frame) }
                    is ChatProtocol.Incoming.Sent -> setStatus(frame.id, if (frame.delivered) Status.Delivered else Status.Queued)
                    is ChatProtocol.Incoming.Error -> {
                        if (frame.id != null) setStatus(frame.id, Status.Failed)
                        _state.update { it.copy(error = frame.message) }
                        // A refused log-in (e.g. this ID is registered to another key) won't fix itself.
                        if (frame.fatal) stopReconnecting = true
                    }
                }
            }
        } finally {
            if (socket === webSocket) socket = null
            webSocket.cancel()
            pendingKeyRequests.values.forEach { it.complete(null) }
            pendingKeyRequests.clear()
        }
        return loggedIn
    }

    private suspend fun receive(frame: ChatProtocol.Incoming.Message) {
        val senderKey = contacts.pinned(frame.from) ?: runCatching {
            val armored = keyDirectory.fetch(frame.from) ?: return@runCatching null
            (contacts.pin(frame.from, armored) as? ContactKeys.PinResult.Trusted)?.publicKey
        }.getOrNull()

        val decrypted = runCatching { Pgp.decryptAndVerify(frame.ciphertext, ownKey, senderKey) }.getOrElse {
            Pgp.Decrypted("This message couldn't be decrypted.", verified = false)
        }
        _state.update {
            if (frame.from in it.conversations) {
                it
            } else {
                it.copy(conversations = it.conversations + (frame.from to Conversation(frame.from, senderKey?.let(Pgp::fingerprint))))
            }
        }
        addMessage(
            frame.from,
            ChatMessage(frame.id, frame.from, decrypted.text, frame.sentAt, outgoing = false, Status.Received, decrypted.verified)
        )
    }

    private suspend fun fetchKeyFromRelay(cosmoId: String): String? {
        val webSocket = socket ?: throw IllegalStateException("Not connected to the chat server.")
        val request = pendingKeyRequests.getOrPut(cosmoId) { CompletableDeferred() }
        webSocket.send(ChatProtocol.getKey(cosmoId))
        return withTimeoutOrNull(KEY_REQUEST_TIMEOUT_MS) { request.await() }
    }

    private fun addMessage(contactId: String, message: ChatMessage) {
        _state.update { state ->
            val conversation = state.conversations[contactId] ?: return@update state
            if (conversation.messages.any { it.id == message.id }) return@update state
            val messages = (conversation.messages + message).sortedBy { it.sentAt }
            state.copy(conversations = state.conversations + (contactId to conversation.copy(messages = messages)))
        }
    }

    private fun setStatus(messageId: String, status: Status) {
        _state.update { state ->
            state.copy(conversations = state.conversations.mapValues { (_, conversation) ->
                conversation.copy(messages = conversation.messages.map { if (it.id == messageId) it.copy(status = status) else it })
            })
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val KEY_REQUEST_TIMEOUT_MS = 10_000L
    }
}
