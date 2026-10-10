package com.unishare.app.presentation.session

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.unishare.app.R
import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.data.transfer.ContentUriPermissionRetainer
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ContentKind
import com.unishare.app.platform.rememberContentPickerLauncher
import com.unishare.app.platform.ContentUriThumbnail
import com.unishare.app.presentation.components.DeviceIcon
import com.unishare.app.presentation.components.StatusIndicator
import com.unishare.app.presentation.components.StatusTone
import com.unishare.app.presentation.components.DevicePresence
import com.unishare.app.presentation.components.devicePresence
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.text.DateFormat
import java.util.Date

@Composable
fun SessionRoute(
    viewModel: SessionViewModel,
    contentUriPermissionRetainer: ContentUriPermissionRetainer,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context by rememberUpdatedState(LocalContext.current)
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberContentPickerLauncher(
        retainer = contentUriPermissionRetainer,
        onPhotoSelected = { viewModel.sendPickedContent(it, ContentKind.IMAGE) },
        onFileSelected = { viewModel.sendPickedContent(it, ContentKind.FILE) },
        onPermissionFailure = viewModel::onContentPermissionFailure,
    )
    LaunchedEffect(viewModel) {
        viewModel.enterSession()
        viewModel.events.collect { event ->
            snackbar.showSnackbar(context.getString(event.message.stringResource()))
        }
    }
    SessionScreen(
        state = state,
        onBack = onBack,
        onForgetDevice = { viewModel.forgetDevice(onBack) },
        onComposerChanged = viewModel::onComposerChanged,
        onSend = viewModel::sendComposer,
        onAddAttachment = viewModel::showShareSheet,
        onDismissShareSheet = viewModel::dismissShareSheet,
        onPickPhoto = picker.launchPhoto,
        onPickFile = picker.launchFile,
        onClipboard = {
            viewModel.sendClipboard {
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        },
        onText = viewModel::selectTextComposer,
        onLink = viewModel::selectLinkComposer,
        onCancel = viewModel::cancel,
        onRetry = viewModel::retry,
        snackbarHostState = snackbar,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    state: SessionUiState,
    onBack: () -> Unit,
    onForgetDevice: () -> Unit,
    onComposerChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAddAttachment: () -> Unit,
    onDismissShareSheet: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onClipboard: () -> Unit,
    onText: () -> Unit,
    onLink: () -> Unit,
    onCancel: (ActivityKey) -> Unit,
    onRetry: (ActivityKey) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val listState = rememberLazyListState()
    val locale = LocalConfiguration.current.locales[0]
    val orderedActivities = state.activities.sortedBy { it.timestampMillis }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val composerFocusRequester = remember { FocusRequester() }
    var userWasNearEnd by remember { mutableStateOf(true) }
    var showDeviceInformation by remember { mutableStateOf(false) }
    var showForgetConfirmation by remember { mutableStateOf(false) }
    var previewedImage by remember { mutableStateOf<SessionActivityUi?>(null) }
    val latestActivities by rememberUpdatedState(state.activities)
    LaunchedEffect(listState) {
        snapshotFlow {
            if (!listState.isScrollInProgress) null
            else listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { last ->
                last >= listState.layoutInfo.totalItemsCount - 2
            }
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd != null) userWasNearEnd = nearEnd
        }
    }
    LaunchedEffect(state.activities.size) {
        if (userWasNearEnd && latestActivities.isNotEmpty()) {
            listState.animateScrollToItem(latestActivities.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SessionHeader(
                state = state,
                onBack = onBack,
                onShowDeviceInformation = { showDeviceInformation = true },
                onRequestForget = { showForgetConfirmation = true },
            )
        },
        bottomBar = {
            SessionComposer(
                value = state.composerText,
                mode = state.composerMode,
                onValueChange = onComposerChanged,
                onAdd = onAddAttachment,
                onSend = onSend,
                focusRequester = composerFocusRequester,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                count = orderedActivities.size,
                key = { orderedActivities[it].id.value },
            ) { index ->
                val activity = orderedActivities[index]
                val date = Instant.ofEpochMilli(activity.timestampMillis).atZone(zone).toLocalDate()
                val previousDate = orderedActivities.getOrNull(index - 1)?.let {
                    Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate()
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (date != previousDate) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Text(
                            if (date == today) stringResource(R.string.session_today) else
                                DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                                    .format(Date(activity.timestampMillis)),
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
                ActivityCard(
                    activity = activity,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    onOpenImage = { previewedImage = it },
                )
                }
            }
        }
    }

    if (state.shareSheetVisible) {
        ShareSheet(
            deviceName = state.deviceName,
            onDismiss = onDismissShareSheet,
            onPhoto = onPickPhoto,
            onFile = onPickFile,
            onClipboard = onClipboard,
            onText = {
                onText()
                composerFocusRequester.requestFocus()
            },
            onLink = {
                onLink()
                composerFocusRequester.requestFocus()
            },
        )
    }
    if (showDeviceInformation) {
        DeviceInformationDialog(state = state, onDismiss = { showDeviceInformation = false })
    }
    if (showForgetConfirmation) {
        AlertDialog(
            onDismissRequest = { showForgetConfirmation = false },
            title = { Text(stringResource(R.string.devices_forget_title, state.deviceName)) },
            text = { Text(stringResource(R.string.devices_forget_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    showForgetConfirmation = false
                    onForgetDevice()
                }) { Text(stringResource(R.string.devices_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { showForgetConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    previewedImage?.let { activity ->
        ImagePreviewDialog(activity = activity, onDismiss = { previewedImage = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionHeader(
    state: SessionUiState,
    onBack: () -> Unit,
    onShowDeviceInformation: () -> Unit,
    onRequestForget: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        modifier = Modifier.testTag("session_header"),
        windowInsets = WindowInsets(0.dp),
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceIcon(
                    platform = state.platform,
                    contentDescription = stringResource(R.string.device_icon_unknown),
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = state.deviceName.ifBlank { stringResource(R.string.unknown_device) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val presence = devicePresence(state.online, state.connected)
                    StatusIndicator(
                        label = when {
                            state.identityChanged -> stringResource(R.string.status_identity_changed)
                            state.pairing -> stringResource(R.string.status_pairing)
                            presence == DevicePresence.CONNECTED -> stringResource(R.string.status_connected)
                            presence == DevicePresence.ONLINE -> stringResource(R.string.status_online)
                            else -> stringResource(R.string.status_offline)
                        },
                        tone = when {
                            state.identityChanged -> StatusTone.ERROR
                            state.pairing -> StatusTone.NEUTRAL
                            presence != DevicePresence.OFFLINE -> StatusTone.ONLINE
                            else -> StatusTone.NEUTRAL
                        },
                    )
                }
            }
        },
        actions = {
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_device_information)) },
                        leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShowDeviceInformation()
                        },
                    )
                    if (state.trusted || state.identityChanged) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.session_forget_device)) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRequestForget()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun DeviceInformationDialog(state: SessionUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_device_information)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    state.deviceName.ifBlank { stringResource(R.string.unknown_device) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                DeviceInformationRow(
                    label = stringResource(R.string.session_device_platform),
                    value = stringResource(
                        when (state.platform) {
                            DevicePlatform.WINDOWS -> R.string.device_icon_windows
                            DevicePlatform.ANDROID -> R.string.device_icon_android
                            DevicePlatform.IOS -> R.string.device_icon_ios
                            null -> R.string.device_icon_unknown
                        },
                    ),
                )
                DeviceInformationRow(
                    label = stringResource(R.string.session_device_connection),
                    value = stringResource(
                        when {
                            state.connected -> R.string.status_connected
                            state.online -> R.string.status_online
                            else -> R.string.status_offline
                        },
                    ),
                )
                DeviceInformationRow(
                    label = stringResource(R.string.session_device_trust),
                    value = stringResource(
                        when {
                            state.identityChanged -> R.string.status_identity_changed
                            state.trusted -> R.string.status_trusted
                            else -> R.string.status_unpaired
                        },
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun DeviceInformationRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ImagePreviewDialog(activity: SessionActivityUi, onDismiss: () -> Unit) {
    val description = stringResource(R.string.session_image_preview, activity.displayName)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                val previewModifier = Modifier.fillMaxSize()
                when {
                    activity.previewBitmap != null -> Image(
                        bitmap = activity.previewBitmap,
                        contentDescription = description,
                        contentScale = ContentScale.Fit,
                        modifier = previewModifier,
                    )
                    activity.previewReference != null -> ContentUriThumbnail(
                        reference = activity.previewReference,
                        contentDescription = description,
                        modifier = previewModifier,
                        contentScale = ContentScale.Fit,
                    ) {
                        Text(
                            stringResource(R.string.session_image_unavailable),
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.White,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        activity.displayName,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.session_close_image_preview),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionComposer(
    value: String,
    mode: ComposerMode,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    onSend: () -> Unit,
    focusRequester: FocusRequester,
) {
    var editorValue by remember {
        mutableStateOf(TextFieldValue(value, selection = TextRange(value.length)))
    }
    var emojiPickerExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (value != editorValue.text) {
            editorValue = TextFieldValue(value, selection = TextRange(value.length))
        }
    }
    fun insertEmoji(emoji: String) {
        val start = minOf(editorValue.selection.start, editorValue.selection.end)
        val end = maxOf(editorValue.selection.start, editorValue.selection.end)
        val updated = editorValue.text.replaceRange(start, end, emoji)
        editorValue = TextFieldValue(updated, selection = TextRange(start + emoji.length))
        onValueChange(updated)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Surface(
                onClick = onAdd,
                modifier = Modifier.size(52.dp).semantics { role = Role.Button },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.session_add_attachment))
                }
            }
            TextField(
                value = editorValue,
                onValueChange = {
                    editorValue = it
                    onValueChange(it.text)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .heightIn(min = 52.dp),
                placeholder = {
                    Text(
                        stringResource(
                            if (mode == ComposerMode.LINK) R.string.session_link_composer_hint
                            else R.string.session_composer_hint,
                        ),
                        maxLines = 2,
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { emojiPickerExpanded = true }) {
                        Icon(
                            Icons.Outlined.SentimentSatisfiedAlt,
                            contentDescription = stringResource(R.string.session_emoji),
                        )
                    }
                },
                maxLines = 4,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
            Surface(
                onClick = onSend,
                modifier = Modifier.size(56.dp).semantics { role = Role.Button },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = stringResource(
                            if (mode == ComposerMode.LINK) R.string.session_send_link
                            else R.string.session_send_text,
                        ),
                        modifier = Modifier.size(27.dp),
                    )
                }
            }
        }
    }
    if (emojiPickerExpanded) {
        ModalBottomSheet(
            onDismissRequest = {
                emojiPickerExpanded = false
                focusRequester.requestFocus()
            },
        ) {
            AndroidView(
                factory = { context -> EmojiPickerView(context) },
                update = { picker ->
                    picker.setOnEmojiPickedListener { item -> insertEmoji(item.emoji) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp, max = 520.dp),
            )
        }
    }
}

private fun SessionUserMessage.stringResource(): Int = when (this) {
    SessionUserMessage.DEVICE_OFFLINE -> R.string.session_error_offline
    SessionUserMessage.DEVICE_NOT_TRUSTED -> R.string.session_error_untrusted
    SessionUserMessage.TEXT_REQUIRED -> R.string.session_error_text_required
    SessionUserMessage.SEND_FAILED -> R.string.session_error_send_failed
    SessionUserMessage.CANCEL_FAILED -> R.string.session_error_cancel_failed
    SessionUserMessage.RETRY_FAILED -> R.string.session_error_retry_failed
    SessionUserMessage.CLIPBOARD_EMPTY -> R.string.session_error_clipboard_empty
    SessionUserMessage.CLIPBOARD_REQUIRES_FOREGROUND -> R.string.session_error_clipboard_foreground
    SessionUserMessage.CONTENT_PERMISSION_DENIED -> R.string.session_error_content_permission
    SessionUserMessage.INVALID_LINK -> R.string.session_error_invalid_link
    SessionUserMessage.FORGET_FAILED -> R.string.session_error_forget_failed
}
