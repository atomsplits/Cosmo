package com.rivers.cosmo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.EntranceStyle
import com.rivers.cosmo.settings.MotionSettings
import com.rivers.cosmo.settings.MotionSpeed

/** Turns animations on or off and picks how screens and widgets appear, and how fast. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MotionSection(motion: MotionSettings, onChange: (MotionSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = motion.enabled, role = Role.Switch, onValueChange = { onChange(motion.copy(enabled = it)) })
        ) {
            Text("Animations", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Switch(checked = motion.enabled, onCheckedChange = null)
        }
        if (motion.enabled) {
            Text("Entrance", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EntranceStyle.entries.forEach { style ->
                    FilterChip(
                        selected = style == motion.entrance,
                        onClick = { onChange(motion.copy(entrance = style)) },
                        label = { Text(style.label) }
                    )
                }
            }
            Text("Speed", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MotionSpeed.entries.forEach { speed ->
                    FilterChip(
                        selected = speed == motion.speed,
                        onClick = { onChange(motion.copy(speed = speed)) },
                        label = { Text(speed.label) }
                    )
                }
            }
        }
        Text(
            "Animations are also off whenever Android's \"Remove animations\" setting is on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
