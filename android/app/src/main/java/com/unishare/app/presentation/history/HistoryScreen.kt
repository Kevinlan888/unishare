package com.unishare.app.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.unishare.app.R
import com.unishare.app.data.history.HistoryQuery
import com.unishare.app.domain.*
import com.unishare.app.presentation.components.ManagementChoice
import com.unishare.app.presentation.format.DisplayFormatters

@Composable
fun HistoryScreen(state: HistoryUiState, onQuery: (HistoryQuery) -> Unit, onOpen: (ActivityKey) -> Unit) {
    val query = state.query
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.screen_history_title), style = MaterialTheme.typography.headlineMedium) }
        item { OutlinedTextField(query.search, { onQuery(query.copy(search = it)) }, label = { Text(stringResource(R.string.history_search)) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { ManagementChoice(stringResource(R.string.history_device), query.device, listOf(null) + state.devices.map { it.id }, { id -> if (id == null) stringResource(R.string.history_all) else state.devices.find { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.history_unknown_device, sanitizedDisplayName(id.value).take(12)) }, { onQuery(query.copy(device = it)) }) }
        item { ManagementChoice(stringResource(R.string.history_direction), query.direction, listOf(null) + ShareDirection.entries, { direction -> directionLabel(direction) }, { onQuery(query.copy(direction = it)) }) }
        item { ManagementChoice(stringResource(R.string.history_type), query.kind, listOf(null) + ContentKind.entries, { kind -> kindLabel(kind) }, { onQuery(query.copy(kind = it)) }) }
        item { ManagementChoice(stringResource(R.string.history_status), query.status, listOf(null) + ActivityStatus.entries, { status -> statusLabel(status) }, { onQuery(query.copy(status = it)) }) }
        if (state.rows.isEmpty()) item { Text(stringResource(R.string.history_empty), modifier = Modifier.padding(vertical = 20.dp)) }
        items(state.rows, key = { it.activity.id.value }) { row ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                val context = LocalContext.current
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(row.activity.displayName.ifBlank { stringResource(R.string.history_unnamed) }, style = MaterialTheme.typography.titleMedium)
                    Text(row.deviceName.ifBlank { stringResource(R.string.unknown_device) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.history_metadata, directionLabel(row.activity.direction), kindLabel(row.activity.kind), DisplayFormatters.bytes(context, row.activity.byteCount)), style = MaterialTheme.typography.bodyMedium)
                    Text(statusLabel(row.activity.status))
                    Text(DisplayFormatters.timestamp(context, row.activity.updatedAtMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (row.activity.direction == ShareDirection.INCOMING && row.activity.kind != ContentKind.TEXT && row.activity.status == ActivityStatus.COMPLETED) {
                        if (!row.canOpen) Text(stringResource(R.string.history_content_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onOpen(row.activity.id) }, enabled = row.canOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.receive_open)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun directionLabel(direction: ShareDirection?): String = stringResource(when (direction) { null -> R.string.history_all; ShareDirection.INCOMING -> R.string.history_incoming; ShareDirection.OUTGOING -> R.string.history_outgoing })
@Composable
private fun kindLabel(kind: ContentKind?): String = stringResource(when (kind) { null -> R.string.history_all; ContentKind.TEXT -> R.string.session_text; ContentKind.IMAGE -> R.string.session_photo; ContentKind.FILE -> R.string.session_file })
@Composable
private fun statusLabel(status: ActivityStatus?): String = stringResource(when (status) {
    null -> R.string.history_all
    ActivityStatus.AWAITING_PEER -> R.string.session_status_waiting_description
    ActivityStatus.TRANSFERRING -> R.string.session_status_transferring_description
    ActivityStatus.COMPLETED -> R.string.session_status_completed_description
    ActivityStatus.REJECTED -> R.string.session_status_rejected_description
    ActivityStatus.CANCELLED -> R.string.session_status_cancelled_description
    ActivityStatus.FAILED -> R.string.session_status_failed_description
})
