package com.rivers.cosmo.kyc

import java.net.URI

/** Where a user is in identity verification. Must match KycStatus in server/.../Sumsub.kt. */
enum class KycStatus {
    NOT_STARTED,
    INCOMPLETE,
    IN_REVIEW,
    APPROVED,
    NEEDS_RESUBMISSION,
    REJECTED;

    /** Whether the user can open the verification flow (to start, finish, or fix their submission). */
    val canVerify: Boolean get() = this == NOT_STARTED || this == INCOMPLETE || this == NEEDS_RESUBMISSION
}

/** The pieces of the KYC request format shared with the Cosmo server (see server/README.md). */
object KycProtocol {
    /** The text the app signs with its PGP key. Must match Kyc.signedText on the server. */
    fun signedText(action: String, userId: String, timestamp: Long) = "cosmo-kyc:$action:$userId:$timestamp"

    /** The HTTP address of the Cosmo server, from its chat address: ws://host:8080/chat → http://host:8080. */
    fun httpBaseUrl(chatServerUrl: String): String {
        val uri = URI(chatServerUrl.trim())
        val scheme = if (uri.scheme.equals("wss", ignoreCase = true)) "https" else "http"
        return "$scheme://${uri.rawAuthority}"
    }

    /** Statuses this app version doesn't know yet count as unfinished, which keeps verification available. */
    fun parseStatus(name: String): KycStatus = KycStatus.entries.firstOrNull { it.name == name } ?: KycStatus.INCOMPLETE
}
