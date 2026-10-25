package com.rivers.cosmo.ui.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.BannerSettings
import com.rivers.cosmo.ui.banner.AnimatedBanner
import com.rivers.cosmo.ui.banner.textColorOnBanner
import com.rivers.cosmo.ui.brand.CosmoLogo
import com.rivers.cosmo.ui.dashboard.BannerGrid
import com.rivers.cosmo.ui.dashboard.BannerPosition
import com.rivers.cosmo.ui.dashboard.DashboardLayout
import com.rivers.cosmo.wallet.StellarNetwork

private val BANNER_HEIGHT = 140.dp
private val LOGO_SIZE = 56.dp
private const val EDGE_DP = 20f

/**
 * The animated banner across the top of the Wallet tab, styled in Settings → Banner. In edit
 * mode its logo and title can be dragged around, snapping to a grid unless that's switched off.
 */
@Composable
fun WalletBanner(
    banner: BannerSettings,
    network: StellarNetwork,
    layout: DashboardLayout,
    editing: Boolean,
    onMove: (id: String, BannerPosition) -> Unit
) {
    val baseColor = if (banner.matchTheme) MaterialTheme.colorScheme.primary else Color(banner.color)
    val textColor = Color(textColorOnBanner(banner.style, baseColor.toArgb()))
    // A soft shadow keeps the title legible over the busier, moving parts of the animation.
    val shadow = Shadow(if (textColor == Color.White) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f), blurRadius = 8f)
    val snap = editing && layout.snapToGrid
    AnimatedBanner(
        style = banner.style,
        baseColor = baseColor,
        animated = banner.animated,
        modifier = Modifier.fillMaxWidth().height(BANNER_HEIGHT)
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
                // By default the title sits at the bottom left.
                default = { _, h -> BannerPosition(EDGE_DP, height - EDGE_DP - h) },
                bannerWidth = width,
                bannerHeight = height,
                editing = editing,
                snap = snap,
                outline = textColor,
                onMove = onMove
            ) {
                Column {
                    Text("Cosmo", style = MaterialTheme.typography.headlineMedium.copy(shadow = shadow), color = textColor)
                    Text(
                        if (network == StellarNetwork.Testnet) "Wallet · Test network" else "Wallet",
                        style = MaterialTheme.typography.labelLarge.copy(shadow = shadow),
                        color = textColor.copy(alpha = 0.85f)
                    )
                }
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
    val shown = BannerGrid.place(raw.x, raw.y, itemWidth, itemHeight, bannerWidth, bannerHeight, snap)

    // The gesture outlives recompositions, so it reads the latest values through these.
    val current by rememberUpdatedState(shown)
    val snapping by rememberUpdatedState(snap)
    val bounds by rememberUpdatedState(arrayOf(itemWidth, itemHeight, bannerWidth, bannerHeight))
    val move by rememberUpdatedState(onMove)

    Box(
        Modifier
            .offset(shown.x.dp, shown.y.dp)
            .onSizeChanged { with(density) { itemWidth = it.width.toDp().value; itemHeight = it.height.toDp().value } }
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
