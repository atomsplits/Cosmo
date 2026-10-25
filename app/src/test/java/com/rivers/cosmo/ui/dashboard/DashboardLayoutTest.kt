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
        assertEquals(layout, DashboardLayout.fromJson(layout.toJson()))
        assertEquals(DashboardLayout(), DashboardLayout.fromJson("not json"))
        assertEquals(DashboardLayout(), DashboardLayout.fromJson(null))
    }
}
