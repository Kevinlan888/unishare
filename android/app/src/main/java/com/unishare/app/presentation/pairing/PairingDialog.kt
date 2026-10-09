package com.unishare.app.presentation.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unishare.app.R
import androidx.compose.ui.res.stringResource

@Composable
fun PairingDialog(
    state: PairingUiState,
    onMatch: () -> Unit,
    onMismatch: () -> Unit,
    onDismiss: () -> Unit,
    onForgetAndRepair: () -> Unit,
) {
    when (val prompt = state.prompt) {
        null -> Unit
        is PairingPrompt.Verification -> VerificationDialog(
            prompt = prompt,
            submitting = state.submitting,
            onMatch = onMatch,
            onMismatch = onMismatch,
            onDismiss = onDismiss,
        )
        is PairingPrompt.IdentityChanged -> IdentityChangedDialog(
            prompt = prompt,
            submitting = state.submitting,
            onForgetAndRepair = onForgetAndRepair,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun VerificationDialog(
    prompt: PairingPrompt.Verification,
    submitting: Boolean,
    onMatch: () -> Unit,
    onMismatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spokenCode = prompt.code.toCharArray().joinToString(" ")
    val codeDescription = stringResource(R.string.pairing_code_accessibility, spokenCode)
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Outlined.Devices,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.pairing_title, prompt.deviceName),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.pairing_detail),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = prompt.code.chunked(3).joinToString(" "),
                    modifier = Modifier.semantics {
                        contentDescription = codeDescription
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onMatch,
                enabled = !submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.pairing_codes_match))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onMismatch,
                enabled = !submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.pairing_codes_mismatch))
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    )
}

@Composable
private fun IdentityChangedDialog(
    prompt: PairingPrompt.IdentityChanged,
    submitting: Boolean,
    onForgetAndRepair: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Outlined.GppBad,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.pairing_identity_changed_title)) },
        text = {
            Text(
                stringResource(R.string.pairing_identity_changed_detail, prompt.deviceName),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = onForgetAndRepair,
                enabled = !submitting,
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.pairing_forget_and_repair))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !submitting,
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    )
}
