package com.rivers.cosmo.ui.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.BannerSettings
import com.rivers.cosmo.ui.banner.AnimatedBanner
import com.rivers.cosmo.ui.banner.textColorOnBanner
import com.rivers.cosmo.ui.brand.CosmoLogo
import com.rivers.cosmo.ui.dashboard.BannerGrid
import com.rivers.cosmo.ui.dashboard.BannerPosition
import com.rivers.cosmo.ui.dashboard.BannerTextAlign
import com.rivers.cosmo.ui.dashboard.DashboardLayout
import com.rivers.cosmo.wallet.StellarNetwork

private val LOGO_SIZE = 56.dp
private const val EDGE_DP = 20f

/**
 * The animated banner across the top of the Wallet tab, styled in Settings → Banner. In edit
 * mode its logo and title can be dragged around (snapping to a grid unless that's switched off),
 * the banner can be made taller or shorter with the handle on its bottom edge, and the title's
 * lines can be aligned left, center or right.
 */
@Composable
fun WalletBanner(
    banner: BannerSettings,
    network: StellarNetwork,
    layout: DashboardLayout,
    editing: Boolean,
    onLayoutChange: (DashboardLayout) -> Unit
) {
    val baseColor = if (banner.matchTheme) MaterialTheme.colorScheme.primary else Color(banner.color)
    val textColor = Color(textColorOnBanner(banner.style, baseColor.toArgb()))
    // A soft shadow keeps the title legible over the busier, moving parts of the animation.
    val shadow = Shadow(if (textColor == Color.White) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f), blurRadius = 8f)
    val snap = editing && layout.snapToGrid
    val density = LocalDensity.current
    // The height while the resize handle is being dragged (unsnapped), else the saved one.
    var draggedHeight by remember { mutableStateOf<Float?>(null) }
    val bannerHeight = draggedHeight?.let { BannerGrid.height(it, snap) } ?: layout.bannerHeight ?: BannerGrid.DEFAULT_HEIGHT_DP
    // The title's size in dp, for lining it up when the alignment changes.
    var titleSize by remember { mutableStateOf(Offset.Zero) }
    var measuredWidth by remember { mutableStateOf(0f) }
    val align = layout.titleAlign
    val onMove: (String, BannerPosition) -> Unit = { id, position -> onLayoutChange(layout.moveBannerItem(id, position)) }
    Column {
        AnimatedBanner(
            style = banner.style,
            baseColor = baseColor,
            animated = banner.animated,
            modifier = Modifier
                .fillMaxWidth()
                .height(bannerHeight.dp)
                .onSizeChanged { measuredWidth = with(density) { it.width.toDp().value } }
        ) {
            BoxWithConstraints(Modifier.matchParentSize()) {
                val width = maxWidth.value
                val height = maxHeight.value
                if (snap) GridLines(textColor)
                BannerItem(
                    id = "logo",
                    label = "logo",
                    saved = layout.banner["logo"],
                    // By default the logo sits at the right, vertically centred.
                    default = { w, h -> BannerPosition(width - EDGE_DP - w, (height - h) / 2) },
                    bannerWidth = width,
                    bannerHeight = height,
                    editing = editing,
                    snap = snap,
                    outline = textColor,
                    onMove = onMove
                ) {
                    CosmoLogo(Modifier.size(LOGO_SIZE), tint = textColor)
                }
                BannerItem(
                    id = "title",
                    label = "title",
                    saved = layout.banner["title"],
                    // By default the title sits at the bottom, on the side it's aligned to.
                    default = { w, h -> BannerPosition(alignedX(align, w, width), height - EDGE_DP - h) },
                    bannerWidth = width,
                    bannerHeight = height,
                    editing = editing,
                    snap = snap,
                    outline = textColor,
                    onMove = onMove,
                    onSize = { titleSize = it }
                ) {
                    val textAlign = when (align) {
                        BannerTextAlign.Start -> TextAlign.Start
                        BannerTextAlign.Center -> TextAlign.Center
                        BannerTextAlign.End -> TextAlign.End
                    }
                    Column(
                        horizontalAlignment = when (align) {
                            BannerTextAlign.Start -> Alignment.Start
                            BannerTextAlign.Center -> Alignment.CenterHorizontally
                            BannerTextAlign.End -> Alignment.End
                        }
                    ) {
                        Text("Cosmo", style = MaterialTheme.typography.headlineMedium.copy(shadow = shadow), color = textColor, textAlign = textAlign)
                        Text(
                            if (network == StellarNetwork.Testnet) "Wallet · Test network" else "Wallet",
                            style = MaterialTheme.typography.labelLarge.copy(shadow = shadow),
                            color = textColor.copy(alpha = 0.85f),
                            textAlign = textAlign
                        )
                    }
                }
                if (editing) {
                    ResizeHandle(
                        color = textColor,
                        onDrag = { dy -> draggedHeight = (draggedHeight ?: bannerHeight) + dy / density.density },
                        onDragEnd = {
                            draggedHeight?.let { onLayoutChange(layout.copy(bannerHeight = BannerGrid.height(it, snap))) }
                            draggedHeight = null
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
        if (editing) {
            TitleAlignmentRow(
                selected = align,
                onSelect = { newAlign ->
                    // Line the title up on that side too, keeping its height in the banner.
                    val y = layout.banner["title"]?.y ?: (bannerHeight - EDGE_DP - titleSize.y)
                    val x = alignedX(newAlign, titleSize.x, measuredWidth)
                    onLayoutChange(layout.copy(titleAlign = newAlign).moveBannerItem("title", BannerPosition(x, y)))
                }
            )
        }
    }
}

/** Where an item [itemWidth] wide goes for an alignment, in a banner [bannerWidth] wide (dp). */
private fun alignedX(align: BannerTextAlign, itemWidth: Float, bannerWidth: Float): Float = when (align) {
    BannerTextAlign.Start -> EDGE_DP
    BannerTextAlign.Center -> (bannerWidth - itemWidth) / 2
    BannerTextAlign.End -> bannerWidth - EDGE_DP - itemWidth
}.coerceAtLeast(0f)

/** The pill on the banner's bottom edge that makes it taller or shorter. */
@Composable
private fun ResizeHandle(color: Color, onDrag: (Float) -> Unit, onDragEnd: () -> Unit, modifier: Modifier = Modifier) {
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    Box(
        modifier
            .size(width = 72.dp, height = 24.dp)
            .semantics { contentDescription = "Banner height. Drag up or down to resize." }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        drag(amount)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(width = 40.dp, height = 5.dp).background(color.copy(alpha = 0.8f), RoundedCornerShape(50)))
    }
}

/** Shown under the banner in edit mode: aligns the title's lines left, center or right. */
@Composable
private fun TitleAlignmentRow(selected: BannerTextAlign, onSelect: (BannerTextAlign) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 4.dp)) {
        Text("Title alignment", style = MaterialTheme.typography.labelLarge)
        listOf(
            Triple(BannerTextAlign.Start, Icons.AutoMirrored.Filled.FormatAlignLeft, "Align title left"),
            Triple(BannerTextAlign.Center, Icons.Filled.FormatAlignCenter, "Align title center"),
            Triple(BannerTextAlign.End, Icons.AutoMirrored.Filled.FormatAlignRight, "Align title right")
        ).forEach { (align, icon, label) ->
            IconToggleButton(checked = align == selected, onCheckedChange = { onSelect(align) }) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (align == selected) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
        }
    }
}

/** Faint lines on the snapping grid, shown while items can be moved. */
@Composable
private fun BoxScope.GridLines(color: Color) {
    Canvas(Modifier.matchParentSize()) {
        val step = BannerGrid.STEP_DP.dp.toPx()
        val line = color.copy(alpha = 0.22f)
        var x = step
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = step
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}

/**
 * One movable item in the banner. While dragging it follows the finger, jumping from grid point to
 * grid point when snapping (with a light tick), and its final spot is saved when the drag ends.
 */
@Composable
private fun BannerItem(
    id: String,
    label: String,
    saved: BannerPosition?,
    default: (width: Float, height: Float) -> BannerPosition,
    bannerWidth: Float,
    bannerHeight: Float,
    editing: Boolean,
    snap: Boolean,
    outline: Color,
    onMove: (String, BannerPosition) -> Unit,
    onSize: (Offset) -> Unit = {},
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    // The item's size in dp, known after its first layout.
    var itemWidth by remember { mutableStateOf(0f) }
    var itemHeight by remember { mutableStateOf(0f) }
    // Where the finger has dragged the item to (unsnapped), while a drag is in progress.
    var dragged by remember { mutableStateOf<Offset?>(null) }

    val start = saved ?: default(itemWidth, itemHeight)
    val raw = dragged ?: Offset(start.x, start.y)
    // Snapping applies to dragging; saved spots (like a centered title) are drawn where they were put.
    val shown = BannerGrid.place(raw.x, raw.y, itemWidth, itemHeight, bannerWidth, bannerHeight, snap && dragged != null)

    // The gesture outlives recompositions, so it reads the latest values through these.
    val current by rememberUpdatedState(shown)
    val snapping by rememberUpdatedState(snap)
    val bounds by rememberUpdatedState(arrayOf(itemWidth, itemHeight, bannerWidth, bannerHeight))
    val move by rememberUpdatedState(onMove)

    Box(
        Modifier
            .offset(shown.x.dp, shown.y.dp)
            .onSizeChanged {
                with(density) { itemWidth = it.width.toDp().value; itemHeight = it.height.toDp().value }
                onSize(Offset(itemWidth, itemHeight))
            }
            .then(
                if (!editing) Modifier else Modifier
                    .border(1.5.dp, outline.copy(alpha = if (dragged != null) 0.9f else 0.5f), RoundedCornerShape(8.dp))
                    .padding(4.dp)
                    .semantics { contentDescription = "Banner $label. Drag to move." }
                    .pointerInput(id) {
                        detectDragGestures(
                            onDragStart = { dragged = Offset(current.x, current.y) },
                            onDragEnd = {
                                move(id, current)
                                dragged = null
                            },
                            onDragCancel = { dragged = null },
                            onDrag = { change, amount ->
                                change.consume()
                                val before = current
                                val next = (dragged ?: Offset(before.x, before.y)) + Offset(amount.x / density.density, amount.y / density.density)
                                dragged = next
                                if (snapping) {
                                    val (w, h, bw, bh) = bounds
                                    if (BannerGrid.place(next.x, next.y, w, h, bw, bh, true) != before) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        )
                    }
            )
    ) {
        content()
    }
}
