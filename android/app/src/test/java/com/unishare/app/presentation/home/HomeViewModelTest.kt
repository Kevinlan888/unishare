package com.unishare.app.presentation.home

import com.unishare.app.data.device.DeviceRepository
import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.data.history.ActivityRepository
import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.data.sdk.GatewayEvent
import com.unishare.app.data.sdk.SessionState
import com.unishare.app.data.sdk.ShareFailure
import com.unishare.app.data.settings.AppSettings
import com.unishare.app.data.settings.AppLanguage
import com.unishare.app.data.settings.AppTheme
import com.unishare.app.data.settings.SettingsRepository
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ActivityStatus
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareActivity
import com.unishare.app.domain.ShareDirection
import com.unishare.app.domain.ShareErrorKind
import com.unishare.app.domain.ShareOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Test fun refresh_waits_for_runtime_retry_before_discovery() = runTest(dispatcher) {
        val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val devices = FakeDeviceRepository(emptyList())
        val viewModel = HomeViewModel(devices, FakeActivityRepository(emptyList()),
            FakeSettingsRepository(AppSettings()), MutableStateFlow(SessionState.FAULTED),
            MutableSharedFlow(), devices::refresh, retryRuntime = { ready.await() })
        viewModel.refresh()
        runCurrent()
        assertEquals(0, devices.refreshCount)
        ready.complete(true)
        advanceUntilIdle()
        assertEquals(1, devices.refreshCount)
    }
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun repository_flows_are_joined_into_home_summaries_without_owning_new_repositories() = runTest(dispatcher) {
        val deviceId = DeviceKey("pc-1")
        val devices = FakeDeviceRepository(
            listOf(DeviceSummary(deviceId, "Studio PC", DevicePlatform.WINDOWS, online = true, trusted = true)),
        )
        val activities = FakeActivityRepository(
            listOf(
                activity(
                    deviceId = deviceId,
                    byteCount = 1_000,
                    transferredBytes = 723,
                    status = ActivityStatus.TRANSFERRING,
                ),
            ),
        )
        val settings = FakeSettingsRepository(AppSettings(deviceName = "Pixel 10"))
        val sessionState = MutableStateFlow(SessionState.RUNNING)
        val sessionEvents = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 1)

        val viewModel = HomeViewModel(
            deviceRepository = devices,
            activityRepository = activities,
            settingsRepository = settings,
            sessionState = sessionState,
            sessionEvents = sessionEvents,
            refreshDevices = devices::refresh,
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(ThisDeviceUi("Pixel 10", ThisDeviceStatus.ONLINE), state.thisDevice)
        assertEquals("Studio PC", state.recent.single().deviceName)
        assertEquals(72, state.recent.single().progressPercent)
        assertEquals(true, state.nearby.single().trusted)
        assertEquals(true, state.nearby.single().online)
    }

    @Test
    fun discovery_failure_is_non_blocking_and_refresh_clears_it_then_uses_existing_repository() = runTest(dispatcher) {
        val devices = FakeDeviceRepository(emptyList())
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 1)
        val viewModel = HomeViewModel(
            deviceRepository = devices,
            activityRepository = FakeActivityRepository(emptyList()),
            settingsRepository = FakeSettingsRepository(AppSettings()),
            sessionState = MutableStateFlow(SessionState.READY),
            sessionEvents = events,
            refreshDevices = devices::refresh,
        )
        advanceUntilIdle()

        events.emit(GatewayEvent.DiscoveryFailed(ShareFailure(ShareErrorKind.DISCOVERY_FAILED)))
        advanceUntilIdle()
        assertEquals(ShareErrorKind.DISCOVERY_FAILED, viewModel.uiState.value.discoveryError)

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1, devices.refreshCount)
        assertNull(viewModel.uiState.value.discoveryError)
    }

    private fun activity(
        deviceId: DeviceKey,
        byteCount: Long,
        transferredBytes: Long,
        status: ActivityStatus,
    ) = ShareActivity(
        id = ActivityKey("a-1"),
        deviceId = deviceId,
        direction = ShareDirection.OUTGOING,
        kind = ContentKind.FILE,
        origin = ShareOrigin.DIRECT,
        displayName = "project.zip",
        mimeType = "application/zip",
        byteCount = byteCount,
        createdAtMillis = 100,
        updatedAtMillis = 200,
        status = status,
        transferredBytes = transferredBytes,
    )
}

private class FakeDeviceRepository(initial: List<DeviceSummary>) : DeviceRepository {
    override val devices = MutableStateFlow(initial)
    var refreshCount = 0

    fun refresh() { refreshCount++ }
    override fun find(deviceId: DeviceKey) = devices.value.firstOrNull { it.id == deviceId }
    override fun isTrusted(deviceId: DeviceKey) = find(deviceId)?.trusted == true
    override fun canSend(deviceId: DeviceKey) = find(deviceId)?.online == true
    override suspend fun connect(deviceId: DeviceKey) = Unit
    override suspend fun forget(deviceId: DeviceKey) = Unit
}

private class FakeActivityRepository(initial: List<ShareActivity>) : ActivityRepository {
    private val recent = MutableStateFlow(initial)
    override fun observeRecent(limit: Int): Flow<List<ShareActivity>> = recent
    override fun observeDevice(device: DeviceKey): Flow<List<ShareActivity>> = MutableStateFlow(emptyList())
    override suspend fun upsert(activity: ShareActivity) = Unit
    override suspend fun markNonTerminalInterrupted(nowMillis: Long) = Unit
    override suspend fun prune(nowMillis: Long) = Unit
}

private class FakeSettingsRepository(initial: AppSettings) : SettingsRepository {
    override val settings = MutableStateFlow(initial)
    override suspend fun setDeviceName(name: String) = Unit
    override suspend fun setReceiveDirectory(treeUri: String?, displayName: String?) = Unit
    override suspend fun setBackgroundReceiveEnabled(enabled: Boolean) = Unit
    override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun setLanguage(language: AppLanguage) = Unit
    override suspend fun setTheme(theme: AppTheme) = Unit
}
