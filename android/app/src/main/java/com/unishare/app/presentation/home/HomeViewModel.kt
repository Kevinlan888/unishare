package com.unishare.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unishare.app.AppGraph
import com.unishare.app.data.device.DeviceRepository
import com.unishare.app.data.history.ActivityRepository
import com.unishare.app.data.sdk.GatewayEvent
import com.unishare.app.data.sdk.GatewayException
import com.unishare.app.data.sdk.SessionState
import com.unishare.app.data.settings.SettingsRepository
import com.unishare.app.domain.ActivityStatus
import com.unishare.app.domain.ShareErrorKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    deviceRepository: DeviceRepository,
    activityRepository: ActivityRepository,
    settingsRepository: SettingsRepository,
    sessionState: StateFlow<SessionState>,
    sessionEvents: Flow<GatewayEvent>,
    private val refreshDevices: () -> Unit,
    private val retryRuntime: suspend () -> Boolean = { true },
) : ViewModel() {
    private val discoveryError = MutableStateFlow<ShareErrorKind?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        deviceRepository.devices,
        activityRepository.observeRecent(3),
        sessionState,
        discoveryError,
    ) { settings, devices, activities, state, error ->
        val deviceById = devices.associateBy { it.id }
        HomeUiState(
            isLoading = state == SessionState.UNINITIALIZED || state == SessionState.INITIALIZING,
            thisDevice = ThisDeviceUi(
                name = settings.deviceName,
                status = state.toThisDeviceStatus(),
            ),
            recent = activities.map { activity ->
                val peer = deviceById[activity.deviceId]
                RecentActivityUi(
                    activityId = activity.id,
                    deviceId = activity.deviceId,
                    deviceName = peer?.name.orEmpty(),
                    platform = peer?.platform,
                    displayName = activity.displayName,
                    status = activity.status,
                    progressPercent = activity.progressPercent(),
                    updatedAtMillis = activity.updatedAtMillis,
                    kind = activity.kind,
                )
            },
            nearby = devices
                .sortedWith(compareByDescending<com.unishare.app.data.device.DeviceSummary> { it.connected }
                    .thenByDescending { it.online }
                    .thenByDescending { it.trusted }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .map { device ->
                    NearbyDeviceUi(
                        id = device.id,
                        name = device.name,
                        platform = device.platform,
                        online = device.online,
                        trusted = device.trusted,
                        connected = device.connected,
                        identityChanged = device.identityChanged,
                    )
                },
            discoveryError = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState(isLoading = true),
    )

    init {
        viewModelScope.launch {
            sessionEvents.collect { event ->
                when (event) {
                    is GatewayEvent.DiscoveryFailed -> discoveryError.value = event.failure.kind
                    is GatewayEvent.DiscoveryStateChanged -> discoveryError.value = null
                    else -> Unit
                }
            }
        }
    }

    fun refresh() {
        discoveryError.value = null
        viewModelScope.launch {
            try {
                if (retryRuntime()) refreshDevices()
                else discoveryError.value = ShareErrorKind.DISCOVERY_FAILED
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: GatewayException) {
                discoveryError.value = error.failure.kind
            } catch (_: Exception) {
                discoveryError.value = ShareErrorKind.DISCOVERY_FAILED
            }
        }
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(
                deviceRepository = graph.deviceRepository,
                activityRepository = graph.activityRepository,
                settingsRepository = graph.settingsRepository,
                sessionState = graph.sessionManager.state,
                sessionEvents = graph.sessionManager.events,
                refreshDevices = graph.deviceRepository::refresh,
                retryRuntime = graph.runtimeController::retry,
            ) as T
        }
    }
}

private fun SessionState.toThisDeviceStatus(): ThisDeviceStatus = when (this) {
    SessionState.RUNNING -> ThisDeviceStatus.ONLINE
    SessionState.UNINITIALIZED, SessionState.INITIALIZING -> ThisDeviceStatus.STARTING
    SessionState.FAULTED -> ThisDeviceStatus.UNAVAILABLE
    SessionState.READY, SessionState.STOPPING, SessionState.STOPPED, SessionState.CLOSED -> ThisDeviceStatus.OFFLINE
}

private fun com.unishare.app.domain.ShareActivity.progressPercent(): Int? {
    if (status != ActivityStatus.TRANSFERRING || byteCount <= 0) return null
    return ((transferredBytes.toDouble() / byteCount.toDouble()) * 100.0).toInt().coerceIn(0, 100)
}
