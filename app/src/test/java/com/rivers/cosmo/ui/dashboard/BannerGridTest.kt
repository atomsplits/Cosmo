package com.rivers.cosmo.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class BannerGridTest {
    // A 360×140dp banner and a 56dp logo.
    private fun place(x: Float, y: Float, snap: Boolean = true) = BannerGrid.place(x, y, 56f, 56f, 360f, 140f, snap)

    @Test
    fun snapsToTheNearestGridPoint() {
        assertEquals(BannerPosition(32f, 48f), place(37f, 41f))
        assertEquals(BannerPosition(48f, 48f), place(41f, 55.9f))
        assertEquals(BannerPosition(0f, 0f), place(7f, 7.9f))
    }

    @Test
    fun keepsItemsInsideTheBannerOnTheGrid() {
        // The banner fits 56dp items up to x = 304 and y = 84; the last grid points before those are 304 and 80.
        assertEquals(BannerPosition(304f, 80f), place(500f, 500f))
        assertEquals(BannerPosition(0f, 0f), place(-40f, -40f))
    }

    @Test
    fun movesFreelyWithSnappingOff() {
        assertEquals(BannerPosition(37f, 41f), place(37f, 41f, snap = false))
        assertEquals(BannerPosition(304f, 84f), place(500f, 500f, snap = false))
    }

    @Test
    fun savesPositionsAndTheSnapSetting() {
        val layout = DashboardLayout().moveBannerItem("logo", BannerPosition(16f, 32f)).copy(snapToGrid = false)
        val restored = DashboardLayout.fromJson(layout.toJson())
        assertEquals(BannerPosition(16f, 32f), restored.banner["logo"])
        assertEquals(false, restored.snapToGrid)
        // Layouts saved before this existed default to snapping on, with default positions.
        val old = DashboardLayout.fromJson("""{"placements":{},"hidden":[],"order":[]}""")
        assertEquals(true, old.snapToGrid)
        assertEquals(emptyMap<String, BannerPosition>(), old.banner)
    }
}
