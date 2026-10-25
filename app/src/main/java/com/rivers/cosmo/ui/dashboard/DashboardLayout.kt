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

/** Where an item in the banner (the logo, the title) sits: its top-left corner, in dp. */
data class BannerPosition(val x: Float, val y: Float)

/** The grid that banner items snap to in edit mode. */
object BannerGrid {
    const val STEP_DP = 16f

    /**
     * Where an item of [itemWidth]×[itemHeight] dragged to ([x], [y]) ends up in a banner of
     * [bannerWidth]×[bannerHeight] (all dp): on the nearest grid point when [snap] is on, and
     * always fully inside the banner.
     */
    fun place(x: Float, y: Float, itemWidth: Float, itemHeight: Float, bannerWidth: Float, bannerHeight: Float, snap: Boolean): BannerPosition {
        fun fit(value: Float, item: Float, space: Float): Float {
            val max = (space - item).coerceAtLeast(0f)
            if (!snap) return value.coerceIn(0f, max)
            // Snap, then step back inside if the nearest grid point would push the item over the edge.
            val lastGridPoint = (max / STEP_DP).toInt() * STEP_DP
            return ((value / STEP_DP).roundToInt() * STEP_DP).coerceIn(0f, lastGridPoint)
        }
        return BannerPosition(fit(x, itemWidth, bannerWidth), fit(y, itemHeight, bannerHeight))
    }
}

/**
 * The user's arrangement of the dashboard. Modules without a placement are stacked automatically
 * below the placed ones; [order] is the drawing order (last is on top).
 */
data class DashboardLayout(
    val placements: Map<String, ModulePlacement> = emptyMap(),
    val hidden: Set<String> = emptySet(),
    val order: List<String> = emptyList(),
    /** A font for individual modules, by font key (an AppFont name or "custom:<id>"); others use the app's font. */
    val fonts: Map<String, String> = emptyMap(),
    /** Positions of items the user moved in the banner, by id ("logo", "title"); others use their defaults. */
    val banner: Map<String, BannerPosition> = emptyMap(),
    /** Whether banner items snap to [BannerGrid] while being moved. */
    val snapToGrid: Boolean = true
) {
    fun moveBannerItem(id: String, position: BannerPosition) = copy(banner = banner + (id to position))

    fun place(id: String, placement: ModulePlacement): DashboardLayout =
        copy(placements = placements + (id to placement), order = order - id + id)

    /**
     * Saves where every module in [drawnAt] is currently drawn, keeping existing placements. Called
     * before moving one module: otherwise the auto-stacked modules would re-stack around its new
     * spot and appear to move along with it.
     */
    fun pinAll(drawnAt: Map<String, ModulePlacement>): DashboardLayout =
        copy(placements = drawnAt.filterKeys { it !in hidden } + placements)

    fun hide(id: String) = copy(hidden = hidden + id, placements = placements - id, order = order - id)

    fun show(id: String) = copy(hidden = hidden - id)

    /** Sets module [id]'s font, or back to the app's font when [fontName] is null. */
    fun setFont(id: String, fontName: String?) =
        copy(fonts = if (fontName == null) fonts - id else fonts + (id to fontName))

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
        .put("fonts", JSONObject(fonts))
        .put("banner", JSONObject().apply {
            banner.forEach { (id, p) -> put(id, JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble())) }
        })
        .put("snapToGrid", snapToGrid)
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
                val fontsJson = root.optJSONObject("fonts") ?: JSONObject()
                val fonts = fontsJson.keys().asSequence().associateWith { fontsJson.getString(it) }
                val bannerJson = root.optJSONObject("banner") ?: JSONObject()
                val banner = bannerJson.keys().asSequence().associateWith { id ->
                    val p = bannerJson.getJSONObject(id)
                    BannerPosition(p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
                }
                DashboardLayout(placements, strings("hidden").toSet(), strings("order"), fonts, banner, root.optBoolean("snapToGrid", true))
            }.getOrDefault(DashboardLayout())
        }
    }
}

/** A module's footprint on the canvas, in pixels, for [compact]. [order] is where the user wants it (smaller = higher). */
data class LayoutBox(val id: String, val x: Int, val width: Int, val order: Float, val height: Int)

/**
 * Vertical compaction: every module keeps its horizontal position but floats up as far as it can
 * without overlapping a module above it, so moving or hiding a module never leaves a gap. Modules
 * are settled in [LayoutBox.order]; side-by-side modules that don't overlap horizontally can share
 * a row. Returns each module's y position.
 */
fun compact(boxes: List<LayoutBox>, gap: Int): Map<String, Int> {
    val settled = mutableListOf<Pair<LayoutBox, Int>>()
    boxes.sortedWith(compareBy({ it.order }, { it.x })).forEach { box ->
        var y = 0
        // Move down past anything it would overlap, until it fits.
        while (true) {
            val blocker = settled.firstOrNull { (other, otherY) ->
                box.x < other.x + other.width && other.x < box.x + box.width &&
                    y < otherY + other.height + gap && otherY < y + box.height + gap
            } ?: break
            y = blocker.second + blocker.first.height + gap
        }
        settled += box to y
    }
    return settled.associate { (box, y) -> box.id to y }
}
