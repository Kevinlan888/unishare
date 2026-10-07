package com.unishare.app.service

import com.unishare.app.data.settings.AppLanguage
import com.unishare.app.data.settings.AppSettings
import com.unishare.app.data.settings.AppTheme
import com.unishare.app.data.settings.SettingsRepository
import com.unishare.app.platform.NotificationPermissionChecker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeControllerTest {
    @Test
    fun `foreground-only runtime starts while visible and stops after leaving`() = runTest {
        val fixture = fixture()
        runCurrent()

        fixture.runtime.setAppVisible(true)
        assertEquals(listOf("session:start:Pixel"), fixture.events)
        assertEquals(RuntimePhase.FOREGROUND, fixture.runtime.state.value.phase)

        fixture.runtime.setAppVisible(false)
        assertEquals(listOf("session:start:Pixel", "session:stop"), fixture.events)
        assertEquals(RuntimePhase.STOPPED, fixture.runtime.state.value.phase)
    }

    @Test
    fun `background receive persists service and session after app leaves`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.runtime.setAppVisible(true)

        assertTrue(fixture.runtime.setBackgroundReceive(true))
        fixture.runtime.setAppVisible(false)

        assertTrue(fixture.settings.value.value.backgroundReceiveEnabled)
        assertEquals(1, fixture.service.starts.size)
        assertEquals(ServiceReason.BACKGROUND_RECEIVE, fixture.service.starts.single())
        assertEquals(0, fixture.session.stopCount)
        assertEquals(RuntimePhase.BACKGROUND_RECEIVE, fixture.runtime.state.value.phase)
    }

    @Test
    fun `turning background receive off does not cancel or stop active work`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.runtime.setAppVisible(true)
        fixture.runtime.setBackgroundReceive(true)
        fixture.active.value = 1
        runCurrent()
        fixture.runtime.setAppVisible(false)

        fixture.runtime.setBackgroundReceive(false)

        assertEquals(0, fixture.session.stopCount)
        assertEquals(0, fixture.service.stopCount)
        assertEquals(1, fixture.active.value)
        assertTrue(fixture.runtime.state.value.stoppingAfterTransfers)

        fixture.active.value = 0
        runCurrent()
        assertEquals(1, fixture.session.stopCount)
        assertEquals(1, fixture.service.stopCount)
        assertEquals(RuntimePhase.STOPPED, fixture.runtime.state.value.phase)
    }

    @Test
    fun `active send gets temporary service before app enters background`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.runtime.setAppVisible(true)

        fixture.active.value = 1
        runCurrent()
        assertEquals(ServiceReason.ACTIVE_TRANSFER, fixture.service.starts.single())
        assertEquals(listOf("session:start:Pixel", "service:start:ACTIVE_TRANSFER"), fixture.events)

        fixture.runtime.setAppVisible(false)
        assertEquals(0, fixture.session.stopCount)
        fixture.active.value = 0
        runCurrent()
        assertEquals(listOf(
            "session:start:Pixel",
            "service:start:ACTIVE_TRANSFER",
            "session:stop",
            "service:stop",
        ), fixture.events)
    }

    @Test
    fun `notification denial keeps background receive disabled`() = runTest {
        val fixture = fixture(notificationGranted = false)
        runCurrent()
        fixture.runtime.setAppVisible(true)

        assertFalse(fixture.runtime.setBackgroundReceive(true))

        assertFalse(fixture.settings.value.value.backgroundReceiveEnabled)
        assertTrue(fixture.service.starts.isEmpty())
        assertEquals(RuntimeFailure.NOTIFICATION_PERMISSION_DENIED, fixture.runtime.state.value.failure)
    }

    @Test
    fun `rapid lifecycle flap serializes stale stop before latest start`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.runtime.setAppVisible(true)
        val stopEntered = CompletableDeferred<Unit>()
        val allowStop = CompletableDeferred<Unit>()
        fixture.session.beforeStop = {
            stopEntered.complete(Unit)
            allowStop.await()
        }

        val stopping = backgroundScope.launch { fixture.runtime.setAppVisible(false) }
        stopEntered.await()
        val returning = backgroundScope.launch { fixture.runtime.setAppVisible(true) }
        runCurrent()
        assertFalse(returning.isCompleted)

        allowStop.complete(Unit)
        stopping.join()
        returning.join()

        assertEquals(2, fixture.session.startCount)
        assertEquals(1, fixture.session.stopCount)
        assertEquals(RuntimePhase.FOREGROUND, fixture.runtime.state.value.phase)
        assertNull(fixture.runtime.state.value.failure)
    }

    @Test
    fun `service start rejection disables persistence but keeps visible session usable`() = runTest {
        val fixture = fixture()
        fixture.service.startFailure = IllegalStateException("rejected")
        runCurrent()
        fixture.runtime.setAppVisible(true)

        assertFalse(fixture.runtime.setBackgroundReceive(true))

        assertFalse(fixture.settings.value.value.backgroundReceiveEnabled)
        assertEquals(1, fixture.session.startCount)
        assertEquals(0, fixture.session.stopCount)
        assertEquals(RuntimeFailure.SERVICE_START_REJECTED, fixture.runtime.state.value.failure)
        assertEquals(RuntimePhase.FOREGROUND, fixture.runtime.state.value.phase)
    }

    @Test
    fun `foreground service stop waits for active transfers to drain`() = runTest {
        val active = MutableStateFlow(1)
        var stopCount = 0
        val controller = ForegroundServiceController(
            activeTransferCount = active,
            startService = { },
            stopService = { stopCount++ },
        )
        val stopping = backgroundScope.launch { controller.stopWhenIdle() }
        runCurrent()
        assertFalse(stopping.isCompleted)
        assertEquals(0, stopCount)

        active.value = 0
        stopping.join()
        assertEquals(1, stopCount)
    }

    @Test
    fun `repeated lifecycle transitions reuse the single session owner`() = runTest {
        val fixture = fixture()
        runCurrent()

        repeat(3) {
            fixture.runtime.setAppVisible(true)
            fixture.runtime.setAppVisible(false)
        }

        assertEquals(3, fixture.session.startCount)
        assertEquals(3, fixture.session.stopCount)
    }

    @Test
    fun `first visible transition waits for persisted device name`() = runTest {
        val events = mutableListOf<String>()
        val settings = DelayedSettingsRepository()
        val session = FakeRuntimeSession(events)
        val runtime = RuntimeController(
            settingsRepository = settings,
            session = session,
            foregroundService = FakeForegroundService(events),
            activeTransferCount = MutableStateFlow(0),
            notificationPermission = NotificationPermissionChecker { true },
            scope = backgroundScope,
        )

        val becomingVisible = backgroundScope.launch { runtime.setAppVisible(true) }
        runCurrent()
        assertFalse(becomingVisible.isCompleted)
        assertEquals(0, session.startCount)

        settings.values.emit(AppSettings(deviceName = "Persisted Pixel"))
        becomingVisible.join()
        assertEquals(listOf("session:start:Persisted Pixel"), events)
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        notificationGranted: Boolean = true,
    ): Fixture {
        val events = mutableListOf<String>()
        val settings = FakeSettingsRepository()
        val session = FakeRuntimeSession(events)
        val service = FakeForegroundService(events)
        val active = MutableStateFlow(0)
        val runtime = RuntimeController(
            settingsRepository = settings,
            session = session,
            foregroundService = service,
            activeTransferCount = active,
            notificationPermission = NotificationPermissionChecker { notificationGranted },
            scope = backgroundScope,
        )
        return Fixture(runtime, settings, session, service, active, events)
    }

    private data class Fixture(
        val runtime: RuntimeController,
        val settings: FakeSettingsRepository,
        val session: FakeRuntimeSession,
        val service: FakeForegroundService,
        val active: MutableStateFlow<Int>,
        val events: MutableList<String>,
    )

    private class FakeRuntimeSession(private val events: MutableList<String>) : RuntimeSession {
        var startCount = 0
        var stopCount = 0
        var beforeStop: suspend () -> Unit = { }

        override suspend fun start(deviceName: String) {
            startCount++
            events += "session:start:$deviceName"
        }

        override suspend fun stop() {
            beforeStop()
            stopCount++
            events += "session:stop"
        }
    }

    private class FakeForegroundService(private val events: MutableList<String>) : ForegroundServiceGateway {
        val starts = mutableListOf<ServiceReason>()
        var stopCount = 0
        var startFailure: RuntimeException? = null

        override fun start(reason: ServiceReason) {
            startFailure?.let { throw it }
            starts += reason
            events += "service:start:$reason"
        }

        override suspend fun stopWhenIdle() {
            stopCount++
            events += "service:stop"
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        val value = MutableStateFlow(AppSettings(deviceName = "Pixel"))
        override val settings: Flow<AppSettings> = value
        override suspend fun setDeviceName(name: String) { value.value = value.value.copy(deviceName = name) }
        override suspend fun setReceiveDirectory(treeUri: String?, displayName: String?) {
            value.value = value.value.copy(receiveTreeUri = treeUri, receiveDirectoryDisplayName = displayName)
        }
        override suspend fun setBackgroundReceiveEnabled(enabled: Boolean) {
            value.value = value.value.copy(backgroundReceiveEnabled = enabled)
        }
        override suspend fun setNotificationsEnabled(enabled: Boolean) {
            value.value = value.value.copy(notificationsEnabled = enabled)
        }
        override suspend fun setLanguage(language: AppLanguage) { value.value = value.value.copy(language = language) }
        override suspend fun setTheme(theme: AppTheme) { value.value = value.value.copy(theme = theme) }
    }

    private class DelayedSettingsRepository : SettingsRepository {
        val values = MutableSharedFlow<AppSettings>()
        override val settings: Flow<AppSettings> = values
        override suspend fun setDeviceName(name: String) = Unit
        override suspend fun setReceiveDirectory(treeUri: String?, displayName: String?) = Unit
        override suspend fun setBackgroundReceiveEnabled(enabled: Boolean) = Unit
        override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
        override suspend fun setLanguage(language: AppLanguage) = Unit
        override suspend fun setTheme(theme: AppTheme) = Unit
    }
}
