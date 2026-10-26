package com.rivers.cosmo.chat

import android.content.Context
import android.util.Log
import com.rivers.cosmo.chat.crypto.KeyVault
import com.rivers.cosmo.chat.crypto.Pgp
import com.rivers.cosmo.market.Listing
import com.rivers.cosmo.market.MarketProtocol
import com.rivers.cosmo.util.backgroundScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 *
 * With a [keyServer] (Supabase), the user's public key is also published there, signed off with
 * [accountVerifier], and contacts the relay doesn't know (offline, or since it restarted) are
 * looked up there.
 */
class ChatService(
    context: Context,
    private val userId: String,
    private val serverUrl: String,
    private val keyServer: ChatKeyServer? = null,
    /** The account's phrase verifier, for publishing to [keyServer]; null if it can't be worked out. */
    private val accountVerifier: suspend () -> String? = { null }
) {
    enum class Connection { Connecting, Online, Offline }

    enum class Status { Sending, Delivered, Queued, Failed, Received,
        /** A note to self: kept on this phone, never sent. */
        Saved }

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
        val messages: List<ChatMessage> = emptyList(),
        /** Received messages that arrived while this conversation wasn't open. */
        val unread: Int = 0,
        /** The user's own conversation (notes to self), which stays on this phone. */
        val self: Boolean = false
    )

    data class State(
        val connection: Connection = Connection.Connecting,
        val ownFingerprint: String? = null,
        val conversations: Map<String, Conversation> = emptyMap(),
        val error: String? = null
    )

    private val vault = KeyVault(context)
    private val contacts = ContactKeys(context)
    private val scope = backgroundScope("chat", Dispatchers.Default)
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val pendingKeyRequests = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val pendingListings = ConcurrentHashMap<String, CompletableDeferred<List<Listing>>>()
    private val _marketMessages = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)

    /** Marketplace messages from other users, as (sender's Cosmo ID, text). Only ones with a verified signature arrive. */
    val marketMessages: SharedFlow<Pair<String, String>> = _marketMessages.asSharedFlow()
    private val _newMessages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)

    /** Received chat messages for conversations that aren't open on screen, for notifications. */
    val newMessages: SharedFlow<ChatMessage> = _newMessages.asSharedFlow()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopReconnecting = false
    @Volatile private var viewing: String? = null
    private lateinit var ownKey: PGPSecretKeyRing

    private val keyDirectory = PublicKeyDirectory { cosmoId -> fetchKey(cosmoId) }

    fun start() {
        scope.launch {
            try {
                ownKey = withContext(Dispatchers.IO) { vault.loadOrCreate(userId) }
            } catch (e: Exception) {
                _state.update { it.copy(connection = Connection.Offline, error = "Couldn't load your chat key: ${e.message}") }
                return@launch
            }
            _state.update { it.copy(ownFingerprint = Pgp.fingerprint(Pgp.publicKeyOf(ownKey))) }
            launch { publishKey() }
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

    /**
     * Tells the service which conversation is on screen (null for none): it's marked read, and its
     * new messages don't count as unread or show notifications.
     */
    fun setViewing(contactId: String?) {
        viewing = contactId
        if (contactId != null) markRead(contactId)
    }

    fun markRead(contactId: String) {
        _state.update { state ->
            val conversation = state.conversations[contactId]
            if (conversation == null || conversation.unread == 0) state
            else state.copy(conversations = state.conversations + (contactId to conversation.copy(unread = 0)))
        }
    }

    /** Looks up [contactId]'s key and checks it against the pinned one, then opens the conversation. */
    suspend fun openConversation(contactId: String): Result<Unit> = runCatching {
        if (contactId == userId) {
            _state.update { state ->
                if (contactId in state.conversations) state
                else state.copy(conversations = state.conversations + (contactId to Conversation(contactId, state.ownFingerprint, self = true)))
            }
            return@runCatching
        }
        if (state.value.conversations[contactId]?.keyChanged == false) return@runCatching

        val armoredKey = keyDirectory.fetch(contactId) ?: throw IllegalStateException(
            "$contactId hasn't set up Cosmo chat yet. They need to open Cosmo and connect to chat once before you can message them."
        )
        val conversation = when (val pin = contacts.pin(contactId, armoredKey)) {
            is ContactKeys.PinResult.Trusted -> Conversation(contactId, pin.fingerprint)
            is ContactKeys.PinResult.Changed -> Conversation(contactId, pin.newFingerprint, keyChanged = true)
        }
        _state.update { state ->
            val existing = state.conversations[contactId]
            val merged = conversation.copy(messages = existing?.messages.orEmpty(), unread = existing?.unread ?: 0)
            state.copy(conversations = state.conversations + (contactId to merged))
        }
    }

    fun send(contactId: String, text: String) {
        val conversation = state.value.conversations[contactId] ?: return
        if (conversation.keyChanged || text.isBlank()) return
        if (conversation.self) {
            addMessage(contactId, ChatMessage(UUID.randomUUID().toString(), userId, text.trim(), System.currentTimeMillis(), outgoing = true, Status.Saved))
            return
        }
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

    // Marketplace: listings live on the Cosmo server; sale messages travel like chat messages but
    // aren't shown in conversations.

    fun publishListing(listing: Listing): Boolean = socket?.send(ChatProtocol.publishListing(listing)) == true

    fun unpublishListing(id: String): Boolean = socket?.send(ChatProtocol.unpublishListing(id)) == true

    suspend fun browseListings(): Result<List<Listing>> = runCatching {
        val webSocket = socket ?: throw IllegalStateException("Not connected to the Cosmo server.")
        val requestId = UUID.randomUUID().toString()
        val request = CompletableDeferred<List<Listing>>().also { pendingListings[requestId] = it }
        try {
            webSocket.send(ChatProtocol.browseListings(requestId))
            withTimeoutOrNull(KEY_REQUEST_TIMEOUT_MS) { request.await() } ?: throw IllegalStateException("The Cosmo server didn't answer.")
        } finally {
            pendingListings.remove(requestId)
        }
    }

    /** Sends a marketplace message, encrypted and signed like chat, without adding it to a conversation. */
    suspend fun sendMarket(contactId: String, text: String): Result<Unit> = runCatching {
        val recipientKey = contacts.pinned(contactId) ?: run {
            val armored = keyDirectory.fetch(contactId) ?: throw IllegalStateException("$contactId hasn't used Cosmo yet.")
            (contacts.pin(contactId, armored) as? ContactKeys.PinResult.Trusted)?.publicKey
                ?: throw IllegalStateException("$contactId's key changed. Check it in Chat before trading with them.")
        }
        val ciphertext = withContext(Dispatchers.Default) { Pgp.encryptAndSign(text, recipientKey, ownKey) }
        val webSocket = socket ?: throw IllegalStateException("Not connected to the Cosmo server.")
        check(webSocket.send(ChatProtocol.send(UUID.randomUUID().toString(), contactId, ciphertext))) { "Couldn't send to the Cosmo server." }
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
                    is ChatProtocol.Incoming.Listings -> pendingListings[frame.requestId]?.complete(frame.listings)
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
            pendingKeyRequests.values.forEach { it.completeExceptionally(IllegalStateException("Lost the connection to the chat server.")) }
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
        if (MarketProtocol.isMarket(decrypted.text)) {
            // Sale terms must really come from who they claim to; unverified ones are dropped.
            if (decrypted.verified) _marketMessages.emit(frame.from to decrypted.text)
            return
        }
        _state.update {
            if (frame.from in it.conversations) {
                it
            } else {
                it.copy(conversations = it.conversations + (frame.from to Conversation(frame.from, senderKey?.let(Pgp::fingerprint))))
            }
        }
        val message = ChatMessage(frame.id, frame.from, decrypted.text, frame.sentAt, outgoing = false, Status.Received, decrypted.verified)
        val unseen = frame.from != viewing
        if (addMessage(frame.from, message, countUnread = unseen) && unseen) _newMessages.emit(message)
    }

    /** Publishes this device's chat key to [keyServer], so contacts can find it while we're offline. */
    private suspend fun publishKey() {
        val server = keyServer ?: return
        try {
            val verifier = withContext(Dispatchers.Default) { accountVerifier() } ?: return
            val publicKey = Pgp.publicKeyOf(ownKey)
            val fingerprint = Pgp.fingerprint(publicKey).filterNot { it.isWhitespace() }.uppercase()
            if (!server.publish(userId, verifier, fingerprint, Pgp.armor(publicKey))) {
                Log.w(TAG, "Supabase didn't accept the chat key for $userId (no account there?)")
            }
        } catch (e: Exception) {
            // Chat still works through the relay; contacts just can't find this key while we're offline.
            Log.w(TAG, "Couldn't publish the chat key", e)
        }
    }

    /**
     * Finds [cosmoId]'s public key: first from the relay (which knows everyone connected since it
     * started), then from [keyServer]. Null means nobody has a key for them; throws if neither
     * could be asked.
     */
    private suspend fun fetchKey(cosmoId: String): String? {
        val fromRelay = runCatching { fetchKeyFromRelay(cosmoId) }
        fromRelay.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        fromRelay.getOrNull()?.let { return it }
        val server = keyServer ?: return fromRelay.getOrThrow()
        return try {
            server.fetch(cosmoId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // If the relay couldn't be asked either, that's usually the problem to fix first.
            throw fromRelay.exceptionOrNull() ?: e
        }
    }

    private suspend fun fetchKeyFromRelay(cosmoId: String): String? {
        val webSocket = socket ?: throw IllegalStateException("Not connected to the chat server.")
        val request = pendingKeyRequests.getOrPut(cosmoId) { CompletableDeferred() }
        webSocket.send(ChatProtocol.getKey(cosmoId))
        return withTimeoutOrNull(KEY_REQUEST_TIMEOUT_MS) { request.await() }
            ?: if (request.isCompleted) null else throw IllegalStateException("The chat server didn't answer.")
    }

    /** Adds [message] to its conversation. Returns false if it was already there. */
    private fun addMessage(contactId: String, message: ChatMessage, countUnread: Boolean = false): Boolean {
        var added = false
        _state.update { state ->
            added = false
            val conversation = state.conversations[contactId] ?: return@update state
            if (conversation.messages.any { it.id == message.id }) return@update state
            added = true
            val messages = (conversation.messages + message).sortedBy { it.sentAt }
            val unread = if (countUnread) conversation.unread + 1 else conversation.unread
            state.copy(conversations = state.conversations + (contactId to conversation.copy(messages = messages, unread = unread)))
        }
        return added
    }

    private fun setStatus(messageId: String, status: Status) {
        _state.update { state ->
            state.copy(conversations = state.conversations.mapValues { (_, conversation) ->
                conversation.copy(messages = conversation.messages.map { if (it.id == messageId) it.copy(status = status) else it })
            })
        }
    }

    private companion object {
        const val TAG = "Cosmo"
        const val NORMAL_CLOSURE = 1000
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val KEY_REQUEST_TIMEOUT_MS = 10_000L
    }
}
