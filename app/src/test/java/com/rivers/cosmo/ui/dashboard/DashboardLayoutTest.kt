package com.rivers.cosmo.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardLayoutTest {
    @Test
    fun keepsModulesOnScreenAndOnTheGrid() {
        // 400dp wide screen: the 8dp grid is 0.02 of the width.
        val p = ModulePlacement(x = 0.9f, y = 13f, width = 0.5f, scale = 5f).normalized(400f)
        assertEquals(0.5f, p.x, 0.0001f) // pushed back so the module's right edge stays on screen
        assertEquals(16f, p.y) // 13dp snaps to 16dp
        assertEquals(ModulePlacement.MAX_SCALE, p.scale)

        val tiny = ModulePlacement(x = -1f, y = -50f, width = 0.05f).normalized(400f)
        assertEquals(ModulePlacement(0f, 0f, ModulePlacement.MIN_WIDTH, 1f), tiny)
        assertEquals(0.04f, ModulePlacement(x = 0.033f, y = 0f, width = 0.5f).normalized(400f).x, 0.0001f)
    }

    @Test
    fun movedModulesDrawOnTop() {
        val layout = DashboardLayout()
            .place("a", ModulePlacement(0f, 0f, 1f))
            .place("b", ModulePlacement(0f, 0f, 1f))
            .place("a", ModulePlacement(0f, 8f, 1f))
        assertEquals(listOf("c", "b", "a"), layout.drawingOrder(listOf("a", "b", "c")))
    }

    @Test
    fun hidingForgetsThePlacement() {
        val layout = DashboardLayout().place("a", ModulePlacement(0f, 0f, 1f)).hide("a")
        assertEquals(setOf("a"), layout.hidden)
        assertEquals(emptyMap<String, ModulePlacement>(), layout.placements)
        assertEquals(emptySet<String>(), layout.show("a").hidden)
    }

    @Test
    fun survivesSavingAndLoading() {
        val layout = DashboardLayout()
            .place("balance", ModulePlacement(0.1f, 24f, 0.6f, 1.4f))
            .place("plugin:goal-1a2b3c/goal", ModulePlacement(0f, 200f, 1f))
            .hide("logout")
            .setFont("balance", "PlayfairDisplay")
        assertEquals(layout, DashboardLayout.fromJson(layout.toJson()))
        assertEquals(DashboardLayout(), DashboardLayout.fromJson("not json"))
        assertEquals(DashboardLayout(), DashboardLayout.fromJson(null))
    }

    @Test
    fun movingOneModuleLeavesTheOthersWhereTheyWere() {
        // "a" was placed by the user; "b" and "c" are auto-stacked where the dashboard drew them.
        val layout = DashboardLayout().place("a", ModulePlacement(0f, 0f, 1f))
        val drawn = mapOf(
            "a" to ModulePlacement(0.5f, 999f, 1f), // ignored: the saved placement wins
            "b" to ModulePlacement(0f, 120f, 1f),
            "c" to ModulePlacement(0f, 240f, 1f)
        )
        val moved = layout.pinAll(drawn).place("b", ModulePlacement(0f, 600f, 1f))
        assertEquals(ModulePlacement(0f, 0f, 1f), moved.placements["a"])
        assertEquals(ModulePlacement(0f, 600f, 1f), moved.placements["b"])
        assertEquals(ModulePlacement(0f, 240f, 1f), moved.placements["c"]) // didn't jump below "b"
    }

    @Test
    fun modulesCanHaveTheirOwnFont() {
        val layout = DashboardLayout().setFont("balance", "Doto").setFont("activity", "Lora")
        assertEquals(mapOf("balance" to "Doto", "activity" to "Lora"), layout.fonts)
        assertEquals(mapOf("activity" to "Lora"), layout.setFont("balance", null).fonts)
        // Layouts saved before per-module fonts existed still load.
        assertEquals(emptyMap<String, String>(), DashboardLayout.fromJson("""{"placements":{},"hidden":[],"order":[]}""").fonts)
    }
}
