package com.unishare.app.presentation.history

import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unishare.app.R
import com.unishare.app.data.history.HistoryQuery
import com.unishare.app.domain.*
import com.unishare.app.presentation.format.DisplayFormatters
import com.unishare.app.presentation.format.activityDisplayName
import com.unishare.app.platform.ContentUriThumbnail
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class HistorySection(val dayStart: Long, val rows: List<HistoryRow>)
private data class HistoryFilterOption<T>(val value: T, val label: String)

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onQuery: (HistoryQuery) -> Unit,
    onOpen: (ActivityKey) -> Unit,
    loadPreview: suspend (ActivityKey) -> ActivityPreview? = { null },
) {
    val query = state.query
    var deviceMenuExpanded by remember { mutableStateOf(false) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var filtersExpanded by remember { mutableStateOf(false) }
    val todayStart = remember { startOfDay(System.currentTimeMillis()) }
    val yesterdayStart = remember(todayStart) { startOfDayOffset(todayStart, -1) }
    val sections = remember(state.rows, todayStart) { groupByDay(state.rows) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                stringResource(R.string.screen_history_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        item {
            OutlinedTextField(
                value = query.search,
                onValueChange = { onQuery(query.copy(search = it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.history_search)) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                shape = RoundedCornerShape(26.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .58f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .58f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterAnchor(
                    text = query.device?.let { id ->
                        state.devices.firstOrNull { it.id == id }?.name?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.history_unknown_device, sanitizedDisplayName(id.value).take(12))
                    } ?: stringResource(R.string.history_all_devices),
                    icon = Icons.Outlined.Computer,
                    modifier = Modifier.weight(1.22f),
                    expanded = deviceMenuExpanded,
                    onClick = { deviceMenuExpanded = true },
                    onDismiss = { deviceMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history_all_devices)) },
                        onClick = {
                            onQuery(query.copy(device = null))
                            deviceMenuExpanded = false
                        },
                    )
                    state.devices.forEach { device ->
                        DropdownMenuItem(
                            text = { Text(device.name.ifBlank { stringResource(R.string.unknown_device) }) },
                            onClick = {
                                onQuery(query.copy(device = device.id))
                                deviceMenuExpanded = false
                            },
                        )
                    }
                }
                FilterAnchor(
                    text = query.kind?.let { kindLabel(it) } ?: stringResource(R.string.history_all_types),
                    icon = Icons.Outlined.Description,
                    modifier = Modifier.weight(1f),
                    expanded = typeMenuExpanded,
                    onClick = { typeMenuExpanded = true },
                    onDismiss = { typeMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history_all_types)) },
                        onClick = {
                            onQuery(query.copy(kind = null))
                            typeMenuExpanded = false
                        },
                    )
                    ContentKind.entries.forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kindLabel(kind)) },
                            onClick = {
                                onQuery(query.copy(kind = kind))
                                typeMenuExpanded = false
                            },
                        )
                    }
                }
                FilterAnchor(
                    text = stringResource(R.string.history_filters),
                    icon = Icons.Outlined.FilterList,
                    modifier = Modifier.weight(.94f),
                    expanded = false,
                    onClick = { filtersExpanded = true },
                    onDismiss = {},
                )
            }
        }

        if (sections.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        } else {
            sections.forEach { section ->
                item(key = "history-day-${section.dayStart}") {
                    Text(
                        when (section.dayStart) {
                            todayStart -> stringResource(R.string.history_today)
                            yesterdayStart -> stringResource(R.string.history_yesterday)
                            else -> formatSectionDate(section.dayStart)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
                items(section.rows, key = { it.activity.id.value }) { row ->
                    HistoryActivityCard(
                        row = row,
                        loadPreview = loadPreview,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }

    if (filtersExpanded) {
        HistoryFiltersSheet(
            query = query,
            onQuery = onQuery,
            onDismiss = { filtersExpanded = false },
        )
    }
}

@Composable
private fun FilterAnchor(
    text: String,
    icon: ImageVector,
    modifier: Modifier,
    expanded: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit = {},
) {
    Box(modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = text },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.heightIn(max = 380.dp),
            content = menuContent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFiltersSheet(
    query: HistoryQuery,
    onQuery: (HistoryQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    val directions = listOf(HistoryFilterOption<ShareDirection?>(null, directionLabel(null))) +
        ShareDirection.entries.map { HistoryFilterOption<ShareDirection?>(it, directionLabel(it)) }
    val statuses = listOf(HistoryFilterOption<ActivityStatus?>(null, statusLabel(null))) +
        ActivityStatus.entries.map { HistoryFilterOption<ActivityStatus?>(it, statusLabel(it)) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.history_filters), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            FilterChoiceRow(
                title = stringResource(R.string.history_direction),
                options = directions,
                selected = query.direction,
                onSelected = { onQuery(query.copy(direction = it)) },
            )
            FilterChoiceRow(
                title = stringResource(R.string.history_status),
                options = statuses,
                selected = query.status,
                onSelected = { onQuery(query.copy(status = it)) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onQuery(query.copy(direction = null, status = null)) }) {
                    Text(stringResource(R.string.history_reset))
                }
                Button(onClick = onDismiss) { Text(stringResource(R.string.history_done)) }
            }
        }
    }
}

@Composable
private fun <T> FilterChoiceRow(
    title: String,
    options: List<HistoryFilterOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option.value == selected,
                    onClick = { onSelected(option.value) },
                    label = { Text(option.label) },
                )
            }
        }
    }
}

@Composable
private fun HistoryActivityCard(
    row: HistoryRow,
    loadPreview: suspend (ActivityKey) -> ActivityPreview?,
    onOpen: (ActivityKey) -> Unit,
) {
    val activity = row.activity
    val context = LocalContext.current
    val storedPreview by produceState<ActivityPreview?>(initialValue = null, activity.id) {
        value = try {
            loadPreview(activity.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val textPreview = (storedPreview as? ActivityPreview.Text)?.text
    val imageBytes = (storedPreview as? ActivityPreview.Image)?.encodedBytes
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, activity.id, imageBytes) {
        value = imageBytes?.let { bytes ->
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
    }
    var menuExpanded by remember(activity.id) { mutableStateOf(false) }
    var detailsExpanded by remember(activity.id) { mutableStateOf(false) }
    val title = if (activity.kind == ContentKind.TEXT) {
        textPreview?.lineSequence()?.firstOrNull()?.trim()?.takeIf(String::isNotBlank)
            ?: activityDisplayName(activity.kind, activity.displayName)
    } else {
        activityDisplayName(activity.kind, activity.displayName)
    }
    val deviceName = row.deviceName.ifBlank { stringResource(R.string.unknown_device) }
    val metadata = stringResource(
        R.string.history_metadata,
        directionLabel(activity.direction),
        kindLabel(activity.kind),
        DisplayFormatters.bytes(context, activity.byteCount),
    )
    val time = DateFormat.getTimeFormat(context).format(Date(activity.updatedAtMillis))
    val canOpenImageUri = activity.kind == ContentKind.IMAGE && row.canOpen && activity.finalContentUri != null
    val clickableModifier = if (row.canOpen) {
        Modifier.clickable(role = Role.Button) { onOpen(activity.id) }
    } else Modifier
    val activityStatusDescription = statusLabel(activity.status)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .semantics { contentDescription = "$title, $deviceName, $metadata, $activityStatusDescription" },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 82.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HistoryThumbnail(
                row = row,
                bitmap = thumbnail,
                imageUri = activity.finalContentUri.takeIf { canOpenImageUri },
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        deviceName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (
                    activity.direction == ShareDirection.INCOMING &&
                    activity.kind != ContentKind.TEXT &&
                    activity.status == ActivityStatus.COMPLETED &&
                    !row.canOpen
                ) {
                    Text(
                        stringResource(R.string.history_content_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { onOpen(activity.id) },
                        enabled = false,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.receive_open))
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(
                modifier = Modifier.width(36.dp).height(58.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.history_details),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (row.canOpen) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_open)) },
                                onClick = {
                                    menuExpanded = false
                                    onOpen(activity.id)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_details)) },
                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                detailsExpanded = true
                            },
                        )
                    }
                }
                HistoryStatusBadge(activity.status)
            }
        }
    }

    if (detailsExpanded) {
        AlertDialog(
            onDismissRequest = { detailsExpanded = false },
            title = { Text(stringResource(R.string.history_details)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(deviceName)
                    Text(metadata)
                    Text(statusLabel(activity.status))
                    Text(DisplayFormatters.timestamp(context, activity.updatedAtMillis))
                }
            },
            confirmButton = {
                TextButton(onClick = { detailsExpanded = false }) { Text(stringResource(R.string.history_done)) }
            },
        )
    }
}

@Composable
private fun HistoryThumbnail(
    row: HistoryRow,
    bitmap: ImageBitmap?,
    imageUri: String?,
) {
    val kind = row.activity.kind
    val name = activityDisplayName(kind, row.activity.displayName)
    val previewDescription = stringResource(R.string.session_image_preview, name)
    val shape = RoundedCornerShape(16.dp)
    val modifier = Modifier.size(56.dp).clip(shape)

    when {
        kind == ContentKind.IMAGE && bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = previewDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        kind == ContentKind.IMAGE && imageUri != null -> ContentUriThumbnail(
            reference = imageUri,
            contentDescription = previewDescription,
            modifier = modifier,
            fallback = { ThumbnailFallback(kind, shape, row.activity.displayName) },
        )
        else -> ThumbnailFallback(kind, shape, row.activity.displayName)
    }
}

@Composable
private fun ThumbnailFallback(
    kind: ContentKind,
    shape: RoundedCornerShape,
    fileName: String? = null,
) {
    val isPdf = kind == ContentKind.FILE && fileName?.endsWith(".pdf", ignoreCase = true) == true
    val tint = if (isPdf) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(if (isPdf) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)),
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            ContentKind.TEXT -> Text(
                "Tt",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            ContentKind.IMAGE -> Icon(Icons.Outlined.ImageIcon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
            ContentKind.FILE -> Icon(Icons.Outlined.Description, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun HistoryStatusBadge(status: ActivityStatus) {
    val description = statusLabel(status)
    val completed = status == ActivityStatus.COMPLETED
    val failed = status in setOf(ActivityStatus.FAILED, ActivityStatus.REJECTED, ActivityStatus.CANCELLED)
    val tint = when {
        completed -> MaterialTheme.colorScheme.primary
        failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    val icon = when (status) {
        ActivityStatus.COMPLETED -> Icons.Outlined.Check
        ActivityStatus.AWAITING_PEER -> Icons.Outlined.Schedule
        ActivityStatus.TRANSFERRING -> Icons.Outlined.Sync
        ActivityStatus.REJECTED, ActivityStatus.CANCELLED, ActivityStatus.FAILED -> Icons.Outlined.Close
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (completed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .62f) else MaterialTheme.colorScheme.surfaceContainer)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
    }
}

private fun groupByDay(rows: List<HistoryRow>): List<HistorySection> {
    val groups = LinkedHashMap<Long, MutableList<HistoryRow>>()
    rows.forEach { row ->
        groups.getOrPut(startOfDay(row.activity.updatedAtMillis)) { mutableListOf() }.add(row)
    }
    return groups.map { (dayStart, items) -> HistorySection(dayStart, items) }
}

private fun startOfDay(timestamp: Long): Long = Calendar.getInstance().apply {
    timeInMillis = timestamp
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun startOfDayOffset(dayStart: Long, dayOffset: Int): Long = Calendar.getInstance().apply {
    timeInMillis = dayStart
    add(Calendar.DAY_OF_YEAR, dayOffset)
}.timeInMillis

@Composable
private fun formatSectionDate(dayStart: Long): String {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(Date(dayStart))
}

@Composable
private fun directionLabel(direction: ShareDirection?): String = stringResource(
    when (direction) {
        null -> R.string.history_all
        ShareDirection.INCOMING -> R.string.history_incoming
        ShareDirection.OUTGOING -> R.string.history_outgoing
    },
)

@Composable
private fun kindLabel(kind: ContentKind?): String = stringResource(
    when (kind) {
        null -> R.string.history_all
        ContentKind.TEXT -> R.string.session_text
        ContentKind.IMAGE -> R.string.session_photo
        ContentKind.FILE -> R.string.history_document
    },
)

@Composable
private fun statusLabel(status: ActivityStatus?): String = stringResource(
    when (status) {
        null -> R.string.history_all
        ActivityStatus.AWAITING_PEER -> R.string.session_status_waiting_description
        ActivityStatus.TRANSFERRING -> R.string.session_status_transferring_description
        ActivityStatus.COMPLETED -> R.string.session_status_completed_description
        ActivityStatus.REJECTED -> R.string.session_status_rejected_description
        ActivityStatus.CANCELLED -> R.string.session_status_cancelled_description
        ActivityStatus.FAILED -> R.string.session_status_failed_description
    },
)
