package com.rivers.cosmo.market

import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketTest {
    private val address = "GAAZI4TCR3TY5OJHCTJC2A4QSY6CJWJH5IAJTGKIN2ER7LBNVKOCCWN7"

    @Test
    fun sealsGoodsSoOnlyTheKeyOpensThem() {
        val goods = Goods(Listing.TEXT, "LICENSE-1234-ABCD".toByteArray())
        val key = GoodsCrypto.newKey()
        val sealed = GoodsCrypto.seal(goods, key)
        assertEquals("LICENSE-1234-ABCD", GoodsCrypto.open(sealed, key).text)
        assertThrows(Exception::class.java) { GoodsCrypto.open(sealed, GoodsCrypto.newKey()) }
        val tampered = sealed.copy(ciphertext = sealed.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertThrows(Exception::class.java) { GoodsCrypto.open(tampered, key) }
    }

    @Test
    fun roundTripsEveryMessage() {
        val sealed = GoodsCrypto.seal(Goods(Listing.FILE, byteArrayOf(1, 2, 3), "pack.zip", "application/zip"), GoodsCrypto.newKey())
        val messages = listOf(
            MarketMessage.Buy("o1", "l1", address),
            MarketMessage.Offer("o1", "l1", "Wallpapers", BigDecimal("12.5"), address, ByteArray(32) { 7 }, 1_800_000_000L, sealed),
            MarketMessage.Decline("o1", "Sold out"),
            MarketMessage.Funded("o1", address, "AAAA"),
            MarketMessage.Released("o1", "abc123")
        )
        for (message in messages) {
            val text = MarketProtocol.encode(message)
            assertTrue(MarketProtocol.isMarket(text))
            val parsed = MarketProtocol.parse(text)
            if (message is MarketMessage.Offer) {
                parsed as MarketMessage.Offer
                assertArrayEquals(message.hashLock, parsed.hashLock)
                assertArrayEquals(message.goods.ciphertext, parsed.goods.ciphertext)
                assertEquals("pack.zip", parsed.goods.fileName)
                assertEquals(0, message.price.compareTo(parsed.price))
            } else {
                assertEquals(message, parsed)
            }
        }
    }

    @Test
    fun ignoresOrdinaryChatAndMalformedMessages() {
        assertFalse(MarketProtocol.isMarket("hey, is this still for sale?"))
        assertNull(MarketProtocol.parse("""{"cosmoMarket":2,"type":"buy","orderId":"o","listingId":"l","buyerAddress":"G"}"""))
        assertNull(MarketProtocol.parse("""{"cosmoMarket":1,"type":"offer","orderId":"o"}"""))
        assertNull(MarketProtocol.parse("""{"cosmoMarket":1,"type":"buy","orderId":"","listingId":"l","buyerAddress":"G"}"""))
        assertNull(MarketProtocol.parse("not json"))
    }

    @Test
    fun keepsOrdersAcrossRestarts() {
        val order = Order(
            id = "o1", buying = true, listingId = "l1", title = "Wallpapers", price = BigDecimal("12.5"),
            counterparty = "COSMO-AAAA-AAAA", buyerAddress = address, sellerAddress = address,
            status = Order.Status.Funded, createdAt = 5L, hashLock = ByteArray(32) { 1 }, deadline = 100L,
            sealed = SealedGoods(Listing.TEXT, null, null, ByteArray(12), byteArrayOf(9, 9)),
            escrowId = address, refundXdr = "AAAA", goods = Goods(Listing.TEXT, "hi".toByteArray())
        )
        val restored = Order.fromJson(JSONObject(order.toJson().toString()))
        assertEquals(order.status, restored.status)
        assertArrayEquals(order.hashLock, restored.hashLock)
        assertArrayEquals(order.sealed!!.ciphertext, restored.sealed!!.ciphertext)
        assertEquals("hi", restored.goods!!.text)
        assertEquals(order.terms!!.deadline, restored.terms!!.deadline)

        assertFalse(order.canRefund(nowSeconds = 99))
        assertTrue(order.canRefund(nowSeconds = 100))
        assertFalse(order.copy(refundDone = true).canRefund(nowSeconds = 100))
        assertFalse(order.copy(buying = false).canRefund(nowSeconds = 100))
    }

    @Test
    fun readsListings() {
        val listing = Listing.fromJson(
            JSONObject("""{"id":"l1","sellerId":"COSMO-AAAA-AAAA","title":"Pack","description":"","price":"12.5","sellerAddress":"$address","kind":"file","publishedAt":3}""")
        )
        assertEquals(BigDecimal("12.5"), listing.price)
        assertEquals("12.5", listing.toDraftJson().getString("price"))
        assertFalse(listing.toDraftJson().has("sellerId")) // the server takes that from the login
    }
}
