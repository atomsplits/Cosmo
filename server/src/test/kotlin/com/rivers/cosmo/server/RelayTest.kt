package com.rivers.cosmo.server

import com.rivers.cosmo.chat.crypto.Pgp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.bouncycastle.openpgp.PGPSecretKeyRing

class RelayTest {
    private val aliceId = "COSMO-AAAA-AAAA"
    private val bobId = "COSMO-BBBB-BBBB"
    private val alice = Pgp.generateKey(aliceId)
    private val bob = Pgp.generateKey(bobId)

    @Test
    fun deliversEncryptedMessagesBetweenOnlineUsers() = relayTest { client ->
        val aliceSession = client.logIn(aliceId, alice)
        val bobSession = client.logIn(bobId, bob)

        aliceSession.sendClient(ClientFrame.GetKey(bobId))
        val bobKey = assertIs<ServerFrame.Key>(aliceSession.receive()).publicKey!!
        val ciphertext = Pgp.encryptAndSign("hi bob", Pgp.readPublicKey(bobKey), alice)
        aliceSession.sendClient(ClientFrame.Send("m1", bobId, ciphertext))
        assertEquals(ServerFrame.Sent("m1", delivered = true), aliceSession.receive())

        val received = assertIs<ServerFrame.Message>(bobSession.receive())
        assertEquals(aliceId, received.from)
        val decrypted = Pgp.decryptAndVerify(received.ciphertext, bob, Pgp.publicKeyOf(alice))
        assertEquals("hi bob", decrypted.text)
        assertTrue(decrypted.verified)
    }

    @Test
    fun queuesMessagesUntilTheRecipientConnects() = relayTest { client ->
        client.logIn(bobId, bob).close()
        withTimeout(10_000) { while (relay.isOnline(bobId)) delay(10) }
        val aliceSession = client.logIn(aliceId, alice)
        val ciphertext = Pgp.encryptAndSign("while you were out", Pgp.publicKeyOf(bob), alice)
        aliceSession.sendClient(ClientFrame.Send("m2", bobId, ciphertext))
        assertEquals(ServerFrame.Sent("m2", delivered = false), aliceSession.receive())

        val bobSession = client.logIn(bobId, bob)
        val received = assertIs<ServerFrame.Message>(bobSession.receive())
        assertEquals("while you were out", Pgp.decryptAndVerify(received.ciphertext, bob, null).text)
    }

    @Test
    fun rejectsAnotherKeyClaimingARegisteredId() = relayTest { client ->
        client.logIn(aliceId, alice).close()
        val impostor = Pgp.generateKey(aliceId)
        val error = client.tryLogIn(aliceId, impostor)
        assertTrue(error.fatal)
        assertEquals("$aliceId is already registered to a different key.", error.message)
    }

    @Test
    fun rejectsKeysNamedForSomeoneElse() = relayTest { client ->
        val error = client.tryLogIn(aliceId, bob)
        assertEquals("The public key doesn't belong to $aliceId.", error.message)
    }

    @Test
    fun rejectsSignaturesOverTheWrongChallenge() = relayTest { client ->
        val session = client.connect()
        assertIs<ServerFrame.Challenge>(session.receive())
        val publicKey = Pgp.armor(Pgp.publicKeyOf(alice))
        session.sendClient(ClientFrame.Hello(aliceId, publicKey, Pgp.sign("some old nonce", alice)))
        assertEquals("The log-in signature is invalid.", assertIs<ServerFrame.Error>(session.receive()).message)
    }

    @Test
    fun refusesPlaintextAndUnknownRecipients() = relayTest { client ->
        client.logIn(bobId, bob)
        val aliceSession = client.logIn(aliceId, alice)
        aliceSession.sendClient(ClientFrame.Send("m3", bobId, "not encrypted"))
        val plaintextError = assertIs<ServerFrame.Error>(aliceSession.receive())
        assertEquals("m3", plaintextError.id)
        assertFalse(plaintextError.fatal)

        aliceSession.sendClient(ClientFrame.GetKey("COSMO-ZZZZ-ZZZZ"))
        assertNull(assertIs<ServerFrame.Key>(aliceSession.receive()).publicKey)
    }

    @Test
    fun listsMarketplaceItemsUnderTheSellersLogin() = relayTest { client ->
        val address = "GAAZI4TCR3TY5OJHCTJC2A4QSY6CJWJH5IAJTGKIN2ER7LBNVKOCCWN7"
        val aliceSession = client.logIn(aliceId, alice)
        aliceSession.sendClient(ClientFrame.PublishListing(ListingDraft("l1", "Wallpaper pack", "4K", "12.50", address, "file")))
        aliceSession.sendClient(ClientFrame.PublishListing(ListingDraft("l2", "", "no title", "1", address, "text")))
        assertEquals("Titles need 1 to 80 characters.", assertIs<ServerFrame.Error>(aliceSession.receive()).message)

        val bobSession = client.logIn(bobId, bob)
        // Bob can't take over or remove Alice's listing.
        bobSession.sendClient(ClientFrame.PublishListing(ListingDraft("l1", "Mine now", "", "1", address, "text")))
        assertEquals("That listing ID is taken.", assertIs<ServerFrame.Error>(bobSession.receive()).message)
        bobSession.sendClient(ClientFrame.UnpublishListing("l1"))
        bobSession.sendClient(ClientFrame.BrowseListings("r1"))
        val listings = assertIs<ServerFrame.Listings>(bobSession.receive())
        assertEquals("r1", listings.requestId)
        val listing = listings.listings.single()
        assertEquals(aliceId, listing.sellerId) // from the login, not from the draft
        assertEquals("12.5", listing.price)

        aliceSession.sendClient(ClientFrame.UnpublishListing("l1"))
        aliceSession.sendClient(ClientFrame.BrowseListings("r2"))
        assertTrue(assertIs<ServerFrame.Listings>(aliceSession.receive()).listings.isEmpty())
    }

    private val relay = Relay()

    private fun relayTest(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { cosmoRelay(relay) }
        block(createClient { install(WebSockets) })
    }

    private suspend fun HttpClient.connect(): DefaultClientWebSocketSession = webSocketSession("/chat")

    private suspend fun HttpClient.logIn(userId: String, key: PGPSecretKeyRing): DefaultClientWebSocketSession {
        val session = connect()
        val challenge = assertIs<ServerFrame.Challenge>(session.receive())
        session.sendClient(ClientFrame.Hello(userId, Pgp.armor(Pgp.publicKeyOf(key)), Pgp.sign(challenge.nonce, key)))
        assertEquals(ServerFrame.Welcome(userId), session.receive())
        return session
    }

    private suspend fun HttpClient.tryLogIn(userId: String, key: PGPSecretKeyRing): ServerFrame.Error {
        val session = connect()
        val challenge = assertIs<ServerFrame.Challenge>(session.receive())
        session.sendClient(ClientFrame.Hello(userId, Pgp.armor(Pgp.publicKeyOf(key)), Pgp.sign(challenge.nonce, key)))
        return assertIs<ServerFrame.Error>(session.receive())
    }

    private suspend fun DefaultClientWebSocketSession.sendClient(frame: ClientFrame) =
        send(ProtocolJson.encodeToString(ClientFrame.serializer(), frame))

    private suspend fun DefaultClientWebSocketSession.receive(): ServerFrame = withTimeout(10_000) {
        val frame = incoming.receive() as Frame.Text
        ProtocolJson.decodeFromString(ServerFrame.serializer(), frame.readText())
    }
}
