package com.rivers.cosmo.plugins

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrappedException

/** A widget's UI, built by a plugin from the `ui.*` helpers and drawn natively by the app. */
sealed interface UiNode {
    data class Text(val text: String, val style: String = "body", val color: String? = null) : UiNode
    data class Column(val children: List<UiNode>) : UiNode
    data class Row(val children: List<UiNode>) : UiNode
    data class Button(val label: String, val action: Int) : UiNode
    data class Progress(val value: Float) : UiNode
    data class Spacer(val height: Int) : UiNode
    data object Divider : UiNode
}

/** Things a plugin asks the app to do; the app decides how (and whether) to do them. */
sealed interface PluginEffect {
    data class Toast(val message: String) : PluginEffect
    /** Opens the payment form pre-filled. The user still reviews and confirms; plugins can't send money. */
    data class SuggestPayment(val to: String, val amount: String?, val memo: String?) : PluginEffect
}

/** A small key-value store for each plugin, so widgets can remember things between app launches. */
interface PluginStorage {
    fun get(pluginId: String, key: String): String?
    fun set(pluginId: String, key: String, value: String?)
    fun keyCount(pluginId: String): Int
}

// A RuntimeException because Rhino can only pass unchecked exceptions back out through script code.
class PluginException(message: String) : RuntimeException(message)

data class WidgetInfo(val key: String, val pluginId: String, val title: String)

/**
 * Runs user-written JavaScript plugins with Mozilla Rhino, sandboxed:
 *  - no access to Java/Android classes, files or the network (only the standard JS library);
 *  - each plugin gets its own global scope, so plugins can't see each other;
 *  - every call is cut off after a fixed amount of work, so a buggy loop can't freeze the app.
 *
 * Plugins only see the wallet data the app chooses to pass in ([render]'s JSON) and can only
 * affect the app through [PluginEffect]s. Not thread-safe: use it from one thread.
 */
class PluginEngine(private val storage: PluginStorage) {

    private class Plugin(val id: String, val scope: Scriptable) {
        val widgets = LinkedHashMap<String, Scriptable>()
    }

    private val plugins = HashMap<String, Plugin>()
    /** Button callbacks from each widget's latest render, by widget key. */
    private val actions = HashMap<String, List<Function>>()
    private var pendingEffects = mutableListOf<PluginEffect>()
    private var loadingPlugin: Plugin? = null

    val widgets: List<WidgetInfo>
        get() = plugins.values.flatMap { plugin ->
            plugin.widgets.map { (id, widget) ->
                WidgetInfo(widgetKey(plugin.id, id), plugin.id, (ScriptableObject.getProperty(widget, "title") as? String) ?: id)
            }
        }

    /** Runs a plugin's script, which registers its widgets. Replaces any earlier version of it. */
    fun load(pluginId: String, source: String) {
        if (source.length > MAX_SCRIPT_CHARS) throw PluginException("The script is too large (max ${MAX_SCRIPT_CHARS / 1000} KB).")
        unload(pluginId)
        withContext { cx ->
            // initSafeStandardObjects leaves out the Packages/java globals that reach into the JVM.
            val scope = cx.initSafeStandardObjects(null, true)
            val plugin = Plugin(pluginId, scope)
            ScriptableObject.putProperty(scope, HOST_NAME, Host(plugin))
            cx.evaluateString(scope, PRELUDE, "cosmo-prelude.js", 1, null)
            ScriptableObject.deleteProperty(scope, HOST_NAME)
            loadingPlugin = plugin
            try {
                cx.evaluateString(scope, source, pluginId, 1, null)
            } finally {
                loadingPlugin = null
            }
            if (plugin.widgets.isEmpty()) throw PluginException("The script didn't register any widgets (call cosmo.registerWidget).")
            plugins[pluginId] = plugin
        }
    }

    fun unload(pluginId: String) {
        plugins.remove(pluginId)
        actions.keys.removeAll { it.startsWith("$pluginId/") }
    }

    /** Calls the widget's `render(data)` with [dataJson] and returns the UI it built. */
    fun render(widgetKey: String, dataJson: String): UiNode = withContext { cx ->
        val (plugin, widget) = find(widgetKey)
        val render = ScriptableObject.getProperty(widget, "render") as? Function
            ?: throw PluginException("This widget has no render function.")
        val data = NativeJSON.parse(cx, plugin.scope, dataJson) { _, _, _, args -> args[1] }
        val result = render.call(cx, plugin.scope, widget, arrayOf(data))
        val callbacks = mutableListOf<Function>()
        val node = toNode(result, callbacks, depth = 0, budget = intArrayOf(MAX_NODES))
        actions[widgetKey] = callbacks
        node
    }

    /** Runs the callback of a button from the widget's latest render. The caller should render again after. */
    fun click(widgetKey: String, action: Int): List<PluginEffect> = withContext { cx ->
        val (plugin, widget) = find(widgetKey)
        val callback = actions[widgetKey]?.getOrNull(action) ?: throw PluginException("That button no longer exists.")
        callback.call(cx, plugin.scope, widget, emptyArray())
        Unit
    }.let { takeEffects() }

    /** Effects requested since the last call (e.g. a toast from inside render). */
    fun takeEffects(): List<PluginEffect> = pendingEffects.also { pendingEffects = mutableListOf() }

    private fun find(widgetKey: String): Pair<Plugin, Scriptable> {
        val pluginId = widgetKey.substringBefore('/')
        val plugin = plugins[pluginId] ?: throw PluginException("Plugin $pluginId isn't loaded.")
        val widget = plugin.widgets[widgetKey.substringAfter('/')] ?: throw PluginException("Widget $widgetKey doesn't exist.")
        return plugin to widget
    }

    private fun toNode(value: Any?, callbacks: MutableList<Function>, depth: Int, budget: IntArray): UiNode {
        if (depth > MAX_DEPTH) throw PluginException("The widget's UI is nested too deeply.")
        if (--budget[0] < 0) throw PluginException("The widget's UI has too many elements (max $MAX_NODES).")
        // Bare strings and numbers become text, formatted the way JS would print them (42, not 42.0).
        if (value is CharSequence || value is Number) return UiNode.Text(Context.toString(value).take(MAX_TEXT))
        val obj = value as? Scriptable ?: throw PluginException("render() must return a ui.* element, got ${describe(value)}.")
        fun str(name: String): String? = (ScriptableObject.getProperty(obj, name) as? CharSequence)?.toString()
        fun num(name: String): Double? = (ScriptableObject.getProperty(obj, name) as? Number)?.toDouble()
        fun children(): List<UiNode> {
            val list = ScriptableObject.getProperty(obj, "children") as? NativeArray ?: return emptyList()
            return list.indices.map { toNode(list[it], callbacks, depth + 1, budget) }
        }
        return when (str("type")) {
            "text" -> UiNode.Text(str("text").orEmpty().take(MAX_TEXT), str("style") ?: "body", str("color"))
            "column" -> UiNode.Column(children())
            "row" -> UiNode.Row(children())
            "button" -> {
                val onClick = ScriptableObject.getProperty(obj, "onClick") as? Function
                    ?: throw PluginException("ui.button needs a function to call when tapped.")
                callbacks += onClick
                UiNode.Button(str("label").orEmpty().take(MAX_LABEL), callbacks.lastIndex)
            }
            "progress" -> UiNode.Progress((num("value") ?: 0.0).toFloat().coerceIn(0f, 1f))
            "spacer" -> UiNode.Spacer((num("height") ?: 8.0).toInt().coerceIn(0, 200))
            "divider" -> UiNode.Divider
            else -> throw PluginException("Unknown UI element type \"${str("type")}\".")
        }
    }

    private fun describe(value: Any?) = when (value) {
        null, Undefined.instance -> "nothing"
        else -> value::class.java.simpleName
    }

    /** Runs [block] in a Rhino context with the sandbox and time limit, turning script errors into [PluginException]. */
    private fun <T> withContext(block: (Context) -> T): T {
        val cx = contextFactory.enterContext()
        try {
            cx.optimizationLevel = -1 // Interpreted mode: required on Android, which can't load generated bytecode.
            cx.languageVersion = Context.VERSION_ES6
            cx.maximumInterpreterStackDepth = MAX_STACK_DEPTH
            cx.putThreadLocal(INSTRUCTIONS_KEY, LongArray(1))
            return block(cx)
        } catch (e: PluginException) {
            throw e
        } catch (e: TooMuchWork) {
            throw PluginException("The plugin took too long and was stopped.")
        } catch (e: WrappedException) {
            // A limit enforced by Host (thrown as PluginException) surfaces wrapped by Rhino.
            throw e.wrappedException as? PluginException ?: PluginException(e.details())
        } catch (e: RhinoException) {
            throw PluginException(e.details() + (e.lineNumber().takeIf { it > 0 }?.let { " (line $it)" } ?: ""))
        } catch (e: StackOverflowError) {
            throw PluginException("The plugin recursed too deeply and was stopped.")
        } finally {
            Context.exit()
        }
    }

    /** The bridge from the JS prelude to the app. Only the prelude sees it; it's removed before plugin code runs. */
    private inner class Host(private val plugin: Plugin) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            val name = args.getOrNull(0) as? String
            fun arg(i: Int): String? = args.getOrNull(i)?.takeUnless { it == Undefined.instance }?.let { Context.toString(it) }
            return when (name) {
                "registerWidget" -> {
                    if (loadingPlugin !== plugin) throw PluginException("Widgets can only be registered when the script loads.")
                    val widget = args.getOrNull(1) as? Scriptable ?: throw PluginException("registerWidget needs a widget object.")
                    val id = (ScriptableObject.getProperty(widget, "id") as? CharSequence)?.toString()
                    if (id == null || !WIDGET_ID.matches(id)) throw PluginException("Each widget needs an id of letters, digits, - or _.")
                    if (plugin.widgets.size >= MAX_WIDGETS_PER_PLUGIN) throw PluginException("A plugin can have at most $MAX_WIDGETS_PER_PLUGIN widgets.")
                    plugin.widgets[id] = widget
                    Undefined.instance
                }
                "storageGet" -> storage.get(plugin.id, arg(1).orEmpty())
                "storageSet" -> {
                    val key = arg(1).orEmpty()
                    val value = arg(2)
                    if (key.isEmpty() || key.length > 100) throw PluginException("Storage keys must be 1-100 characters.")
                    if (value != null && value.length > MAX_STORED_CHARS) throw PluginException("Stored values can be at most $MAX_STORED_CHARS characters.")
                    if (value != null && storage.get(plugin.id, key) == null && storage.keyCount(plugin.id) >= MAX_STORED_KEYS) {
                        throw PluginException("A plugin can store at most $MAX_STORED_KEYS values.")
                    }
                    storage.set(plugin.id, key, value)
                    Undefined.instance
                }
                "toast" -> {
                    pendingEffects += PluginEffect.Toast(arg(1).orEmpty().take(MAX_TEXT))
                    Undefined.instance
                }
                "suggestPayment" -> {
                    val to = arg(1) ?: throw PluginException("suggestPayment needs a \"to\" address.")
                    pendingEffects += PluginEffect.SuggestPayment(to.take(100), arg(2)?.take(40), arg(3)?.take(40))
                    Undefined.instance
                }
                else -> throw PluginException("Unknown host call.")
            }
        }
    }

    private class TooMuchWork : Error()

    companion object {
        const val MAX_SCRIPT_CHARS = 100_000
        private const val HOST_NAME = "__cosmoHost"
        private const val INSTRUCTIONS_KEY = "cosmo.instructions"
        // Rhino counts in units of this threshold; the limit allows a few million simple operations per call.
        private const val INSTRUCTION_CHECK_EVERY = 10_000
        private const val MAX_INSTRUCTIONS = 5_000_000L
        private const val MAX_STACK_DEPTH = 500
        private const val MAX_DEPTH = 20
        private const val MAX_NODES = 300
        private const val MAX_TEXT = 1_000
        private const val MAX_LABEL = 60
        private const val MAX_WIDGETS_PER_PLUGIN = 10
        private const val MAX_STORED_KEYS = 50
        private const val MAX_STORED_CHARS = 4_000
        private val WIDGET_ID = Regex("[A-Za-z0-9_-]{1,40}")

        fun widgetKey(pluginId: String, widgetId: String) = "$pluginId/$widgetId"

        private val contextFactory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                instructionObserverThreshold = INSTRUCTION_CHECK_EVERY
                // Blocks every Java class, so scripts can't reach the JVM even through tricks like getClass().
                setClassShutter(ClassShutter { false })
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                val used = cx.getThreadLocal(INSTRUCTIONS_KEY) as? LongArray ?: return
                used[0] += instructionCount
                if (used[0] > MAX_INSTRUCTIONS) throw TooMuchWork()
            }
        }

        /** The plugin API, written in JS on top of the host bridge. See docs/plugins.md. */
        private val PRELUDE = """
            (function (host) {
              function el(type, props) { props = props || {}; props.type = type; return props; }
              function kids(args) { return Array.prototype.slice.call(args); }
              var ui = {
                text: function (text, opts) { opts = opts || {}; return el('text', { text: String(text), style: opts.style || 'body', color: opts.color }); },
                title: function (text) { return ui.text(text, { style: 'title' }); },
                column: function () { return el('column', { children: kids(arguments) }); },
                row: function () { return el('row', { children: kids(arguments) }); },
                button: function (label, onClick) { return el('button', { label: String(label), onClick: onClick }); },
                progress: function (value) { return el('progress', { value: Number(value) }); },
                spacer: function (height) { return el('spacer', { height: height || 8 }); },
                divider: function () { return el('divider'); }
              };
              var cosmo = {
                registerWidget: function (widget) { host('registerWidget', widget); },
                storage: {
                  get: function (key) { return host('storageGet', key); },
                  set: function (key, value) { host('storageSet', key, value === undefined || value === null ? null : String(value)); },
                  remove: function (key) { host('storageSet', key, null); }
                },
                toast: function (message) { host('toast', message); },
                suggestPayment: function (p) { p = p || {}; host('suggestPayment', p.to, p.amount === undefined ? undefined : String(p.amount), p.memo); }
              };
              Object.freeze(ui); Object.freeze(cosmo.storage); Object.freeze(cosmo);
              this.ui = ui;
              this.cosmo = cosmo;
            }).call(this, $HOST_NAME);
        """.trimIndent()
    }
}
