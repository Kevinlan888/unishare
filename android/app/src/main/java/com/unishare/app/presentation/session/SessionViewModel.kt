package com.unishare.app.presentation.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unishare.app.AppGraph
import com.unishare.app.data.device.DeviceRepository
import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.data.transfer.SelectedContentResolver
import com.unishare.app.data.transfer.CoordinatorException
import com.unishare.app.data.transfer.TransferCoordinator
import com.unishare.app.data.transfer.RetainedContentUri
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ActivityPreview
import com.unishare.app.domain.ActivityStatus
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareActivity
import com.unishare.app.domain.ShareErrorKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart

interface SessionOperations {
    suspend fun sendText(deviceId: DeviceKey, text: String): ActivityKey
    suspend fun sendLink(deviceId: DeviceKey, link: String): ActivityKey
    /** Takes ownership of selection and its read-permission lease. */
    suspend fun sendPickedContent(deviceId: DeviceKey, selection: RetainedContentUri, kind: ContentKind): ActivityKey
    suspend fun sendClipboard(deviceId: DeviceKey, isForeground: () -> Boolean): ActivityKey?
    suspend fun cancel(activityId: ActivityKey): Boolean
    suspend fun retry(activityId: ActivityKey): ActivityKey
}

private class CoordinatorSessionOperations(
    private val coordinator: TransferCoordinator,
    private val resolver: SelectedContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionOperations {
    override suspend fun sendText(deviceId: DeviceKey, text: String) = coordinator.sendText(deviceId, text)
    override suspend fun sendLink(deviceId: DeviceKey, link: String) = coordinator.sendText(deviceId, link)

    override suspend fun sendPickedContent(deviceId: DeviceKey, selection: RetainedContentUri, kind: ContentKind): ActivityKey {
        val selected = try {
            withContext(ioDispatcher) { resolver.resolve(selection.uri, kind) }
                .copy(readPermission = selection.lease)
        } catch (failure: Exception) {
            selection.lease.close()
            throw failure
        }
        return coordinator.sendContent(deviceId, selected)
    }

    override suspend fun sendClipboard(deviceId: DeviceKey, isForeground: () -> Boolean) =
        coordinator.sendClipboard(deviceId, isForeground)
    override suspend fun cancel(activityId: ActivityKey) = coordinator.cancel(activityId)
    override suspend fun retry(activityId: ActivityKey) = coordinator.retry(activityId)
}

class SessionViewModel(
    private val deviceId: DeviceKey,
    private val devices: DeviceRepository,
    activities: Flow<List<ShareActivity>>,
    private val previewFor: (ActivityKey) -> ActivityPreview?,
    private val operations: SessionOperations,
) : ViewModel() {
    private data class RateSample(
        val bytes: Long,
        val timestampMillis: Long,
        val bytesPerSecond: Long?,
    )

    private val composer = MutableStateFlow("")
    private val composerMode = MutableStateFlow(ComposerMode.TEXT)
    private val shareSheetVisible = MutableStateFlow(false)
    private val eventChannel = Channel<SessionEvent>(Channel.UNLIMITED)
    private val rateSamples = HashMap<ActivityKey, RateSample>()
    private var lastDevice: DeviceSummary? = devices.find(deviceId)

    val events = eventChannel.receiveAsFlow()

    val uiState: StateFlow<SessionUiState> = combine(
        devices.devices,
        activities,
        composer,
        shareSheetVisible,
        composerMode,
    ) { allDevices, allActivities, composerText, sheetVisible, mode ->
        val current = allDevices.firstOrNull { it.id == deviceId }
        if (current != null) lastDevice = current
        val visibleDevice = current ?: lastDevice
        SessionUiState(
            deviceId = deviceId,
            deviceName = visibleDevice?.name.orEmpty(),
            platform = visibleDevice?.platform,
            online = current?.online == true,
            trusted = current?.trusted == true,
            identityChanged = current?.identityChanged == true,
            composerText = composerText,
            composerMode = mode,
            activities = allActivities.map(::toUiActivity),
            shareSheetVisible = sheetVisible,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SessionUiState(
            deviceId = deviceId,
            deviceName = lastDevice?.name.orEmpty(),
            platform = lastDevice?.platform,
            online = lastDevice?.online == true,
            trusted = lastDevice?.trusted == true,
            identityChanged = lastDevice?.identityChanged == true,
        ),
    )

    fun onComposerChanged(value: String) {
        composer.value = value
    }

    fun showShareSheet() {
        shareSheetVisible.value = true
    }

    fun dismissShareSheet() {
        shareSheetVisible.value = false
    }

    fun selectTextComposer() {
        composerMode.value = ComposerMode.TEXT
        shareSheetVisible.value = false
    }

    fun selectLinkComposer() {
        composerMode.value = ComposerMode.LINK
        shareSheetVisible.value = false
    }

    fun sendComposer() {
        val submitted = composer.value
        val accepted = {
            if (composer.value == submitted) composer.value = ""
            composerMode.value = ComposerMode.TEXT
        }
        when (composerMode.value) {
            ComposerMode.TEXT -> {
                if (submitted.isBlank()) {
                    emit(SessionUserMessage.TEXT_REQUIRED)
                    return
                }
                sendText(submitted, accepted)
            }
            ComposerMode.LINK -> sendLink(submitted, accepted)
        }
    }

    private fun sendLink(link: String, accepted: () -> Unit) {
        val normalized = link.trim()
        if (!isHttpLink(normalized)) {
            emit(SessionUserMessage.INVALID_LINK)
            return
        }
        val blocked = sendBlock()
        if (blocked != null) {
            emit(blocked)
            return
        }
        viewModelScope.launch {
            try {
                operations.sendLink(deviceId, normalized)
                accepted()
            } catch (_: Exception) {
                emit(SessionUserMessage.SEND_FAILED)
            }
        }
    }

    fun sendPickedContent(selection: RetainedContentUri?, kind: ContentKind) {
        if (selection == null) return
        val blocked = sendBlock()
        if (blocked != null) {
            selection.lease.close()
            emit(blocked)
            return
        }
        shareSheetVisible.value = false
        val viewModelOwnsLease = AtomicBoolean(true)
        val submission = viewModelScope.launch(start = CoroutineStart.LAZY) {
            if (!viewModelOwnsLease.compareAndSet(true, false)) return@launch
            try {
                operations.sendPickedContent(deviceId, selection, kind)
            } catch (_: Exception) {
                emit(SessionUserMessage.SEND_FAILED)
            }
        }
        submission.invokeOnCompletion {
            if (viewModelOwnsLease.compareAndSet(true, false)) selection.lease.close()
        }
        submission.start()
    }

    fun onContentPermissionFailure(errorKind: ShareErrorKind) {
        emit(
            if (errorKind == ShareErrorKind.PERMISSION_DENIED) SessionUserMessage.CONTENT_PERMISSION_DENIED
            else SessionUserMessage.SEND_FAILED,
        )
    }

    fun sendClipboard(isForeground: () -> Boolean) {
        val blocked = sendBlock()
        if (blocked != null) {
            emit(blocked)
            return
        }
        shareSheetVisible.value = false
        viewModelScope.launch {
            try {
                if (operations.sendClipboard(deviceId, isForeground) == null) emit(SessionUserMessage.CLIPBOARD_EMPTY)
            } catch (failure: Exception) {
                emit(
                    if ((failure as? CoordinatorException)?.errorKind == ShareErrorKind.PERMISSION_DENIED) {
                        SessionUserMessage.CLIPBOARD_REQUIRES_FOREGROUND
                    } else {
                        SessionUserMessage.SEND_FAILED
                    },
                )
            }
        }
    }

    fun cancel(activityId: ActivityKey) {
        viewModelScope.launch {
            try {
                if (!operations.cancel(activityId)) emit(SessionUserMessage.CANCEL_FAILED)
            } catch (_: Exception) {
                emit(SessionUserMessage.CANCEL_FAILED)
            }
        }
    }

    fun retry(activityId: ActivityKey) {
        viewModelScope.launch {
            try {
                operations.retry(activityId)
            } catch (_: Exception) {
                emit(SessionUserMessage.RETRY_FAILED)
            }
        }
    }

    private fun sendText(text: String, accepted: () -> Unit = {}) {
        val blocked = sendBlock()
        if (blocked != null) {
            emit(blocked)
            return
        }
        viewModelScope.launch {
            try {
                operations.sendText(deviceId, text)
                accepted()
            } catch (_: Exception) {
                emit(SessionUserMessage.SEND_FAILED)
            }
        }
    }

    private fun sendBlock(): SessionUserMessage? {
        val device = devices.find(deviceId) ?: return SessionUserMessage.DEVICE_OFFLINE
        if (!device.online) return SessionUserMessage.DEVICE_OFFLINE
        if (!device.trusted || device.identityChanged) return SessionUserMessage.DEVICE_NOT_TRUSTED
        return null
    }

    private fun emit(message: SessionUserMessage) {
        eventChannel.trySend(SessionEvent(message))
    }

    private fun isHttpLink(value: String): Boolean = try {
        val parsed = URI(value)
        parsed.scheme?.lowercase() in setOf("http", "https") && !parsed.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    private fun toUiActivity(activity: ShareActivity): SessionActivityUi {
        val previous = rateSamples[activity.id]
        val computedRate = if (
            activity.status == ActivityStatus.TRANSFERRING &&
            previous != null &&
            activity.transferredBytes > previous.bytes &&
            activity.updatedAtMillis > previous.timestampMillis
        ) {
            ((activity.transferredBytes - previous.bytes) * 1_000L /
                (activity.updatedAtMillis - previous.timestampMillis)).coerceAtLeast(0L)
        } else if (activity.status == ActivityStatus.TRANSFERRING) {
            previous?.bytesPerSecond
        } else null
        if (activity.status == ActivityStatus.TRANSFERRING) {
            rateSamples[activity.id] = RateSample(activity.transferredBytes, activity.updatedAtMillis, computedRate)
        } else {
            rateSamples.remove(activity.id)
        }
        val eta = computedRate?.takeIf { it > 0L }?.let { rate ->
            ((activity.byteCount - activity.transferredBytes).coerceAtLeast(0L) + rate - 1L) / rate
        }
        val preview = previewFor(activity.id)
        return SessionActivityUi(
            id = activity.id,
            direction = activity.direction,
            kind = activity.kind,
            origin = activity.origin,
            displayName = activity.displayName,
            mimeType = activity.mimeType,
            byteCount = activity.byteCount,
            transferredBytes = activity.transferredBytes,
            status = activity.status,
            timestampMillis = activity.updatedAtMillis,
            text = (preview as? ActivityPreview.Text)?.text,
            previewReference = (preview as? ActivityPreview.Image)?.reference ?: activity.finalContentUri,
            rateBytesPerSecond = computedRate,
            etaSeconds = eta,
            errorKind = activity.errorKind,
        )
    }

    class Factory(
        private val graph: AppGraph,
        private val deviceId: DeviceKey,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SessionViewModel::class.java))
            return SessionViewModel(
                deviceId = deviceId,
                devices = graph.deviceRepository,
                activities = graph.activityTracker.activities(deviceId),
                previewFor = graph.activityTracker::preview,
                operations = CoordinatorSessionOperations(
                    graph.transferCoordinator,
                    graph.selectedContentResolver,
                ),
            ) as T
        }
    }
}
