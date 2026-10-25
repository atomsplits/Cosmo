package com.rivers.cosmo.fonts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Woff2Test {
    private fun resource(name: String) = javaClass.getResourceAsStream("/fonts/$name")!!.use { it.readBytes() }
    private val original = resource("lora-subset.ttf")

    /** Tag → table bytes, read from a TrueType file's table directory. */
    private fun tables(font: ByteArray): Map<String, ByteArray> {
        fun u16(at: Int) = ((font[at].toInt() and 0xff) shl 8) or (font[at + 1].toInt() and 0xff)
        fun u32(at: Int) = (u16(at) shl 16) or u16(at + 2)
        return (0 until u16(4)).associate { i ->
            val record = 12 + 16 * i
            val offset = u32(record + 8)
            String(font, record, 4, Charsets.US_ASCII) to font.copyOfRange(offset, offset + u32(record + 12))
        }
    }

    private fun comparableHead(head: ByteArray) = head.copyOf().also {
        it.fill(0, 8, 12)
        it[16] = (it[16].toInt() and 0x08.inv()).toByte()
    }

    private fun checkValidFont(decoded: ByteArray) {
        // Every table's checksum is right, and the whole file sums to the magic number.
        fun u32(at: Int) = ((decoded[at].toInt() and 0xff) shl 24) or ((decoded[at + 1].toInt() and 0xff) shl 16) or
            ((decoded[at + 2].toInt() and 0xff) shl 8) or (decoded[at + 3].toInt() and 0xff)
        val numTables = ((decoded[4].toInt() and 0xff) shl 8) or (decoded[5].toInt() and 0xff)
        for (i in 0 until numTables) {
            val record = 12 + 16 * i
            val tag = String(decoded, record, 4, Charsets.US_ASCII)
            val expected = if (tag == "head") {
                val table = decoded.copyOfRange(u32(record + 8), u32(record + 8) + u32(record + 12)).also { it.fill(0, 8, 12) }
                Woff2.checksum(table, 0, table.size)
            } else {
                Woff2.checksum(decoded, u32(record + 8), u32(record + 12))
            }
            assertEquals("checksum of $tag", expected, u32(record + 4))
        }
        assertEquals(0xB1B0AFBA.toInt(), Woff2.checksum(decoded, 0, decoded.size))
    }

    private fun checkMatchesOriginal(decoded: ByteArray) {
        val before = tables(original)
        val after = tables(decoded)
        assertEquals(before.keys, after.keys)
        for ((tag, bytes) in before) {
            when (tag) {
                // Rebuilt from WOFF2's transformed form: same glyphs, possibly encoded differently.
                "glyf", "loca" -> Unit
                // Same apart from the checksum and flags bit 11, which WOFF2 encoders set ("transformed").
                "head" -> assertArrayEquals(comparableHead(bytes), comparableHead(after.getValue(tag)))
                else -> assertArrayEquals("table $tag", bytes, after.getValue(tag))
            }
        }
        // loca points into glyf in order and ends at its length (short format: offsets / 2).
        val loca = after.getValue("loca")
        val offsets = (0 until loca.size / 2).map { (((loca[it * 2].toInt() and 0xff) shl 8) or (loca[it * 2 + 1].toInt() and 0xff)) * 2 }
        assertEquals(offsets.sorted(), offsets)
        assertEquals(after.getValue("glyf").size, offsets.last())
    }

    @Test
    fun decodesWoff2ToTheOriginalFont() {
        val decoded = Woff2.decode(resource("lora-subset.woff2"))
        checkValidFont(decoded)
        checkMatchesOriginal(decoded)
    }

    @Test
    fun rebuildsTransformedHorizontalMetrics() {
        val decoded = Woff2.decode(resource("lora-subset-hmtx.woff2"))
        checkValidFont(decoded)
        checkMatchesOriginal(decoded) // includes hmtx, byte for byte
    }

    @Test
    fun readsTheFontsOwnName() {
        assertEquals("Lora Regular", SfntNames.displayName(Woff2.decode(resource("lora-subset.woff2"))))
        assertTrue(SfntNames.isSfnt(original))
        assertNull(SfntNames.displayName(ByteArray(20)))
    }

    @Test
    fun refusesDamagedAndForeignFiles() {
        val woff2 = resource("lora-subset.woff2")
        assertTrue(Woff2.isWoff2(woff2))
        assertThrows(FontFormatException::class.java) { Woff2.decode(original) }
        assertThrows(FontFormatException::class.java) { Woff2.decode(woff2.copyOf(woff2.size / 2)) }
        val corrupted = woff2.copyOf().also { for (i in 60 until 200) it[i] = 0x55 }
        assertThrows(FontFormatException::class.java) { Woff2.decode(corrupted) }
    }

    @Test
    fun keysCustomFonts() {
        assertEquals("custom:abc", FontKeys.custom("abc"))
        assertEquals("abc", FontKeys.customId("custom:abc"))
        assertNull(FontKeys.customId("Inter"))
    }
}
