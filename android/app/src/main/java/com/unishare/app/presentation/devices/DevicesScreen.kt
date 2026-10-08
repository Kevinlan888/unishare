package com.unishare.app.presentation.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.unishare.app.R
import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.domain.DeviceKey
import com.unishare.app.presentation.components.*

@Composable
fun DevicesScreen(state: DevicesUiState, onConnect: (DeviceKey) -> Unit, onRequestForget: (DeviceKey) -> Unit, onConfirmForget: () -> Unit, onDismissForget: () -> Unit, onOpen: (DeviceKey) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().semantics { isTraversalGroup = true }, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var order = 0f
        val pageTitleOrder = order++
        item { Text(stringResource(R.string.screen_devices_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { traversalIndex = pageTitleOrder }) }
        if (state.actionFailed) {
            val errorOrder = order++
            item { Text(stringResource(R.string.devices_action_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { traversalIndex = errorOrder }) }
        }
        val sections = listOf(R.string.devices_trusted to state.trusted, R.string.devices_nearby to state.nearby, R.string.devices_offline to state.offline)
        sections.forEach { (title, peers) ->
            val headingOrder = order++
            item { Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { traversalIndex = headingOrder }) }
            if (peers.isEmpty()) {
                val emptyOrder = order++
                item { Text(stringResource(R.string.devices_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { traversalIndex = emptyOrder }) }
            }
            val sectionOffset = order
            itemsIndexed(peers, key = { _, peer -> peer.id.value }) { index, peer ->
                DeviceManagementCard(peer, sectionOffset + index, onConnect, onRequestForget, onOpen)
            }
            order += peers.size
        }
    }
    state.pendingForget?.let { peer ->
        val name = peer.name.ifBlank { stringResource(R.string.unknown_device) }
        val title = stringResource(R.string.devices_forget_title, name)
        val detail = stringResource(R.string.devices_forget_detail)
        val confirm = stringResource(R.string.devices_forget)
        val cancel = stringResource(R.string.action_cancel)
        AlertDialog(onDismissRequest = onDismissForget, title = { Text(title) }, text = { Text(detail) },
            confirmButton = { TextButton(onClick = onConfirmForget, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirm) } },
            dismissButton = { TextButton(onClick = onDismissForget, modifier = Modifier.heightIn(min = 48.dp)) { Text(cancel) } })
    }
}

@Composable
private fun DeviceManagementCard(peer: DeviceSummary, order: Float, onConnect: (DeviceKey) -> Unit, onForget: (DeviceKey) -> Unit, onOpen: (DeviceKey) -> Unit) {
    val name = peer.name.ifBlank { stringResource(R.string.unknown_device) }
    val presence = devicePresence(peer.online, peer.connected)
    val status = if (peer.identityChanged) stringResource(R.string.status_identity_changed) else stringResource(R.string.device_status_format,
        stringResource(if (peer.trusted) R.string.status_trusted else R.string.status_unpaired), stringResource(when (presence) {
            DevicePresence.CONNECTED -> R.string.status_connected
            DevicePresence.ONLINE -> R.string.status_online
            DevicePresence.OFFLINE -> R.string.status_offline
        }))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth().testTag("device-${peer.id.value}").semantics { isTraversalGroup = true; traversalIndex = order }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(peer.id) }, verticalAlignment = Alignment.CenterVertically) {
                    DeviceIcon(peer.platform, stringResource(R.string.device_icon_unknown), Modifier.size(36.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        StatusIndicator(status, if (presence != DevicePresence.OFFLINE && !peer.identityChanged) StatusTone.ONLINE else StatusTone.NEUTRAL)
                    }
                }
                if (peer.trusted) IconButton(onClick = { onForget(peer.id) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.devices_forget_label, name)) }
            }
            if (peer.online && !peer.connected && !peer.identityChanged) TextButton(onClick = { onConnect(peer.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.devices_connect)) }
        }
    }
}
