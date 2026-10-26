package com.rivers.cosmo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Lets screenshots and screen recordings show the sign-up and log-in screens. */
@Composable
fun PrivacySection(allowScreenCapture: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = allowScreenCapture, role = Role.Switch, onValueChange = onChange)
        ) {
            Text("Allow screenshots and screen recording", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Switch(checked = allowScreenCapture, onCheckedChange = null)
        }
        Text(
            if (allowScreenCapture) {
                "Screenshots and recordings can capture your recovery phrase on the sign-up and log-in screens. Turn this off when you're done recording."
            } else {
                "The sign-up and log-in screens stay blank in screenshots and recordings, so your recovery phrase can't be captured."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
