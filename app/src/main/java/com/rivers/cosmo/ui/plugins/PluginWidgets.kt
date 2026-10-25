package com.rivers.cosmo.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.plugins.PluginHost
import com.rivers.cosmo.plugins.UiNode
import com.rivers.cosmo.settings.ColorMath
import com.rivers.cosmo.ui.dashboard.DashboardModule

/** Dashboard modules for every widget the enabled plugins registered. */
fun pluginModules(widgets: List<PluginHost.WidgetState>, onClick: (widgetKey: String, action: Int) -> Unit): List<DashboardModule> =
    widgets.map { widget ->
        DashboardModule(widget.moduleId, "${widget.title} (${widget.pluginName})") {
            PluginWidgetCard(widget, onClick = { action -> onClick(widget.key, action) })
        }
    }

@Composable
private fun PluginWidgetCard(widget: PluginHost.WidgetState, onClick: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${widget.title} · plugin",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when {
                widget.error != null -> Text(widget.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                widget.node != null -> RenderNode(widget.node, onClick)
                else -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Draws a plugin's UI tree with the app's own components and theme. */
@Composable
private fun RenderNode(node: UiNode, onClick: (Int) -> Unit) {
    when (node) {
        is UiNode.Text -> Text(
            node.text,
            style = when (node.style) {
                "headline" -> MaterialTheme.typography.headlineSmall
                "title" -> MaterialTheme.typography.titleMedium
                "label" -> MaterialTheme.typography.labelLarge
                "caption" -> MaterialTheme.typography.bodySmall
                else -> MaterialTheme.typography.bodyMedium
            },
            color = node.color?.let(ColorMath::parseHex)?.let(::Color) ?: Color.Unspecified
        )
        is UiNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.children.forEach { RenderNode(it, onClick) }
        }
        is UiNode.Row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            node.children.forEach { RenderNode(it, onClick) }
        }
        is UiNode.Button -> OutlinedButton(onClick = { onClick(node.action) }) { Text(node.label) }
        is UiNode.Progress -> LinearProgressIndicator(progress = { node.value }, modifier = Modifier.fillMaxWidth())
        is UiNode.Spacer -> Spacer(Modifier.height(node.height.dp))
        UiNode.Divider -> HorizontalDivider()
    }
}
