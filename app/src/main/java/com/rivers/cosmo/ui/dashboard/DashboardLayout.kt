package com.rivers.cosmo.ui.dashboard

import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where one module sits on the dashboard. [x] and [width] are fractions of the screen width, so a
 * layout still fits after rotating or on another phone; [y] is in dp from the top.
 * [scale] enlarges or shrinks the module's contents.
 */
data class ModulePlacement(val x: Float, val y: Float, val width: Float, val scale: Float = 1f) {
    /** Keeps the module on screen, snapped to an 8dp grid so modules line up easily. */
    fun normalized(canvasWidthDp: Float): ModulePlacement {
        val w = width.coerceIn(MIN_WIDTH, 1f)
        val gridX = if (canvasWidthDp > 0) GRID_DP / canvasWidthDp else 0f
        fun snapX(value: Float) = if (gridX > 0) (value / gridX).roundToInt() * gridX else value
        return ModulePlacement(
            x = snapX(x).coerceIn(0f, 1f - w),
            y = ((y / GRID_DP).roundToInt() * GRID_DP).coerceAtLeast(0f),
            width = w,
            scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        )
    }

    companion object {
        const val GRID_DP = 8f
        const val MIN_WIDTH = 0.3f
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 1.8f
        const val SCALE_STEP = 0.1f
    }
}

/**
 * The user's arrangement of the dashboard. Modules without a placement are stacked automatically
 * below the placed ones; [order] is the drawing order (last is on top).
 */
data class DashboardLayout(
    val placements: Map<String, ModulePlacement> = emptyMap(),
    val hidden: Set<String> = emptySet(),
    val order: List<String> = emptyList()
) {
    fun place(id: String, placement: ModulePlacement): DashboardLayout =
        copy(placements = placements + (id to placement), order = order - id + id)

    fun hide(id: String) = copy(hidden = hidden + id, placements = placements - id, order = order - id)

    fun show(id: String) = copy(hidden = hidden - id)

    /** [ids] sorted so recently moved modules draw on top of the others. */
    fun drawingOrder(ids: List<String>): List<String> =
        ids.sortedBy { id -> order.indexOf(id).let { if (it < 0) -1 else it } }

    fun toJson(): String = JSONObject()
        .put("placements", JSONObject().apply {
            placements.forEach { (id, p) ->
                put(id, JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()).put("width", p.width.toDouble()).put("scale", p.scale.toDouble()))
            }
        })
        .put("hidden", JSONArray(hidden.toList()))
        .put("order", JSONArray(order))
        .toString()

    companion object {
        /** Reads a saved layout, falling back to the default arrangement if it's missing or corrupt. */
        fun fromJson(json: String?): DashboardLayout {
            json ?: return DashboardLayout()
            return runCatching {
                val root = JSONObject(json)
                val placementsJson = root.optJSONObject("placements") ?: JSONObject()
                val placements = placementsJson.keys().asSequence().associateWith { id ->
                    val p = placementsJson.getJSONObject(id)
                    ModulePlacement(
                        p.getDouble("x").toFloat(),
                        p.getDouble("y").toFloat(),
                        p.getDouble("width").toFloat(),
                        p.optDouble("scale", 1.0).toFloat()
                    )
                }
                fun strings(name: String) = root.optJSONArray(name)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
                DashboardLayout(placements, strings("hidden").toSet(), strings("order"))
            }.getOrDefault(DashboardLayout())
        }
    }
}
