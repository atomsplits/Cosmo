package com.rivers.cosmo.fonts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.brotli.dec.BrotliInputStream

/** The file isn't a font Cosmo can use; the message is worded for the user. */
class FontFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a WOFF2 web font into the plain TrueType/OpenType file Android can load, following the
 * W3C WOFF2 spec (https://www.w3.org/TR/WOFF2/): Brotli-decompress the tables, then rebuild the
 * glyf, loca and hmtx tables that WOFF2 stores in a smaller, transformed form.
 */
object Woff2 {
    private const val SIGNATURE = 0x774F4632 // "wOF2"
    private const val COLLECTION = 0x74746366 // "ttcf"
    /** Fonts this big are almost certainly not what the user meant; also caps memory use. */
    const val MAX_FONT_BYTES = 30 * 1024 * 1024

    private val GLYF = tag("glyf")
    private val LOCA = tag("loca")
    private val HMTX = tag("hmtx")
    private val HHEA = tag("hhea")
    private val HEAD = tag("head")

    private val KNOWN_TAGS = listOf(
        "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ",
        "VORG", "EBDT", "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea", "vmtx", "BASE", "GDEF", "GPOS",
        "GSUB", "EBSC", "JSTF", "MATH", "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt", "avar", "bdat", "bloc",
        "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar", "gvar", "hsty", "just", "lcar", "mort", "morx", "opbd", "prop",
        "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill"
    ).map(::tag)

    fun isWoff2(bytes: ByteArray) = bytes.size >= 4 && Reader(bytes).u32() == SIGNATURE

    private class Entry(val tag: Int, val origLength: Long, val transformed: Boolean, val storedLength: Long) {
        var data: ByteArray = ByteArray(0)
    }

    fun decode(woff2: ByteArray): ByteArray = try {
        decodeChecked(woff2)
    } catch (e: FontFormatException) {
        throw e
    } catch (e: IOException) {
        throw FontFormatException("That font file is damaged.", e)
    } catch (e: RuntimeException) {
        // Malformed files show up as out-of-range reads; never crash on a user's file.
        throw FontFormatException("That font file is damaged.", e)
    }

    private fun decodeChecked(woff2: ByteArray): ByteArray {
        val r = Reader(woff2)
        if (r.u32() != SIGNATURE) throw FontFormatException("That isn't a WOFF2 font.")
        val flavor = r.u32()
        if (flavor == COLLECTION) throw FontFormatException("Font collections aren't supported; pick a single font.")
        val length = r.u32()
        if (length.toLong() != woff2.size.toLong()) throw FontFormatException("That font file is incomplete.")
        val numTables = r.u16()
        r.skip(2) // reserved
        val totalSfntSize = r.u32().toLong() and 0xffffffffL
        val totalCompressedSize = r.u32().toLong() and 0xffffffffL
        r.skip(24) // version, metadata and private data blocks, which fonts don't need
        if (numTables == 0 || totalSfntSize > MAX_FONT_BYTES) throw FontFormatException("That font is too large.")

        val entries = List(numTables) {
            val flags = r.u8()
            val tagIndex = flags and 0x3f
            val tag = if (tagIndex == 63) r.u32() else KNOWN_TAGS[tagIndex]
            val version = flags shr 6
            val origLength = r.base128()
            // glyf/loca are transformed unless version 3; hmtx (and others) only with version 1.
            val transformed = if (tag == GLYF || tag == LOCA) version == 0 else version != 0
            if (transformed && tag != GLYF && tag != LOCA && tag != HMTX) throw FontFormatException("That font uses an unknown WOFF2 feature.")
            val stored = if (transformed) r.base128() else origLength
            if (origLength > MAX_FONT_BYTES || stored > MAX_FONT_BYTES) throw FontFormatException("That font is too large.")
            Entry(tag, origLength, transformed, stored)
        }

        val compressedEnd = r.position + totalCompressedSize
        if (compressedEnd > woff2.size) throw FontFormatException("That font file is incomplete.")
        val expected = entries.sumOf { it.storedLength }
        if (expected > MAX_FONT_BYTES) throw FontFormatException("That font is too large.")
        val stream = decompress(woff2, r.position, totalCompressedSize.toInt(), expected.toInt())
        var offset = 0
        for (entry in entries) {
            entry.data = stream.copyOfRange(offset, offset + entry.storedLength.toInt())
            offset += entry.storedLength.toInt()
        }

        val glyf = entries.firstOrNull { it.tag == GLYF }
        val loca = entries.firstOrNull { it.tag == LOCA }
        var xMins: ShortArray? = null
        if (glyf != null && glyf.transformed) {
            if (loca == null || !loca.transformed) throw FontFormatException("That font file is damaged.")
            val rebuilt = GlyfReconstructor(glyf.data).rebuild()
            glyf.data = rebuilt.glyf
            loca.data = rebuilt.loca
            xMins = rebuilt.xMins
        }
        entries.firstOrNull { it.tag == HMTX && it.transformed }?.let { hmtx ->
            val hhea = entries.firstOrNull { it.tag == HHEA } ?: throw FontFormatException("That font file is damaged.")
            val mins = xMins ?: throw FontFormatException("That font file is damaged.")
            hmtx.data = rebuildHmtx(hmtx.data, Reader(hhea.data).apply { skip(34) }.u16(), mins)
        }
        return writeSfnt(flavor, entries)
    }

    private fun decompress(source: ByteArray, offset: Int, length: Int, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected)
        BrotliInputStream(ByteArrayInputStream(source, offset, length)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > expected) throw FontFormatException("That font file is damaged.")
            }
        }
        if (out.size() != expected) throw FontFormatException("That font file is damaged.")
        return out.toByteArray()
    }

    /** hmtx with left side bearings left out where they equal the glyph's xMin. */
    private fun rebuildHmtx(data: ByteArray, numHMetrics: Int, xMins: ShortArray): ByteArray {
        val r = Reader(data)
        val flags = r.u8()
        val numGlyphs = xMins.size
        if (numHMetrics < 1 || numHMetrics > numGlyphs) throw FontFormatException("That font file is damaged.")
        val advances = IntArray(numHMetrics) { r.u16() }
        val lsbs = IntArray(numHMetrics) { i -> if (flags and 1 != 0) xMins[i].toInt() else r.s16() }
        val extra = IntArray(numGlyphs - numHMetrics) { i -> if (flags and 2 != 0) xMins[numHMetrics + i].toInt() else r.s16() }
        val w = Writer(numHMetrics * 4 + extra.size * 2)
        for (i in 0 until numHMetrics) {
            w.u16(advances[i])
            w.u16(lsbs[i])
        }
        extra.forEach { w.u16(it) }
        return w.bytes()
    }

    private fun writeSfnt(flavor: Int, entries: List<Entry>): ByteArray {
        val tables = entries.sortedBy { it.tag.toLong() and 0xffffffffL }
        val headerSize = 12 + 16 * tables.size
        val total = headerSize + tables.sumOf { (it.data.size + 3) and 3.inv() }
        if (total > MAX_FONT_BYTES) throw FontFormatException("That font is too large.")
        val w = Writer(total)
        var searchRange = 1
        var entrySelector = 0
        while (searchRange * 2 <= tables.size) {
            searchRange *= 2
            entrySelector++
        }
        w.u32(flavor)
        w.u16(tables.size)
        w.u16(searchRange * 16)
        w.u16(entrySelector)
        w.u16(tables.size * 16 - searchRange * 16)
        // head.checkSumAdjustment is recomputed below, so zero it before taking checksums.
        tables.firstOrNull { it.tag == HEAD && it.data.size >= 12 }?.data?.fill(0, 8, 12)
        var offset = headerSize
        for (table in tables) {
            w.u32(table.tag)
            w.u32(checksum(table.data, 0, table.data.size))
            w.u32(offset)
            w.u32(table.data.size)
            offset += (table.data.size + 3) and 3.inv()
        }
        var headOffset = -1
        for (table in tables) {
            if (table.tag == HEAD) headOffset = w.size
            w.bytes(table.data)
            w.pad4()
        }
        val font = w.bytes()
        if (headOffset >= 0 && tables.first { it.tag == HEAD }.data.size >= 12) {
            val adjustment = (0xB1B0AFBAL - (checksum(font, 0, font.size).toLong() and 0xffffffffL)).toInt()
            Writer.putU32(font, headOffset + 8, adjustment)
        }
        return font
    }

    internal fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end) {
            var word = 0
            for (k in 0 until 4) word = (word shl 8) or (if (i + k < end) data[i + k].toInt() and 0xff else 0)
            sum += word
            i += 4
        }
        return sum
    }

    private fun tag(name: String): Int = name.fold(0) { acc, c -> (acc shl 8) or c.code }

    /** Rebuilds TrueType glyf and loca tables from WOFF2's transformed glyf table (section 5.1 of the spec). */
    private class GlyfReconstructor(data: ByteArray) {
        class Result(val glyf: ByteArray, val loca: ByteArray, val xMins: ShortArray)

        private val header = Reader(data)
        private val numGlyphs: Int
        private val indexFormat: Int
        private val nContours: Reader
        private val nPoints: Reader
        private val flags: Reader
        private val glyphs: Reader
        private val composites: Reader
        private val bboxBitmap: ByteArray
        private val bboxes: Reader
        private val instructions: Reader
        private val overlapBitmap: ByteArray?

        init {
            header.skip(2) // reserved
            val optionFlags = header.u16()
            numGlyphs = header.u16()
            indexFormat = header.u16()
            val sizes = IntArray(7) { header.u32().also { if (it < 0) throw FontFormatException("That font file is damaged.") } }
            var at = header.position
            fun slice(size: Int): Reader = Reader(data, at, at + size).also { at += size }
            nContours = slice(sizes[0])
            nPoints = slice(sizes[1])
            flags = slice(sizes[2])
            glyphs = slice(sizes[3])
            composites = slice(sizes[4])
            val bboxStream = slice(sizes[5])
            val bitmapSize = ((numGlyphs + 31) shr 5) shl 2
            bboxBitmap = bboxStream.bytes(bitmapSize)
            bboxes = bboxStream
            instructions = slice(sizes[6])
            overlapBitmap = if (optionFlags and 1 != 0) Reader(data, at, data.size).bytes((numGlyphs + 7) shr 3) else null
        }

        fun rebuild(): Result {
            val glyf = Writer(numGlyphs * 32)
            val offsets = IntArray(numGlyphs + 1)
            val xMins = ShortArray(numGlyphs)
            for (i in 0 until numGlyphs) {
                offsets[i] = glyf.size
                val contours = nContours.s16()
                when {
                    contours == 0 -> if (hasBbox(i)) throw FontFormatException("That font file is damaged.")
                    contours > 0 -> xMins[i] = simple(glyf, i, contours)
                    contours == -1 -> xMins[i] = composite(glyf, i)
                    else -> throw FontFormatException("That font file is damaged.")
                }
                glyf.pad4()
            }
            offsets[numGlyphs] = glyf.size
            val loca = Writer((numGlyphs + 1) * if (indexFormat == 0) 2 else 4)
            for (offset in offsets) if (indexFormat == 0) loca.u16(offset shr 1) else loca.u32(offset)
            if (indexFormat == 0 && glyf.size > 0x1FFFE) throw FontFormatException("That font file is damaged.")
            return Result(glyf.bytes(), loca.bytes(), xMins)
        }

        private fun hasBbox(i: Int) = bboxBitmap[i shr 3].toInt() and (0x80 shr (i and 7)) != 0

        private fun simple(out: Writer, index: Int, contours: Int): Short {
            val endPoints = IntArray(contours)
            var total = 0
            for (c in 0 until contours) {
                total += nPoints.uint255()
                endPoints[c] = total - 1
            }
            if (total > 0xFFFF) throw FontFormatException("That font file is damaged.")
            val xs = IntArray(total)
            val ys = IntArray(total)
            val onCurve = BooleanArray(total)
            var x = 0
            var y = 0
            for (p in 0 until total) {
                val flag = flags.u8()
                onCurve[p] = flag shr 7 == 0
                val f = flag and 0x7f
                val dx: Int
                val dy: Int
                when {
                    f < 10 -> {
                        dx = 0
                        dy = withSign(f, ((f and 14) shl 7) + glyphs.u8())
                    }
                    f < 20 -> {
                        dx = withSign(f, (((f - 10) and 14) shl 7) + glyphs.u8())
                        dy = 0
                    }
                    f < 84 -> {
                        val b0 = f - 20
                        val b1 = glyphs.u8()
                        dx = withSign(f, 1 + (b0 and 0x30) + (b1 shr 4))
                        dy = withSign(f shr 1, 1 + ((b0 and 0x0c) shl 2) + (b1 and 0x0f))
                    }
                    f < 120 -> {
                        val b0 = f - 84
                        dx = withSign(f, 1 + ((b0 / 12) shl 8) + glyphs.u8())
                        dy = withSign(f shr 1, 1 + (((b0 % 12) shr 2) shl 8) + glyphs.u8())
                    }
                    f < 124 -> {
                        val b0 = glyphs.u8()
                        val b1 = glyphs.u8()
                        val b2 = glyphs.u8()
                        dx = withSign(f, (b0 shl 4) + (b1 shr 4))
                        dy = withSign(f shr 1, ((b1 and 0x0f) shl 8) + b2)
                    }
                    else -> {
                        dx = withSign(f, glyphs.u16())
                        dy = withSign(f shr 1, glyphs.u16())
                    }
                }
                x += dx
                y += dy
                xs[p] = x
                ys[p] = y
            }
            val instructionLength = glyphs.uint255()
            val program = instructions.bytes(instructionLength)

            val box = if (hasBbox(index)) IntArray(4) { bboxes.s16() } else intArrayOf(
                xs.minOrNull() ?: 0, ys.minOrNull() ?: 0, xs.maxOrNull() ?: 0, ys.maxOrNull() ?: 0
            )
            out.u16(contours)
            box.forEach { out.u16(it) }
            endPoints.forEach { out.u16(it) }
            out.u16(instructionLength)
            out.bytes(program)

            val overlap = overlapBitmap != null && overlapBitmap[index shr 3].toInt() and (0x80 shr (index and 7)) != 0
            val pointFlags = IntArray(total)
            var lastX = 0
            var lastY = 0
            for (p in 0 until total) {
                var flag = if (onCurve[p]) 0x01 else 0
                if (p == 0 && overlap) flag = flag or 0x40
                val dx = xs[p] - lastX
                val dy = ys[p] - lastY
                flag = flag or when {
                    dx == 0 -> 0x10
                    dx in -255..255 -> 0x02 or (if (dx > 0) 0x10 else 0)
                    else -> 0
                }
                flag = flag or when {
                    dy == 0 -> 0x20
                    dy in -255..255 -> 0x04 or (if (dy > 0) 0x20 else 0)
                    else -> 0
                }
                pointFlags[p] = flag
                lastX = xs[p]
                lastY = ys[p]
            }
            // Flags, using the "repeat" bit for runs, as TrueType allows.
            var p = 0
            while (p < total) {
                var run = 0
                while (p + run + 1 < total && pointFlags[p + run + 1] == pointFlags[p] && run < 255) run++
                if (run > 0) {
                    out.u8(pointFlags[p] or 0x08)
                    out.u8(run)
                } else {
                    out.u8(pointFlags[p])
                }
                p += run + 1
            }
            lastX = 0
            for (i in 0 until total) {
                val dx = xs[i] - lastX
                when {
                    dx == 0 -> Unit
                    dx in -255..255 -> out.u8(kotlin.math.abs(dx))
                    else -> out.u16(dx)
                }
                lastX = xs[i]
            }
            lastY = 0
            for (i in 0 until total) {
                val dy = ys[i] - lastY
                when {
                    dy == 0 -> Unit
                    dy in -255..255 -> out.u8(kotlin.math.abs(dy))
                    else -> out.u16(dy)
                }
                lastY = ys[i]
            }
            return box[0].toShort()
        }

        private fun composite(out: Writer, index: Int): Short {
            if (!hasBbox(index)) throw FontFormatException("That font file is damaged.")
            val box = IntArray(4) { bboxes.s16() }
            out.u16(0xFFFF) // numberOfContours = -1
            box.forEach { out.u16(it) }
            var hasInstructions = false
            do {
                val flag = composites.u16()
                out.u16(flag)
                var argBytes = 2 /* glyph index */ + if (flag and 0x0001 != 0) 4 else 2
                argBytes += when {
                    flag and 0x0008 != 0 -> 2
                    flag and 0x0040 != 0 -> 4
                    flag and 0x0080 != 0 -> 8
                    else -> 0
                }
                out.bytes(composites.bytes(argBytes))
                if (flag and 0x0100 != 0) hasInstructions = true
            } while (flag and 0x0020 != 0)
            if (hasInstructions) {
                val length = glyphs.uint255()
                out.u16(length)
                out.bytes(instructions.bytes(length))
            }
            return box[0].toShort()
        }

        private fun withSign(flag: Int, value: Int) = if (flag and 1 != 0) value else -value
    }

    /** Big-endian reads with bounds checks, over [start, end) of [data]. */
    private class Reader(private val data: ByteArray, start: Int = 0, private val end: Int = data.size) {
        var position = start
            private set

        init {
            if (start < 0 || end > data.size || start > end) throw FontFormatException("That font file is damaged.")
        }

        private fun need(n: Int) {
            if (n < 0 || position + n > end) throw FontFormatException("That font file is damaged.")
        }

        fun skip(n: Int) { need(n); position += n }
        fun u8(): Int { need(1); return data[position++].toInt() and 0xff }
        fun u16(): Int = (u8() shl 8) or u8()
        fun s16(): Int = u16().toShort().toInt()
        fun u32(): Int = (u16() shl 16) or u16()
        fun bytes(n: Int): ByteArray { need(n); return data.copyOfRange(position, position + n).also { position += n } }

        /** UIntBase128: up to 5 bytes, 7 bits each, high bit means "more". */
        fun base128(): Long {
            var value = 0L
            for (i in 0 until 5) {
                val b = u8()
                if (i == 0 && b == 0x80) throw FontFormatException("That font file is damaged.")
                if (value and 0xFE000000L != 0L) throw FontFormatException("That font file is damaged.")
                value = (value shl 7) or (b and 0x7f).toLong()
                if (b and 0x80 == 0) return value
            }
            throw FontFormatException("That font file is damaged.")
        }

        /** 255UInt16, WOFF2's variable-length 16-bit number. */
        fun uint255(): Int = when (val code = u8()) {
            253 -> u16()
            255 -> u8() + 253
            254 -> u8() + 253 * 2
            else -> code
        }
    }

    private class Writer(capacity: Int) {
        private var buffer = ByteArray(maxOf(capacity, 16))
        var size = 0
            private set

        private fun ensure(n: Int) {
            if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n))
        }

        fun u8(v: Int) { ensure(1); buffer[size++] = v.toByte() }
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        fun u32(v: Int) { u16(v shr 16); u16(v) }
        fun bytes(b: ByteArray) { ensure(b.size); b.copyInto(buffer, size); size += b.size }
        fun pad4() { while (size and 3 != 0) u8(0) }
        fun bytes(): ByteArray = buffer.copyOf(size)

        companion object {
            fun putU32(target: ByteArray, at: Int, v: Int) {
                for (k in 0 until 4) target[at + k] = (v shr (24 - 8 * k)).toByte()
            }
        }
    }
}
