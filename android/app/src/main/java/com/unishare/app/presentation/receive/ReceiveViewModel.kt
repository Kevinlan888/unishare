package com.unishare.app.presentation.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unishare.app.AppGraph
import com.unishare.app.data.device.DeviceRepository
import com.unishare.app.data.sdk.IncomingOffer
import com.unishare.app.data.settings.SettingsRepository
import com.unishare.app.data.transfer.TransferCoordinator
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ActivityStatus
import com.unishare.app.domain.ActivityTracker
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareActivity
import com.unishare.app.domain.ShareErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ReceiveOperations {
    fun pendingOffer(activityId: ActivityKey): IncomingOffer?
    fun hasPublicationRecovery(activityId: ActivityKey): Boolean
    fun observeActivities(deviceId: DeviceKey): Flow<List<ShareActivity>>
    fun findActivity(activityId: ActivityKey): ShareActivity?
    suspend fun accept(activityId: ActivityKey): Boolean
    suspend fun reject(activityId: ActivityKey): Boolean
    suspend fun retry(activityId: ActivityKey): ActivityKey
}

fun interface FinalContentReader {
    fun canRead(uri: String): Boolean
}

private class AppReceiveOperations(
    private val coordinator: TransferCoordinator,
    private val tracker: ActivityTracker,
) : ReceiveOperations {
    override fun pendingOffer(activityId: ActivityKey) = coordinator.pendingOffer(activityId)
    override fun hasPublicationRecovery(activityId: ActivityKey) = coordinator.hasPublicationRecovery(activityId)
    override fun observeActivities(deviceId: DeviceKey): Flow<List<ShareActivity>> = tracker.activities(deviceId)
    override fun findActivity(activityId: ActivityKey) = tracker.find(activityId)
    override suspend fun accept(activityId: ActivityKey) = coordinator.accept(activityId)
    override suspend fun reject(activityId: ActivityKey) = coordinator.reject(activityId)
    override suspend fun retry(activityId: ActivityKey) = coordinator.retry(activityId)
}

class ReceiveViewModel internal constructor(
    private val originalActivityId: ActivityKey,
    private val operations: ReceiveOperations,
    private val devices: DeviceRepository,
    private val settings: SettingsRepository,
    private val finalContentReader: FinalContentReader,
    providedScope: CoroutineScope? = null,
) : ViewModel() {
    private val workerScope = providedScope ?: viewModelScope
    private val gate = Any()
    private val destinationGate = Mutex()
    private var decisionClaimed = false
    private var observedActivityId = originalActivityId
    private var downloadsDestinationConfirmed = false
    private val offer = operations.pendingOffer(originalActivityId)

    private val mutableUiState = MutableStateFlow(
        offer?.toInitialState() ?: ReceiveUiState(activityId = originalActivityId),
    )
    val uiState: StateFlow<ReceiveUiState> = mutableUiState.asStateFlow()

    init {
        offer?.let { incoming ->
            workerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                combine(
                    devices.devices,
                    settings.settings,
                    operations.observeActivities(incoming.deviceId),
                ) { deviceList, appSettings, activities ->
                    Triple(deviceList, appSettings, activities)
                }.collect { (deviceList, appSettings, activities) ->
                    val device = deviceList.firstOrNull { it.id == incoming.deviceId }
                    val activity = activities.firstOrNull { it.id == synchronized(gate) { observedActivityId } }
                    val base = mutableUiState.value.copy(
                        source = ReceiveSourceUi(
                            id = incoming.deviceId,
                            name = device?.name ?: incoming.deviceId.value,
                            platform = device?.platform,
                            online = device?.online == true,
                        ),
                        destinationDisplayName = appSettings.receiveDirectoryDisplayName,
                    )
                    mutableUiState.value = activity?.let { base.withActivity(it) } ?: base
                }
            }
        }
    }

    fun accept() {
        if (!claimDecision(ReceivePhase.RECEIVING)) return
        workerScope.launch {
            val accepted = try { operations.accept(originalActivityId) } catch (_: Exception) { false }
            if (!accepted) mutableUiState.value = mutableUiState.value.copy(phase = ReceivePhase.EXPIRED)
        }
    }

    fun reject() {
        if (!claimDecision(ReceivePhase.RECEIVING)) return
        workerScope.launch {
            try {
                val rejected = operations.reject(originalActivityId)
                mutableUiState.value = mutableUiState.value.copy(
                    phase = if (rejected) ReceivePhase.REJECTED else ReceivePhase.EXPIRED,
                )
            } catch (_: Exception) {
                val stillPending = operations.pendingOffer(originalActivityId) != null
                synchronized(gate) { decisionClaimed = !stillPending }
                mutableUiState.value = mutableUiState.value.copy(
                    phase = if (stillPending) ReceivePhase.PENDING else ReceivePhase.EXPIRED,
                )
            }
        }
    }

    fun retryPublication() {
        synchronized(gate) {
            if (mutableUiState.value.phase != ReceivePhase.DIRECTORY_ERROR || decisionClaimed) return
            decisionClaimed = true
            mutableUiState.value = mutableUiState.value.copy(phase = ReceivePhase.RECEIVING)
        }
        workerScope.launch {
            if (!ensureDownloadsDestination()) {
                synchronized(gate) { decisionClaimed = false }
                mutableUiState.value = mutableUiState.value.copy(phase = ReceivePhase.DIRECTORY_ERROR)
                return@launch
            }
            val retrySourceId = synchronized(gate) { observedActivityId }
            try {
                val retryId = operations.retry(retrySourceId)
                synchronized(gate) { observedActivityId = retryId }
                operations.findActivity(retryId)?.let { activity ->
                    mutableUiState.value = mutableUiState.value.withActivity(activity)
                }
            } catch (_: Exception) {
                val recoverable = operations.hasPublicationRecovery(retrySourceId)
                synchronized(gate) { decisionClaimed = !recoverable }
                mutableUiState.value = mutableUiState.value.copy(
                    phase = if (recoverable) ReceivePhase.DIRECTORY_ERROR else ReceivePhase.FAILED,
                )
            }
        }
    }

    /** Generic SAF publication is intentionally unsupported; correction returns to safe Downloads. */
    fun restoreDownloadsDestination() {
        mutableUiState.value = mutableUiState.value.copy(showUnsupportedDestinationNotice = true)
        workerScope.launch {
            ensureDownloadsDestination()
        }
    }

    private suspend fun ensureDownloadsDestination(): Boolean = destinationGate.withLock {
        if (downloadsDestinationConfirmed) return@withLock true
        try {
            settings.setReceiveDirectory(treeUri = null, displayName = null)
            downloadsDestinationConfirmed = true
            true
        } catch (_: Exception) {
            // Never retry publication against a destination we could not safely reset.
            false
        }
    }

    fun openFinalContent(): String? {
        val state = mutableUiState.value
        return state.finalContentUri?.takeIf { state.phase == ReceivePhase.COMPLETED && state.canOpen }
    }

    private fun claimDecision(next: ReceivePhase): Boolean = synchronized(gate) {
        if (decisionClaimed || mutableUiState.value.phase != ReceivePhase.PENDING) return@synchronized false
        decisionClaimed = true
        mutableUiState.value = mutableUiState.value.copy(phase = next)
        true
    }

    private fun IncomingOffer.toInitialState() = ReceiveUiState(
        activityId = originalActivityId,
        kind = kind,
        displayName = displayName,
        mimeType = mimeType,
        byteCount = byteCount,
        source = ReceiveSourceUi(id = deviceId, name = deviceId.value),
        phase = ReceivePhase.PENDING,
    )

    private fun ReceiveUiState.withActivity(activity: ShareActivity): ReceiveUiState {
        val nextPhase = when (activity.status) {
            ActivityStatus.AWAITING_PEER -> phase
            ActivityStatus.TRANSFERRING -> ReceivePhase.RECEIVING
            ActivityStatus.COMPLETED -> ReceivePhase.COMPLETED
            ActivityStatus.REJECTED, ActivityStatus.CANCELLED -> ReceivePhase.REJECTED
            ActivityStatus.FAILED -> if (
                activity.errorKind == ShareErrorKind.STORAGE_FAILED &&
                operations.hasPublicationRecovery(activity.id)
            ) {
                ReceivePhase.DIRECTORY_ERROR
            } else {
                ReceivePhase.FAILED
            }
        }
        val finalUri = activity.finalContentUri.takeIf { nextPhase == ReceivePhase.COMPLETED }
        val readable = finalUri != null && !finalUri.isPrivateStagingUri() &&
            try { finalContentReader.canRead(finalUri) } catch (_: Exception) { false }
        if (nextPhase == ReceivePhase.DIRECTORY_ERROR) synchronized(gate) { decisionClaimed = false }
        return copy(
            phase = nextPhase,
            finalContentUri = finalUri,
            canOpen = readable,
        )
    }

    class Factory(
        graph: AppGraph,
        private val activityId: ActivityKey,
        private val finalContentReader: FinalContentReader,
    ) : ViewModelProvider.Factory {
        private val operations = AppReceiveOperations(graph.transferCoordinator, graph.activityTracker)
        private val devices = graph.deviceRepository
        private val settings = graph.settingsRepository

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReceiveViewModel(
            originalActivityId = activityId,
            operations = operations,
            devices = devices,
            settings = settings,
            finalContentReader = finalContentReader,
        ) as T
    }
}

private fun String.isPrivateStagingUri(): Boolean =
    startsWith("content://com.unishare.app.staging/") || contains(".staging/")
