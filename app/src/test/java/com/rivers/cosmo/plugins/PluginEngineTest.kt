package com.rivers.cosmo.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginEngineTest {
    private class MemoryStorage : PluginStorage {
        val values = HashMap<String, String>()
        override fun get(pluginId: String, key: String) = values["$pluginId:$key"]
        override fun set(pluginId: String, key: String, value: String?) {
            if (value == null) values.remove("$pluginId:$key") else values["$pluginId:$key"] = value
        }
        override fun keyCount(pluginId: String) = values.keys.count { it.startsWith("$pluginId:") }
    }

    private val storage = MemoryStorage()
    private val engine = PluginEngine(storage)
    private val data = """{"xlm":"125.5","address":"GABC","network":"testnet","balances":[{"code":"XLM","amount":"125.5"}]}"""

    private fun loadWidget(body: String, pluginId: String = "test") =
        engine.load(pluginId, "cosmo.registerWidget({ id: 'w', title: 'Test', render: function (data) { $body } });")

    @Test
    fun rendersWidgetsFromWalletData() {
        loadWidget("return ui.column(ui.title('Balance'), ui.text(data.xlm + ' XLM', { color: '#FF0000' }), ui.progress(0.5), ui.divider());")
        assertEquals(listOf(WidgetInfo("test/w", "test", "Test")), engine.widgets)
        assertEquals(
            UiNode.Column(
                listOf(
                    UiNode.Text("Balance", "title"),
                    UiNode.Text("125.5 XLM", "body", "#FF0000"),
                    UiNode.Progress(0.5f),
                    UiNode.Divider
                )
            ),
            engine.render("test/w", data)
        )
    }

    @Test
    fun buttonsUpdateStateThatSurvivesReloadsThroughStorage() {
        val script = """
            var count = Number(cosmo.storage.get('count') || 0);
            cosmo.registerWidget({ id: 'counter', title: 'Counter', render: function () {
              return ui.row(ui.text('Taps: ' + count), ui.button('Tap', function () {
                count++; cosmo.storage.set('count', count);
                if (count === 2) cosmo.toast('Two taps!');
              }));
            }});
        """
        engine.load("counter", script)
        val button = (engine.render("counter/counter", data) as UiNode.Row).children[1] as UiNode.Button
        assertEquals(emptyList<PluginEffect>(), engine.click("counter/counter", button.action))
        assertEquals(listOf(PluginEffect.Toast("Two taps!")), engine.click("counter/counter", button.action))
        assertEquals(UiNode.Text("Taps: 2"), (engine.render("counter/counter", data) as UiNode.Row).children[0])

        engine.load("counter", script) // e.g. the next app launch
        assertEquals(UiNode.Text("Taps: 2"), (engine.render("counter/counter", data) as UiNode.Row).children[0])
    }

    @Test
    fun canSuggestButNotSendPayments() {
        loadWidget("return ui.button('Tip', function () { cosmo.suggestPayment({ to: 'GDEST', amount: 5, memo: 'thanks' }); });")
        val button = engine.render("test/w", data) as UiNode.Button
        assertEquals(listOf(PluginEffect.SuggestPayment("GDEST", "5", "thanks")), engine.click("test/w", button.action))
    }

    @Test
    fun cannotReachJava() {
        // The usual Rhino escape hatches are all missing or blocked.
        for (attempt in listOf(
            "return String(typeof Packages + typeof java + typeof javax + typeof JavaImporter + typeof __cosmoHost);",
            "return String(java.lang.System.getProperty('user.home'));"
        )) {
            loadWidget(attempt)
            val result = runCatching { engine.render("test/w", data) }
            result.onSuccess { assertEquals(UiNode.Text("undefinedundefinedundefinedundefinedundefined"), it) }
        }
        loadWidget("return String(''.getClass());")
        assertThrows(PluginException::class.java) { engine.render("test/w", data) }
    }

    @Test
    fun stopsRunawayScripts() {
        loadWidget("while (true) {}")
        val error = assertThrows(PluginException::class.java) { engine.render("test/w", data) }
        assertEquals("The plugin took too long and was stopped.", error.message)

        // Even when the plugin tries to catch the interruption.
        loadWidget("for (;;) { try { while (true) {} } catch (e) {} }")
        assertThrows(PluginException::class.java) { engine.render("test/w", data) }

        loadWidget("function f() { return f() + 1; } return String(f());")
        assertThrows(PluginException::class.java) { engine.render("test/w", data) }

        // A runaway top level is stopped too, before it registers anything.
        assertThrows(PluginException::class.java) { engine.load("loop", "while (true) {}") }
    }

    @Test
    fun keepsPluginsApart() {
        engine.load("a", "var secret = 'from a'; cosmo.registerWidget({ id: 'w', render: function () { return 'a'; } });")
        engine.load("b", "cosmo.registerWidget({ id: 'w', render: function () { return typeof secret; } });")
        assertEquals(UiNode.Text("undefined"), engine.render("b/w", data))
        storage.set("a", "k", "v")
        engine.load("c", "cosmo.registerWidget({ id: 'w', render: function () { return String(cosmo.storage.get('k')); } });")
        assertEquals(UiNode.Text("null"), engine.render("c/w", data))
    }

    @Test
    fun reportsMistakesClearly() {
        assertTrue(assertThrows(PluginException::class.java) { engine.load("x", "var a = ;") }.message!!.contains("line 1"))
        assertThrows(PluginException::class.java) { engine.load("x", "var nothing = 1;") }
        assertThrows(PluginException::class.java) { engine.load("x", "cosmo.registerWidget({ id: 'bad id!', render: function () {} });") }

        loadWidget("return 42;")
        assertEquals(UiNode.Text("42"), engine.render("test/w", data))
        loadWidget("return { type: 'iframe' };")
        assertThrows(PluginException::class.java) { engine.render("test/w", data) }
        loadWidget("var c = []; for (var i = 0; i < 1000; i++) c.push(ui.text(i)); return ui.column.apply(null, c);")
        assertThrows(PluginException::class.java) { engine.render("test/w", data) }

        // Widgets can't be registered later from a callback.
        engine.load("late", "cosmo.registerWidget({ id: 'w', render: function () { return ui.button('x', function () { cosmo.registerWidget({ id: 'y', render: function () {} }); }); } });")
        val button = engine.render("late/w", data) as UiNode.Button
        assertThrows(PluginException::class.java) { engine.click("late/w", button.action) }
    }

    @Test
    fun limitsStorage() {
        loadWidget("cosmo.storage.set('big', new Array(5000).join('x')); return 'ok';")
        assertThrows(PluginException::class.java) { engine.render("test/w", data) }
        loadWidget("for (var i = 0; i < 60; i++) cosmo.storage.set('k' + i, 'v'); return 'ok';")
        assertThrows(PluginException::class.java) { engine.render("test/w", data) }
    }

    @Test
    fun supportsModernSyntax() {
        loadWidget("const add = (a, b) => a + b; let n = add(1, 2); return ui.text(`Sum: \${n}`);")
        assertEquals(UiNode.Text("Sum: 3"), engine.render("test/w", data))
    }
}
