package com.rivers.cosmo.chat

import com.rivers.cosmo.chat.ChatProtocol.Incoming
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Frames here are copied from what the server sends (see server/README.md).
class ChatProtocolTest {
    @Test
    fun parsesServerFrames() {
        assertEquals(Incoming.Challenge("abc"), ChatProtocol.parse("""{"type":"challenge","nonce":"abc"}"""))
        assertEquals(Incoming.Welcome("COSMO-AAAA-AAAA"), ChatProtocol.parse("""{"type":"welcome","userId":"COSMO-AAAA-AAAA"}"""))
        assertEquals(
            Incoming.Key("COSMO-AAAA-AAAA", null),
            ChatProtocol.parse("""{"type":"key","userId":"COSMO-AAAA-AAAA","publicKey":null}""")
        )
        assertEquals(
            Incoming.Message("m1", "COSMO-AAAA-AAAA", "-----BEGIN PGP MESSAGE-----", 42),
            ChatProtocol.parse("""{"type":"message","id":"m1","from":"COSMO-AAAA-AAAA","ciphertext":"-----BEGIN PGP MESSAGE-----","sentAt":42}""")
        )
        assertEquals(Incoming.Sent("m1", false), ChatProtocol.parse("""{"type":"sent","id":"m1","delivered":false}"""))
        assertEquals(
            Incoming.Error("nope", true, null),
            ChatProtocol.parse("""{"type":"error","message":"nope","fatal":true,"id":null}""")
        )
    }

    @Test
    fun ignoresUnknownOrBrokenFrames() {
        assertNull(ChatProtocol.parse("""{"type":"something_new"}"""))
        assertNull(ChatProtocol.parse("not json"))
    }

    @Test
    fun buildsClientFrames() {
        val send = JSONObject(ChatProtocol.send("m1", "COSMO-BBBB-BBBB", "cipher"))
        assertEquals("send", send.getString("type"))
        assertEquals("COSMO-BBBB-BBBB", send.getString("to"))
        assertEquals("get_key", JSONObject(ChatProtocol.getKey("COSMO-BBBB-BBBB")).getString("type"))
        assertEquals("sig", JSONObject(ChatProtocol.hello("COSMO-AAAA-AAAA", "key", "sig")).getString("signature"))
    }
}
