package com.rivers.cosmo.kyc

/** Where the user is in an anchor's identity verification, as SEP-12 reports it. */
enum class KycStatus {
    /** Verified: the anchor has everything it needs. */
    ACCEPTED,
    /** Submitted and being checked. */
    PROCESSING,
    /** The anchor needs (more) details. Also the state before anything was sent. */
    NEEDS_INFO,
    /** Verification was declined. */
    REJECTED;

    companion object {
        /** Statuses this app version doesn't know yet count as needing info, which keeps the form available. */
        fun parse(name: String): KycStatus = entries.firstOrNull { it.name == name } ?: NEEDS_INFO
    }
}
