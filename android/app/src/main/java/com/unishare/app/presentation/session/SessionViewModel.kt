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
import com.unishare.app.presentation.pairing.PairingUiState
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

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
    private val awaitRuntime: suspend () -> Unit = {},
    private val pairingState: Flow<PairingUiState> = MutableStateFlow(PairingUiState()),
) : ViewModel() {
    private data class RateSample(
        val bytes: Long,
        val timestampMillis: Long,
        val bytesPerSecond: Long?,
    )
    private data class DeviceState(
        val devices: List<DeviceSummary>,
        val pairing: PairingUiState,
    )

    private val composer = MutableStateFlow("")
    private val composerMode = MutableStateFlow(ComposerMode.TEXT)
    private val shareSheetVisible = MutableStateFlow(false)
    private val eventChannel = Channel<SessionEvent>(Channel.UNLIMITED)
    private val rateSamples = HashMap<ActivityKey, RateSample>()
    private var lastDevice: DeviceSummary? = devices.find(deviceId)
    // Serializes connection/pairing and submission. Never held by the SDK event collector.
    private val submissions = Mutex()
    private class Blocked(val messageKind: SessionUserMessage) : Exception()
    private val deviceState = combine(devices.devices, pairingState) { allDevices, pairing ->
        DeviceState(allDevices, pairing)
    }

    val events = eventChannel.receiveAsFlow()

    val uiState: StateFlow<SessionUiState> = combine(
        deviceState,
        activities,
        composer,
        shareSheetVisible,
        composerMode,
    ) { deviceState, allActivities, composerText, sheetVisible, mode ->
        val allDevices = deviceState.devices
        val current = allDevices.firstOrNull { it.id == deviceId }
        if (current != null) lastDevice = current
        val visibleDevice = current ?: lastDevice
        SessionUiState(
            deviceId = deviceId,
            deviceName = visibleDevice?.name.orEmpty(),
            platform = visibleDevice?.platform,
            online = current?.online == true,
            connected = current?.connected == true,
            trusted = current?.trusted == true,
            identityChanged = current?.identityChanged == true,
            pairing = deviceId in deviceState.pairing.matchingDeviceIds,
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
            connected = lastDevice?.connected == true,
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
        viewModelScope.launch {
            try {
                submissions.withLock {
                    ensureConnection()
                    operations.sendLink(deviceId, normalized)
                    accepted()
                }
            } catch (failure: Exception) {
                reportSendFailure(failure)
            }
        }
    }

    fun sendPickedContent(selection: RetainedContentUri?, kind: ContentKind) {
        if (selection == null) return
        shareSheetVisible.value = false
        val viewModelOwnsLease = AtomicBoolean(true)
        val submission = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                submissions.withLock {
                    ensureConnection(waitForDiscovery = true)
                    if (!viewModelOwnsLease.compareAndSet(true, false)) return@withLock
                    operations.sendPickedContent(deviceId, selection, kind)
                }
            } catch (failure: Exception) {
                reportSendFailure(failure)
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
        shareSheetVisible.value = false
        viewModelScope.launch {
            try {
                submissions.withLock {
                    ensureConnection()
                    if (operations.sendClipboard(deviceId, isForeground) == null) emit(SessionUserMessage.CLIPBOARD_EMPTY)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
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
                submissions.withLock {
                    if (uiState.value.activities.any { it.id == activityId && it.direction == com.unishare.app.domain.ShareDirection.OUTGOING }) {
                        ensureConnection(waitForDiscovery = true)
                    }
                    operations.retry(activityId)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                emit(SessionUserMessage.RETRY_FAILED)
            }
        }
    }

    fun forgetDevice(onForgotten: () -> Unit) {
        viewModelScope.launch {
            try {
                devices.forget(deviceId)
                onForgotten()
            } catch (_: Exception) {
                emit(SessionUserMessage.FORGET_FAILED)
            }
        }
    }

    private fun sendText(text: String, accepted: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                submissions.withLock {
                    ensureConnection()
                    operations.sendText(deviceId, text)
                    accepted()
                }
            } catch (failure: Exception) {
                reportSendFailure(failure)
            }
        }
    }

    fun enterSession() {
        viewModelScope.launch {
            try { submissions.withLock { ensureConnection(waitForDiscovery = true) } }
            catch (failure: Exception) { reportSendFailure(failure) }
        }
    }

    private suspend fun ensureConnection(waitForDiscovery: Boolean = false) {
        awaitRuntime()
        if (waitForDiscovery) withTimeout(30_000) {
            devices.devices.first { peers -> peers.any { it.id == deviceId && (it.online || it.connected || it.identityChanged) } }
        }
        val device = devices.find(deviceId) ?: throw Blocked(SessionUserMessage.DEVICE_OFFLINE)
        if (device.identityChanged) throw Blocked(SessionUserMessage.DEVICE_NOT_TRUSTED)
        if (!device.online && !device.connected) throw Blocked(SessionUserMessage.DEVICE_OFFLINE)
        // Repository validates the authenticated result against the current SDK lifecycle token.
        if (!device.connected) devices.connect(deviceId)
        if (!devices.isTrusted(deviceId)) throw Blocked(SessionUserMessage.DEVICE_NOT_TRUSTED)
    }

    private fun reportSendFailure(failure: Exception) {
        if (failure is TimeoutCancellationException) { emit(SessionUserMessage.DEVICE_OFFLINE); return }
        if (failure is CancellationException) throw failure
        emit((failure as? Blocked)?.messageKind ?: SessionUserMessage.SEND_FAILED)
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
                awaitRuntime = graph.runtimeController::awaitForegroundReady,
                pairingState = graph.pairingCoordinator.uiState,
            ) as T
        }
    }
}
