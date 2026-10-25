package com.rivers.cosmo.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON frames exchanged over the WebSocket. Every frame has a "type" field naming it.
 * See server/README.md for the full log-in and messaging flow.
 */
val ProtocolJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Frames the app sends. */
@Serializable
sealed interface ClientFrame {
    /** Logs in: the Cosmo ID, its armored public key, and the server's challenge signed with that key. */
    @Serializable
    @SerialName("hello")
    data class Hello(val userId: String, val publicKey: String, val signature: String) : ClientFrame

    /** Asks for another user's public key. */
    @Serializable
    @SerialName("get_key")
    data class GetKey(val userId: String) : ClientFrame

    /** Relays an already encrypted PGP message to another user. */
    @Serializable
    @SerialName("send")
    data class Send(val id: String, val to: String, val ciphertext: String) : ClientFrame
}

/** Frames the server sends. */
@Serializable
sealed interface ServerFrame {
    @Serializable
    @SerialName("challenge")
    data class Challenge(val nonce: String) : ServerFrame

    @Serializable
    @SerialName("welcome")
    data class Welcome(val userId: String) : ServerFrame

    /** [publicKey] is null when that Cosmo ID has never logged in to chat. */
    @Serializable
    @SerialName("key")
    data class Key(val userId: String, val publicKey: String?) : ServerFrame

    @Serializable
    @SerialName("message")
    data class Message(val id: String, val from: String, val ciphertext: String, val sentAt: Long) : ServerFrame

    /** Confirms a send; [delivered] is false when the recipient was offline and the message was queued. */
    @Serializable
    @SerialName("sent")
    data class Sent(val id: String, val delivered: Boolean) : ServerFrame

    /** When [fatal] is true the server closes the connection after sending this. */
    @Serializable
    @SerialName("error")
    data class Error(val message: String, val fatal: Boolean = false, val id: String? = null) : ServerFrame
}
