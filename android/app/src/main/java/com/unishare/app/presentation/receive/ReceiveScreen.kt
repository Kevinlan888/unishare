package com.unishare.app.presentation.receive

import com.unishare.app.presentation.format.DisplayFormatters
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unishare.app.R
import com.unishare.app.domain.ContentKind
import com.unishare.app.presentation.components.DeviceIcon
import com.unishare.app.presentation.components.DevicePresence
import com.unishare.app.presentation.components.StatusIndicator
import com.unishare.app.presentation.components.StatusTone
import com.unishare.app.presentation.components.devicePresence

@Composable
fun ReceiveScreen(
    state: ReceiveUiState,
    onBack: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onChangeDestination: () -> Unit,
    onRetryPublication: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Test/verified-final fixture only. Production pending offers intentionally pass null. */
    verifiedPreview: ImageBitmap? = null,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
        ) {
            ReceiveTopBar(state.kind, onBack)
            Spacer(Modifier.height(24.dp))
            ReceivePreview(state, verifiedPreview)
            Spacer(Modifier.height(16.dp))
            Text(
                text = state.displayName.ifBlank {
                    stringResource(
                        if (state.kind == ContentKind.TEXT) R.string.receive_unknown_text
                        else R.string.receive_unknown_file,
                    )
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = receiveMetadata(state),
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(34.dp))
            Text(
                text = stringResource(R.string.receive_from),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SourceDeviceRow(state)
            Spacer(Modifier.height(32.dp))
            DecisionActions(state, onReject, onAccept, onOpen)
            Spacer(Modifier.height(24.dp))
            if (state.kind != ContentKind.TEXT) {
                DestinationSection(state, onChangeDestination, onRetryPublication)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReceiveTopBar(kind: ContentKind, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
        Text(
            text = stringResource(
                if (kind == ContentKind.TEXT) R.string.receive_text_title else R.string.receive_title,
            ),
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ReceivePreview(state: ReceiveUiState, preview: ImageBitmap?) {
    val shape = MaterialTheme.shapes.large
    if (state.kind == ContentKind.TEXT && state.phase == ReceivePhase.COMPLETED && state.receivedText != null) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            SelectionContainer {
                Text(
                    text = state.receivedText,
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        return
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.08f),
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = stringResource(R.string.receive_image_preview, state.displayName),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (state.kind == ContentKind.IMAGE) Icons.Outlined.Image else Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = stringResource(
                        if (state.kind == ContentKind.TEXT) R.string.receive_safe_text_preview
                        else R.string.receive_safe_preview,
                    ),
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SourceDeviceRow(state: ReceiveUiState) {
    val presence = devicePresence(state.source.online, state.source.connected)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
            DeviceIcon(
            platform = state.source.platform,
            contentDescription = stringResource(R.string.receive_source_device_icon),
            modifier = Modifier.size(48.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.source.name,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusIndicator(
                label = stringResource(when (presence) {
                    DevicePresence.CONNECTED -> R.string.status_connected
                    DevicePresence.ONLINE -> R.string.status_online
                    DevicePresence.OFFLINE -> R.string.status_offline
                }),
                tone = if (presence != DevicePresence.OFFLINE) StatusTone.ONLINE else StatusTone.NEUTRAL,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun DecisionActions(
    state: ReceiveUiState,
    onReject: () -> Unit,
    onAccept: () -> Unit,
    onOpen: () -> Unit,
) {
    when (state.phase) {
        ReceivePhase.PENDING, ReceivePhase.RECEIVING -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onReject,
                enabled = state.actionsEnabled,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(stringResource(R.string.receive_reject), fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onAccept,
                enabled = state.actionsEnabled,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
                shape = CircleShape,
            ) {
                Text(
                    text = stringResource(R.string.receive_accept),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        ReceivePhase.COMPLETED -> if (state.kind == ContentKind.TEXT) {
            ReceiveMessage(stringResource(R.string.receive_text_received), error = false)
        } else {
            Button(
                onClick = onOpen,
                enabled = state.canOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = CircleShape,
            ) {
                Text(stringResource(R.string.receive_open), fontWeight = FontWeight.SemiBold)
            }
        }
        ReceivePhase.EXPIRED -> ReceiveMessage(
            text = stringResource(R.string.receive_expired),
            error = true,
        )
        ReceivePhase.REJECTED -> ReceiveMessage(
            stringResource(
                if (state.kind == ContentKind.TEXT) R.string.receive_text_rejected
                else R.string.receive_rejected,
            ),
            error = false,
        )
        ReceivePhase.FAILED -> ReceiveMessage(stringResource(R.string.receive_failed), error = true)
        ReceivePhase.DIRECTORY_ERROR -> Unit
    }
}

@Composable
private fun ReceiveMessage(text: String, error: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DestinationSection(
    state: ReceiveUiState,
    onChangeDestination: () -> Unit,
    onRetryPublication: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 22.dp)) {
            Text(
                text = stringResource(R.string.receive_saved_to),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.destinationDisplayName
                        ?: stringResource(R.string.receive_destination_download),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onChangeDestination, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(
                        if (state.showDestinationRecovery) R.string.receive_restore_downloads
                        else R.string.receive_destination_info,
                    ))
                }
            }
            if (state.showUnsupportedDestinationNotice) {
                Text(
                    text = stringResource(R.string.receive_custom_destination_unavailable),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.showDestinationRecovery) {
                Text(
                    text = stringResource(R.string.receive_directory_error),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(
                    onClick = onRetryPublication,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(stringResource(R.string.receive_retry_publication))
                }
            }
        }
    }
}

@Composable
private fun receiveMetadata(state: ReceiveUiState): String {
    val size = DisplayFormatters.bytes(LocalContext.current, state.byteCount)
    val type = state.mimeType.substringAfterLast('/').ifBlank {
        state.kind.name
    }.uppercase()
    return stringResource(R.string.receive_metadata, size, type)
}
