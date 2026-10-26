package com.rivers.cosmo.fonts

/** Reads a TrueType/OpenType file's own name for itself, e.g. "Inter Bold", from its name table. */
object SfntNames {
    private const val FULL_NAME = 4
    private const val FAMILY = 1

    /** True for files that start like a TrueType or OpenType (CFF) font. */
    fun isSfnt(bytes: ByteArray): Boolean = bytes.size >= 12 && u32(bytes, 0).let { it == 0x00010000 || it == 0x4F54544F || it == 0x74727565 }

    fun displayName(font: ByteArray): String? = runCatching {
        val numTables = u16(font, 4)
        var name: Int? = null
        for (i in 0 until numTables) {
            val record = 12 + 16 * i
            if (u32(font, record) == 0x6E616D65) name = u32(font, record + 8) // "name"
        }
        val table = name ?: return null
        val count = u16(font, table + 2)
        val strings = table + u16(font, table + 4)
        val found = mutableMapOf<Int, String>()
        for (i in 0 until count) {
            val record = table + 6 + 12 * i
            val platform = u16(font, record)
            val encoding = u16(font, record + 2)
            val nameId = u16(font, record + 6)
            if (nameId != FULL_NAME && nameId != FAMILY) continue
            val length = u16(font, record + 8)
            val start = strings + u16(font, record + 10)
            val bytes = font.copyOfRange(start, start + length)
            val text = when {
                platform == 3 || platform == 0 -> String(bytes, Charsets.UTF_16BE)
                platform == 1 && encoding == 0 -> String(bytes, Charsets.ISO_8859_1)
                else -> continue
            }.trim()
            // Prefer Windows/Unicode names, which are the ones most fonts fill in properly.
            if (text.isNotEmpty() && (nameId !in found || platform != 1)) found[nameId] = text
        }
        (found[FULL_NAME] ?: found[FAMILY])?.take(MAX_NAME)
    }.getOrNull()

    private const val MAX_NAME = 60

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)
    private fun u32(b: ByteArray, at: Int) = (u16(b, at) shl 16) or u16(b, at + 2)
}
