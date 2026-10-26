package com.rivers.cosmo.plugins

import android.content.Context
import com.rivers.cosmo.util.backgroundScope
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the user's installed plugins in the background and keeps their widgets rendered against
 * the latest wallet data. All script execution happens on one dedicated thread, because the JS
 * engine is single-threaded and scripts must never block the UI.
 */
class PluginHost(context: Context) {
    data class PluginState(val plugin: InstalledPlugin, val error: String? = null)

    data class WidgetState(
        val key: String,
        val pluginName: String,
        val title: String,
        val node: UiNode? = null,
        val error: String? = null
    ) {
        /** The dashboard module ID this widget is laid out under. */
        val moduleId: String get() = "$MODULE_PREFIX$key"
    }

    data class State(val plugins: List<PluginState> = emptyList(), val widgets: List<WidgetState> = emptyList())

    private val repository = PluginRepository(context)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "cosmo-plugins") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = backgroundScope("plugins", dispatcher)
    private val engine = PluginEngine(repository)
    private var dataJson = "{}"
    private val loadErrors = HashMap<String, String>()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<PluginEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<PluginEffect> = _effects.asSharedFlow()

    fun start() {
        scope.launch { reloadAll() }
    }

    /** Re-renders every widget when the wallet data they're given changes. */
    fun updateData(json: String) {
        scope.launch {
            if (json == dataJson) return@launch
            dataJson = json
            renderAll()
        }
    }

    fun click(widgetKey: String, action: Int) {
        scope.launch {
            try {
                engine.click(widgetKey, action).forEach { _effects.tryEmit(it) }
            } catch (e: PluginException) {
                _effects.tryEmit(PluginEffect.Toast(e.message ?: "The plugin failed."))
            }
            renderAll()
        }
    }

    /** Checks that [source] runs and registers widgets, then saves it. Returns the error if it doesn't. */
    suspend fun install(name: String, source: String): Result<Unit> = withContext(dispatcher) {
        val id = repository.newId(name)
        try {
            engine.load(id, source)
            repository.add(id, name, source)
            reloadAll()
            Result.success(Unit)
        } catch (e: PluginException) {
            engine.unload(id)
            Result.failure(e)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        scope.launch {
            repository.setEnabled(id, enabled)
            reloadAll()
        }
    }

    fun remove(id: String) {
        scope.launch {
            repository.remove(id)
            reloadAll()
        }
    }

    fun close() {
        scope.cancel()
        executor.shutdown()
    }

    private fun reloadAll() {
        loadErrors.clear()
        val installed = repository.list()
        installed.forEach { plugin ->
            engine.unload(plugin.id)
            if (!plugin.enabled) return@forEach
            val source = repository.source(plugin.id)
            if (source == null) {
                loadErrors[plugin.id] = "The script file is missing."
                return@forEach
            }
            try {
                engine.load(plugin.id, source)
            } catch (e: PluginException) {
                loadErrors[plugin.id] = e.message ?: "The script failed to load."
            }
        }
        renderAll()
    }

    private fun renderAll() {
        val installed = repository.list()
        val names = installed.associate { it.id to it.name }
        val widgets = engine.widgets.map { info ->
            val base = WidgetState(info.key, names[info.pluginId] ?: info.pluginId, info.title)
            try {
                base.copy(node = engine.render(info.key, dataJson))
            } catch (e: PluginException) {
                base.copy(error = e.message)
            }
        }
        engine.takeEffects().forEach { _effects.tryEmit(it) }
        _state.value = State(installed.map { PluginState(it, loadErrors[it.id]) }, widgets)
    }

    companion object {
        const val MODULE_PREFIX = "plugin:"
    }
}
