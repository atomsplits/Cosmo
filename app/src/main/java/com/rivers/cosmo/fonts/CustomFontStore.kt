package com.rivers.cosmo.fonts

import android.content.Context
import android.graphics.Typeface
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A font the user added. It's referred to elsewhere (settings, widget fonts) by [key]. */
data class CustomFont(val id: String, val name: String, val file: File) {
    val key: String get() = FontKeys.custom(id)
}

/** Font choices are stored as text: an [com.rivers.cosmo.settings.AppFont] name, or "custom:<id>". */
object FontKeys {
    private const val PREFIX = "custom:"
    fun custom(id: String) = PREFIX + id
    fun customId(key: String): String? = key.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
}

/**
 * Fonts the user added in Settings, from .woff2 files (or .ttf/.otf). WOFF2 is converted to TrueType
 * once, on import, because that's what Android can draw with.
 */
class CustomFontStore(context: Context) {
    private val directory = File(context.filesDir, "fonts").apply { mkdirs() }
    private val index = File(directory, "index.json")
    private val _fonts = MutableStateFlow(load())
    val fonts: StateFlow<List<CustomFont>> = _fonts.asStateFlow()

    /** Imports a font file and returns it; throws [FontFormatException] with a message for the user. */
    suspend fun import(bytes: ByteArray, fileName: String?): CustomFont = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_FILE_BYTES) throw FontFormatException("Fonts can be at most 10 MB.")
        if (_fonts.value.size >= MAX_FONTS) throw FontFormatException("You can add up to $MAX_FONTS fonts. Remove one first.")
        val sfnt = when {
            Woff2.isWoff2(bytes) -> Woff2.decode(bytes)
            SfntNames.isSfnt(bytes) -> bytes
            else -> throw FontFormatException("That isn't a font file. Pick a .woff2 (or .ttf/.otf) font.")
        }
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$id.ttf")
        file.writeBytes(sfnt)
        // Android falls back to its default font for files it can't use; don't keep those.
        val usable = runCatching { Typeface.createFromFile(file) }.getOrNull()?.let { it != Typeface.DEFAULT } == true
        if (!usable) {
            file.delete()
            throw FontFormatException("Android couldn't use that font.")
        }
        val name = SfntNames.displayName(sfnt) ?: fileName?.substringBeforeLast('.')?.take(60) ?: "Custom font"
        val font = CustomFont(id, name, file)
        synchronized(this@CustomFontStore) {
            _fonts.value = _fonts.value + font
            save()
        }
        font
    }

    fun remove(id: String) = synchronized(this) {
        _fonts.value.firstOrNull { it.id == id }?.file?.delete()
        _fonts.value = _fonts.value.filterNot { it.id == id }
        save()
    }

    private fun save() {
        val json = JSONArray()
        _fonts.value.forEach { json.put(JSONObject().put("id", it.id).put("name", it.name)) }
        index.writeText(json.toString())
    }

    private fun load(): List<CustomFont> = runCatching {
        val json = JSONArray(index.readText())
        List(json.length()) { i ->
            val item = json.getJSONObject(i)
            val id = item.getString("id")
            CustomFont(id, item.getString("name"), File(directory, "$id.ttf"))
        }.filter { it.file.exists() }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
        const val MAX_FONTS = 20
    }
}
