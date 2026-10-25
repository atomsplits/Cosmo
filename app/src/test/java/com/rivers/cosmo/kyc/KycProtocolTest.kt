package com.rivers.cosmo.kyc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KycProtocolTest {
    @Test
    fun derivesTheServerAddressFromTheChatAddress() {
        assertEquals("http://10.0.2.2:8080", KycProtocol.httpBaseUrl("ws://10.0.2.2:8080/chat"))
        assertEquals("https://cosmo.example.com", KycProtocol.httpBaseUrl("wss://cosmo.example.com/chat"))
        assertEquals("http://192.168.1.20:9000", KycProtocol.httpBaseUrl(" ws://192.168.1.20:9000/chat "))
    }

    @Test
    fun signsTheSameTextAsTheServerExpects() {
        // Kyc.signedText in server/src/main/kotlin/com/rivers/cosmo/server/Kyc.kt produces the same string.
        assertEquals("cosmo-kyc:token:COSMO-AAAA-AAAA:1761400000000", KycProtocol.signedText("token", "COSMO-AAAA-AAAA", 1761400000000))
    }

    @Test
    fun parsesStatuses() {
        assertEquals(KycStatus.APPROVED, KycProtocol.parseStatus("APPROVED"))
        assertEquals(KycStatus.INCOMPLETE, KycProtocol.parseStatus("SOMETHING_NEW"))
        assertTrue(KycStatus.NEEDS_RESUBMISSION.canVerify)
        assertFalse(KycStatus.IN_REVIEW.canVerify)
        assertFalse(KycStatus.APPROVED.canVerify)
    }
}
