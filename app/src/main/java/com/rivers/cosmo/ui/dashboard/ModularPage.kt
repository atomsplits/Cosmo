package com.rivers.cosmo.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A page made of modules the user can rearrange, resize, rescale and hide, like the Wallet
 * dashboard. Each [page] keeps its own saved layout. [header] is drawn above the toolbar and can
 * use the layout too (the Wallet banner does).
 */
@Composable
fun ModularPage(
    page: String,
    title: String?,
    modules: List<DashboardModule>,
    layoutStore: DashboardLayoutStore,
    modifier: Modifier = Modifier,
    header: @Composable (layout: DashboardLayout, editing: Boolean, update: (DashboardLayout) -> Unit) -> Unit = { _, _, _ -> }
) {
    var layout by remember(page) { mutableStateOf(layoutStore.load(page)) }
    var editing by rememberSaveable(page) { mutableStateOf(false) }
    fun update(newLayout: DashboardLayout) {
        layout = newLayout
        layoutStore.save(newLayout, page)
    }
    Column(modifier) {
        header(layout, editing, ::update)
        DashboardToolbar(
            title = title,
            editing = editing,
            onEditingChange = { editing = it },
            hiddenModules = modules.filter { it.id in layout.hidden },
            onShow = { update(layout.show(it)) },
            onReset = { update(DashboardLayout()) },
            snapToGrid = layout.snapToGrid,
            onSnapToGridChange = { update(layout.copy(snapToGrid = it)) }
        )
        Dashboard(
            modules = modules,
            layout = layout,
            onLayoutChange = ::update,
            editing = editing,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
        )
    }
}

/** A module's content on a card, for sections that aren't already cards themselves. */
@Composable
fun WidgetCard(title: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}
