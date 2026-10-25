package com.rivers.cosmo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.kyc.KycController
import com.rivers.cosmo.kyc.KycStatus

/** The user's identity verification status, with a button to start or continue it. */
@Composable
fun KycSection(state: KycController.State, starting: Boolean, onVerify: () -> Unit, onRefresh: () -> Unit) {
    val status = state.status
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusLabel(status, state.loading),
                    style = MaterialTheme.typography.titleSmall,
                    color = when (status) {
                        KycStatus.APPROVED -> MaterialTheme.colorScheme.primary
                        KycStatus.REJECTED, KycStatus.NEEDS_RESUBMISSION -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh") }
            }
            Text(statusDetail(status), style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (status == null || status.canVerify) {
                Button(onClick = onVerify, enabled = !starting, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            starting -> "Starting…"
                            status == KycStatus.INCOMPLETE -> "Continue verification"
                            status == KycStatus.NEEDS_RESUBMISSION -> "Fix and resubmit"
                            else -> "Verify identity"
                        }
                    )
                }
            }
        }
    }
}

private fun statusLabel(status: KycStatus?, loading: Boolean): String = when (status) {
    null -> if (loading) "Checking…" else "Status unknown"
    KycStatus.NOT_STARTED -> "Not verified"
    KycStatus.INCOMPLETE -> "Verification not finished"
    KycStatus.IN_REVIEW -> "In review"
    KycStatus.APPROVED -> "✓ Verified"
    KycStatus.NEEDS_RESUBMISSION -> "Needs another look"
    KycStatus.REJECTED -> "Verification declined"
}

private fun statusDetail(status: KycStatus?): String = when (status) {
    KycStatus.IN_REVIEW -> "Your documents were submitted. This usually takes a few minutes."
    KycStatus.APPROVED -> "Your identity has been verified."
    KycStatus.NEEDS_RESUBMISSION -> "Something in your submission couldn't be checked. Open verification to see what to fix."
    KycStatus.REJECTED -> "Your identity couldn't be verified. Contact support if you think this is a mistake."
    else -> "Verify your identity with a photo ID and a quick selfie. Verification is handled by Sumsub."
}
