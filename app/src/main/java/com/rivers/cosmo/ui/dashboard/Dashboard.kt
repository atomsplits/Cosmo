package com.rivers.cosmo.ui.dashboard

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.AppFont
import com.rivers.cosmo.ui.theme.fontFamily
import com.rivers.cosmo.ui.theme.typographyFor
import kotlin.math.roundToInt

/** One movable, resizable block on the dashboard. */
class DashboardModule(
    val id: String,
    val title: String,
    /** True when the module has nothing to show right now (e.g. the activation card once activated). */
    val isEmpty: Boolean = false,
    /** Shown instead of the content in edit mode while [isEmpty], so the module can still be moved. */
    val emptyHint: String? = null,
    val content: @Composable () -> Unit
)

/** Saves the dashboard arrangement on this device. */
class DashboardLayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("cosmo_dashboard", Context.MODE_PRIVATE)
    fun load(): DashboardLayout = DashboardLayout.fromJson(prefs.getString(KEY, null))
    fun save(layout: DashboardLayout) = prefs.edit().putString(KEY, layout.toJson()).apply()
    private companion object { const val KEY = "layout" }
}

/**
 * A free-form canvas of modules. In edit mode each module can be dragged anywhere, resized with
 * its corner handle, scaled with A−/A+, or hidden; outside edit mode they work normally.
 */
@Composable
fun Dashboard(
    modules: List<DashboardModule>,
    layout: DashboardLayout,
    onLayoutChange: (DashboardLayout) -> Unit,
    editing: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // Live gesture offsets in px, applied on top of the saved placement until the gesture ends.
    val dragOffsets = remember { mutableStateMapOf<String, Offset>() }
    val widthDeltas = remember { mutableStateMapOf<String, Float>() }
    // Where auto-stacked modules ended up, so dragging one starts from where it's drawn.
    val autoPlacements = remember { HashMap<String, ModulePlacement>() }
    // The one module being dragged or resized; other touches are ignored until it's released.
    var activeGesture by remember { mutableStateOf<String?>(null) }
    val visible = modules.filterNot { it.id in layout.hidden }
    val drawOrder = layout.drawingOrder(visible.map { it.id })
    val byId = visible.associateBy { it.id }

    BoxWithConstraints(modifier.verticalScroll(rememberScrollState())) {
        val canvasWidthPx = constraints.maxWidth.toFloat()
        val canvasWidthDp = with(density) { canvasWidthPx.toDp().value }
        fun current(id: String) = layout.placements[id] ?: autoPlacements[id] ?: ModulePlacement(0f, 0f, 1f)

        fun commit(id: String, placement: ModulePlacement) {
            dragOffsets.remove(id)
            widthDeltas.remove(id)
            val drawnAt = visible.associate { it.id to current(it.id) }
            onLayoutChange(layout.pinAll(drawnAt).place(id, placement.normalized(canvasWidthDp)))
        }

        /** Claims the gesture for [id], or returns false if another module is already being moved. */
        fun claim(id: String): Boolean {
            if (activeGesture == null) activeGesture = id
            return activeGesture == id
        }

        fun release(id: String): Boolean {
            if (activeGesture != id) return false
            activeGesture = null
            return true
        }

        Layout(
            content = {
                drawOrder.forEach { id ->
                    val module = byId.getValue(id)
                    // Keyed so each module keeps its own state when the drawing order changes.
                    key(id) { ModuleFrame(
                        module = module,
                        scale = current(id).scale,
                        editing = editing,
                        onDrag = { delta -> if (claim(id)) dragOffsets[id] = (dragOffsets[id] ?: Offset.Zero) + delta },
                        onDragEnd = {
                            if (!release(id)) return@ModuleFrame
                            val offset = dragOffsets[id] ?: Offset.Zero
                            val start = current(id)
                            commit(id, start.copy(x = start.x + offset.x / canvasWidthPx, y = start.y + with(density) { offset.y.toDp().value }))
                        },
                        onResize = { dx -> if (claim(id)) widthDeltas[id] = (widthDeltas[id] ?: 0f) + dx },
                        onResizeEnd = {
                            if (!release(id)) return@ModuleFrame
                            val start = current(id)
                            commit(id, start.copy(width = start.width + (widthDeltas[id] ?: 0f) / canvasWidthPx))
                        },
                        onScale = { step -> current(id).let { commit(id, it.copy(scale = it.scale + step)) } },
                        onHide = { onLayoutChange(layout.hide(id)) },
                        font = layout.fonts[id]?.let { name -> AppFont.entries.firstOrNull { it.name == name } },
                        onFontChange = { font -> onLayoutChange(layout.setFont(id, font?.name)) }
                    ) }
                }
            }
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val gap = with(density) { 12.dp.roundToPx() }
            // Modules the user hasn't placed yet stack, in their natural order, under everything placed.
            var nextAutoY = layout.placements.filterKeys { it in byId }.values
                .maxOfOrNull { with(density) { it.y.dp.roundToPx() } }?.let { it + gap } ?: 0
            val autoOrder = visible.map { it.id }.filterNot { it in layout.placements }
            val placeables = drawOrder.zip(measurables).associate { (id, measurable) ->
                val base = layout.placements[id] ?: ModulePlacement(0f, 0f, 1f)
                val moduleWidth = ((base.width * width) + (widthDeltas[id] ?: 0f))
                    .coerceIn(ModulePlacement.MIN_WIDTH * width, width.toFloat()).roundToInt()
                id to measurable.measure(Constraints(minWidth = moduleWidth, maxWidth = moduleWidth))
            }
            autoOrder.forEach { id ->
                autoPlacements[id] = ModulePlacement(0f, with(density) { nextAutoY.toDp().value }, 1f, layout.placements[id]?.scale ?: 1f)
                nextAutoY += placeables.getValue(id).height + gap
            }
            val positions = drawOrder.associateWith { id ->
                val placement = current(id)
                val offset = dragOffsets[id] ?: Offset.Zero
                val x = (placement.x * width + offset.x).roundToInt().coerceIn(0, (width - placeables.getValue(id).width).coerceAtLeast(0))
                val y = (with(density) { placement.y.dp.toPx() } + offset.y).roundToInt().coerceAtLeast(0)
                x to y
            }
            val height = drawOrder.maxOfOrNull { positions.getValue(it).second + placeables.getValue(it).height } ?: 0
            layout(width, height + gap) {
                drawOrder.forEach { id ->
                    val (x, y) = positions.getValue(id)
                    placeables.getValue(id).place(x, y)
                }
            }
        }
    }
}

@Composable
private fun ModuleFrame(
    module: DashboardModule,
    scale: Float,
    /** This module's own font, or null to use the app's font. */
    font: AppFont?,
    onFontChange: (AppFont?) -> Unit,
    editing: Boolean,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onResize: (Float) -> Unit,
    onResizeEnd: () -> Unit,
    onScale: (Float) -> Unit,
    onHide: () -> Unit
) {
    // The gesture handlers below outlive recompositions, so read the latest callbacks through these.
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val resize by rememberUpdatedState(onResize)
    val resizeEnd by rememberUpdatedState(onResizeEnd)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .then(if (editing) Modifier.border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), shape) else Modifier)
            .padding(if (editing) 4.dp else 0.dp)
    ) {
        if (editing) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    module.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
                IconButton(onClick = { onScale(-ModulePlacement.SCALE_STEP) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Remove, contentDescription = "Make ${module.title} smaller")
                }
                IconButton(onClick = { onScale(ModulePlacement.SCALE_STEP) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "Make ${module.title} bigger")
                }
                FontPicker(module.title, font, onFontChange)
                IconButton(onClick = onHide, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.VisibilityOff, contentDescription = "Hide ${module.title}")
                }
            }
        }
        Box {
            Box(Modifier.scaledContent(scale)) {
                when {
                    !module.isEmpty -> WithFont(font) { module.content() }
                    editing -> Text(
                        module.emptyHint ?: "Nothing to show right now",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
            }
            if (editing) {
                // Covers the content so taps don't trigger its buttons, and turns drags into moves.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(module.id) {
                            detectDragGestures(onDragEnd = { dragEnd() }, onDragCancel = { dragEnd() }) { change, amount ->
                                change.consume()
                                drag(amount)
                            }
                        }
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                        .pointerInput(module.id) {
                            detectDragGestures(onDragEnd = { resizeEnd() }, onDragCancel = { resizeEnd() }) { change, amount ->
                                change.consume()
                                resize(amount.x)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.OpenInFull, contentDescription = "Resize ${module.title}", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * Draws content [scale] times its normal size while still filling the given width: the content is
 * laid out at width/scale and then scaled, so text grows or shrinks and reflows to fit.
 */
private fun Modifier.scaledContent(scale: Float): Modifier = layout { measurable, constraints ->
    val innerWidth = (constraints.maxWidth / scale).roundToInt()
    val placeable = measurable.measure(Constraints(minWidth = innerWidth, maxWidth = innerWidth))
    val width = (placeable.width * scale).roundToInt()
    val height = (placeable.height * scale).roundToInt()
    layout(width, height) {
        placeable.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

/** Edit / Add widget / Reset controls shown above the dashboard. */
@Composable
fun DashboardToolbar(
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    hiddenModules: List<DashboardModule>,
    onShow: (String) -> Unit,
    onReset: () -> Unit
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
    ) {
        if (editing) {
            TextButton(onClick = { adding = true }, enabled = hiddenModules.isNotEmpty()) { Text("Add widget") }
            TextButton(onClick = { confirmingReset = true }) { Text("Reset") }
        }
        TextButton(onClick = { onEditingChange(!editing) }) { Text(if (editing) "Done" else "Edit layout") }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add widget") },
            text = {
                Column {
                    hiddenModules.forEach { module ->
                        Text(
                            module.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onShow(module.id)
                                    adding = false
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("Close") } }
        )
    }
    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Reset layout?") },
            text = { Text("All widgets go back to their original places and sizes, and hidden ones come back.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    onReset()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmingReset = false }) { Text("Cancel") } }
        )
    }
}

/** Draws [content] with every text style in [font], or unchanged when [font] is null. */
@Composable
private fun WithFont(font: AppFont?, content: @Composable () -> Unit) {
    if (font == null) {
        content()
        return
    }
    val typography = remember(font) { typographyFor(font.fontFamily) }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = typography, content = content)
}

/** The "Aa" button in a module's edit toolbar: picks a font for just that module. */
@Composable
private fun FontPicker(title: String, font: AppFont?, onFontChange: (AppFont?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.TextFields, contentDescription = "Font for $title")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("App font" + if (font == null) "  ✓" else "") },
                onClick = { onFontChange(null); open = false }
            )
            AppFont.entries.forEach { option ->
                DropdownMenuItem(
                    // Each option is shown in its own font so it previews itself.
                    text = { Text(option.label + if (option == font) "  ✓" else "", fontFamily = option.fontFamily) },
                    onClick = { onFontChange(option); open = false }
                )
            }
        }
    }
}
