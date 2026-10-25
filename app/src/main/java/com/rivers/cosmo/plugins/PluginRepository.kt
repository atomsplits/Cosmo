package com.rivers.cosmo.plugins

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class InstalledPlugin(val id: String, val name: String, val enabled: Boolean)

/** Saves imported plugin scripts, which ones are enabled, and each plugin's own key-value storage. */
class PluginRepository(context: Context) : PluginStorage {
    private val directory = File(context.filesDir, "plugins").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("cosmo_plugins", Context.MODE_PRIVATE)
    private val storagePrefs = context.getSharedPreferences("cosmo_plugin_storage", Context.MODE_PRIVATE)

    fun list(): List<InstalledPlugin> {
        val array = runCatching { JSONArray(prefs.getString(KEY_PLUGINS, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            InstalledPlugin(item.getString("id"), item.getString("name"), item.optBoolean("enabled", true))
        }
    }

    fun source(id: String): String? = File(directory, "$id.js").takeIf { it.exists() }?.readText()

    /** A fresh ID for a new plugin; IDs are also file names, so they're kept to safe characters. */
    fun newId(name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "plugin" }
        return "$slug-${UUID.randomUUID().toString().take(6)}"
    }

    fun add(id: String, name: String, source: String) {
        File(directory, "$id.js").writeText(source)
        save(list() + InstalledPlugin(id, name.take(MAX_NAME), enabled = true))
    }

    fun setEnabled(id: String, enabled: Boolean) {
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(id: String) {
        File(directory, "$id.js").delete()
        save(list().filterNot { it.id == id })
        storagePrefs.edit().apply { storagePrefs.all.keys.filter { it.startsWith("$id:") }.forEach(::remove) }.apply()
    }

    override fun get(pluginId: String, key: String): String? = storagePrefs.getString("$pluginId:$key", null)

    override fun set(pluginId: String, key: String, value: String?) {
        storagePrefs.edit().apply { if (value == null) remove("$pluginId:$key") else putString("$pluginId:$key", value) }.apply()
    }

    override fun keyCount(pluginId: String): Int = storagePrefs.all.keys.count { it.startsWith("$pluginId:") }

    private fun save(plugins: List<InstalledPlugin>) {
        val array = JSONArray()
        plugins.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("enabled", it.enabled)) }
        prefs.edit().putString(KEY_PLUGINS, array.toString()).apply()
    }

    private companion object {
        const val KEY_PLUGINS = "plugins"
        const val MAX_NAME = 40
    }
}
