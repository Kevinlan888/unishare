package com.unishare.app.service

import com.unishare.app.data.settings.AppLanguage
import com.unishare.app.data.settings.AppSettings
import com.unishare.app.data.settings.AppTheme
import com.unishare.app.data.settings.SettingsRepository
import com.unishare.app.platform.NotificationPermissionChecker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeControllerTest {
    @Test fun explicit_retry_reuses_real_session_owner_after_initialize_or_sdk_start_fault() = runTest {
        for (failInitialize in listOf(true, false)) {
            val gateway = com.unishare.app.data.sdk.FakeShareSdkGateway()
            if (failInitialize) gateway.initializeFailure = IllegalStateException("init")
            else gateway.startFailure = IllegalStateException("start")
            val session = com.unishare.app.data.sdk.ShareSessionManager(gateway, backgroundScope)
            val runtime = RuntimeController(FakeSettingsRepository(), ShareSessionRuntime(session),
                FakeForegroundService(mutableListOf()), MutableStateFlow(0), NotificationPermissionChecker { true }, backgroundScope)
            runtime.setAppVisible(true)
            assertEquals(com.unishare.app.data.sdk.SessionState.FAULTED, session.state.value)
            gateway.initializeFailure = null; gateway.startFailure = null
            assertTrue(runtime.retry())
            assertEquals(com.unishare.app.data.sdk.SessionState.RUNNING, session.state.value)
            assertEquals(1, gateway.calls.count { it == "subscribe" })
            session.close()
        }
    }
    @Test fun explicit_retry_recovers_failed_session_start() = runTest {
        val fixture = fixture()
        fixture.session.startFailure = IllegalStateException("start failed")
        fixture.runtime.setAppVisible(true)
        assertEquals(RuntimeFailure.SESSION_START_FAILED, fixture.runtime.state.value.failure)
        fixture.session.startFailure = null
        assertTrue(fixture.runtime.retry())
        assertEquals(RuntimePhase.FOREGROUND, fixture.runtime.state.value.phase)
        assertNull(fixture.runtime.state.value.failure)
    }

    @Test fun explicit_retry_recovers_service_start_for_active_work() = runTest {
        val fixture = fixture()
        fixture.runtime.setAppVisible(true)
        fixture.service.startFailure = IllegalStateException("service failed")
        fixture.active.value = 1
        runCurrent()
        assertEquals(RuntimeFailure.SERVICE_START_REJECTED, fixture.runtime.state.value.failure)
        fixture.service.startFailure = null
        assertTrue(fixture.runtime.retry())
        assertTrue(fixture.runtime.state.value.foregroundServiceRunning)
        assertNull(fixture.runtime.state.value.failure)
    }
    @Test fun `notification-off rollback IOException stays inside application scope and safely stops receive`() = runTest {
        assertRollbackWriteFailureSafe(activeTransfers = 0)
    }

    @Test fun `notification-off rollback IOException preserves active transfer service until drain`() = runTest {
        assertRollbackWriteFailureSafe(activeTransfers = 1)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertRollbackWriteFailureSafe(activeTransfers: Int) {
        val uncaught = mutableListOf<Throwable>()
        val applicationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) +
            CoroutineExceptionHandler { _, error -> uncaught += error })
        try {
            val fixture = fixture(scope = applicationScope)
            fixture.runtime.setAppVisible(true)
            assertTrue(fixture.runtime.setBackgroundReceive(true))
            fixture.active.value = activeTransfers
            runCurrent()
            fixture.runtime.setAppVisible(false)
            fixture.settings.backgroundWriteFailure = IOException("disk unavailable")
            val attemptsBefore = fixture.settings.backgroundWriteAttempts
            fixture.settings.setNotificationsEnabled(false)
            runCurrent()
            assertEquals(emptyList<Throwable>(), uncaught)
            assertFalse(fixture.runtime.state.value.backgroundReceiveEnabled)
            assertEquals(RuntimeFailure.SETTINGS_WRITE_FAILED, fixture.runtime.state.value.failure)
            assertEquals(if (activeTransfers == 0) RuntimePhase.STOPPED else RuntimePhase.ACTIVE_TRANSFER,
                fixture.runtime.state.value.phase)
            assertEquals(activeTransfers > 0, fixture.runtime.state.value.foregroundServiceRunning)
            assertEquals(if (activeTransfers == 0) 1 else 0, fixture.service.stopCount)
            assertEquals(attemptsBefore + 1, fixture.settings.backgroundWriteAttempts)
            // A failed persist must not spin or re-enable on unrelated settings emissions.
            fixture.settings.setDeviceName("Changed")
            fixture.settings.setNotificationsEnabled(true)
            runCurrent()
            assertFalse(fixture.runtime.state.value.backgroundReceiveEnabled)
            assertEquals(attemptsBefore + 1, fixture.settings.backgroundWriteAttempts)
            fixture.active.value = 0
            runCurrent()
            assertEquals(RuntimePhase.STOPPED, fixture.runtime.state.value.phase)
            assertEquals(1, fixture.service.stopCount)
            assertEquals(1, fixture.session.stopCount)
        } finally { applicationScope.cancel() }
    }

    @Test fun `runtime stop propagates cancellation instead of recording a session failure`() = runTest {
        val fixture = fixture()
        fixture.runtime.setAppVisible(true)
        fixture.session.beforeStop = { throw CancellationException("cancel stop") }
        val stopping = backgroundScope.launch { fixture.runtime.setAppVisible(false) }
        runCurrent()
        assertTrue(stopping.isCancelled)
        assertNull(fixture.runtime.state.value.failure)
    }

    @Test fun `rollback write cancellation is not converted to an ordinary runtime failure`() = runTest {
        val fixture = fixture()
        fixture.runtime.setAppVisible(true)
        fixture.runtime.setBackgroundReceive(true)
        fixture.settings.backgroundWriteFailure = CancellationException("cancel write")
        val disabling = backgroundScope.launch { fixture.runtime.setBackgroundReceive(false) }
        runCurrent()
        assertTrue(disabling.isCancelled)
        assertNull(fixture.runtime.state.value.failure)
    }

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
            "service:stop",
            "session:stop",
        ), fixture.events)
    }

    @Test
    fun `visibility loss observes active transfer before flow collector is dispatched`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.runtime.setAppVisible(true)

        fixture.active.value = 1
        fixture.runtime.setAppVisible(false)
        runCurrent()

        assertEquals(0, fixture.session.stopCount)
        assertEquals(listOf(ServiceReason.ACTIVE_TRANSFER), fixture.service.starts)
        assertEquals(RuntimePhase.ACTIVE_TRANSFER, fixture.runtime.state.value.phase)
        assertNull(fixture.runtime.state.value.failure)
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
    fun `persisted background receive is rolled off when notification permission is denied`() = runTest {
        val fixture = fixture(
            notificationGranted = false,
            initialSettings = AppSettings(
                deviceName = "Pixel",
                backgroundReceiveEnabled = true,
            ),
        )
        runCurrent()

        fixture.runtime.setAppVisible(true)

        assertFalse(fixture.settings.value.value.backgroundReceiveEnabled)
        assertTrue(fixture.service.starts.isEmpty())
        assertEquals(RuntimeFailure.NOTIFICATION_PERMISSION_DENIED, fixture.runtime.state.value.failure)
        assertEquals(RuntimePhase.FOREGROUND, fixture.runtime.state.value.phase)
    }

    @Test
    fun `revoked notification permission tears down persistent receive without cancelling visibility`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.runtime.setAppVisible(true)
        assertTrue(fixture.runtime.setBackgroundReceive(true))
        fixture.runtime.setAppVisible(false)

        fixture.permission.granted = false
        fixture.runtime.setAppVisible(false)

        assertFalse(fixture.settings.value.value.backgroundReceiveEnabled)
        assertEquals(1, fixture.session.stopCount)
        assertEquals(1, fixture.service.stopCount)
        assertEquals(RuntimeFailure.NOTIFICATION_PERMISSION_DENIED, fixture.runtime.state.value.failure)
        assertEquals(RuntimePhase.STOPPED, fixture.runtime.state.value.phase)
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
            startService = { _, _ -> },
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
    fun `foreground service stop cannot overtake pending foreground promotion`() = runTest {
        val active = MutableStateFlow(0)
        var requestId: Long? = null
        var stopCount = 0
        val controller = ForegroundServiceController(
            activeTransferCount = active,
            startService = { _, id -> requestId = id },
            stopService = { stopCount++ },
        )

        val starting = backgroundScope.launch { controller.start(ServiceReason.ACTIVE_TRANSFER) }
        runCurrent()
        assertFalse(starting.isCompleted)
        val stopping = backgroundScope.launch { controller.stopWhenIdle() }
        runCurrent()

        assertFalse(stopping.isCompleted)
        assertEquals(0, stopCount)
        controller.onForegroundStarted(requireNotNull(requestId))
        starting.join()
        stopping.join()
        assertEquals(1, stopCount)
    }

    @Test
    fun `cancelled start waiting for lifecycle lock never starts the service later`() = runTest {
        val requestIds = mutableListOf<Long>()
        val controller = ForegroundServiceController(
            activeTransferCount = MutableStateFlow(0),
            startService = { _, id -> requestIds += id },
            stopService = { },
        )
        val first = backgroundScope.launch { controller.start(ServiceReason.ACTIVE_TRANSFER) }
        runCurrent()
        val cancelled = backgroundScope.launch { controller.start(ServiceReason.BACKGROUND_RECEIVE) }
        runCurrent()
        cancelled.cancel()

        controller.onForegroundStarted(requestIds.single())
        runCurrent()
        val requestsAfterCancellation = requestIds.size
        requestIds.drop(1).forEach(controller::onForegroundStarted)
        first.join()
        cancelled.join()

        assertEquals(1, requestsAfterCancellation)
    }

    @Test
    fun `foreground service stop rechecks idle state after pending promotion`() = runTest {
        val active = MutableStateFlow(0)
        var requestId: Long? = null
        var stopCount = 0
        val controller = ForegroundServiceController(
            activeTransferCount = active,
            startService = { _, id -> requestId = id },
            stopService = { stopCount++ },
        )
        val starting = backgroundScope.launch { controller.start(ServiceReason.ACTIVE_TRANSFER) }
        runCurrent()
        val stopping = backgroundScope.launch { controller.stopWhenIdle() }
        runCurrent()
        active.value = 1

        controller.onForegroundStarted(requireNotNull(requestId))
        runCurrent()

        assertEquals(0, stopCount)
        assertFalse(stopping.isCompleted)
        active.value = 0
        starting.join()
        stopping.join()
        assertEquals(1, stopCount)
    }

    @Test
    fun `service stop failure leaves session running for an explicit retry`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.runtime.setAppVisible(true)
        fixture.runtime.setBackgroundReceive(true)
        fixture.runtime.setAppVisible(false)
        fixture.service.stopFailure = IllegalStateException("stop rejected")

        fixture.runtime.setBackgroundReceive(false)

        assertEquals(1, fixture.service.stopAttempts)
        assertEquals(0, fixture.session.stopCount)
        assertEquals(RuntimeFailure.SERVICE_STOP_FAILED, fixture.runtime.state.value.failure)
        assertTrue(fixture.runtime.state.value.foregroundServiceRunning)

        fixture.service.stopFailure = null
        fixture.runtime.setBackgroundReceive(false)
        assertEquals(1, fixture.service.stopCount)
        assertEquals(1, fixture.session.stopCount)
        assertEquals(RuntimePhase.STOPPED, fixture.runtime.state.value.phase)
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
        initialSettings: AppSettings = AppSettings(deviceName = "Pixel"),
        scope: CoroutineScope = backgroundScope,
    ): Fixture {
        val events = mutableListOf<String>()
        val settings = FakeSettingsRepository(initialSettings)
        val session = FakeRuntimeSession(events)
        val service = FakeForegroundService(events)
        val active = MutableStateFlow(0)
        val permission = MutableNotificationPermissionChecker(notificationGranted)
        val runtime = RuntimeController(
            settingsRepository = settings,
            session = session,
            foregroundService = service,
            activeTransferCount = active,
            notificationPermission = permission,
            scope = scope,
        )
        return Fixture(runtime, settings, session, service, active, permission, events)
    }

    private data class Fixture(
        val runtime: RuntimeController,
        val settings: FakeSettingsRepository,
        val session: FakeRuntimeSession,
        val service: FakeForegroundService,
        val active: MutableStateFlow<Int>,
        val permission: MutableNotificationPermissionChecker,
        val events: MutableList<String>,
    )

    private class FakeRuntimeSession(private val events: MutableList<String>) : RuntimeSession {
        var startFailure: Exception? = null
        var startCount = 0
        var stopCount = 0
        var beforeStop: suspend () -> Unit = { }

        override suspend fun start(deviceName: String) {
            startFailure?.let { throw it }
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
        var stopAttempts = 0
        var startFailure: RuntimeException? = null
        var stopFailure: RuntimeException? = null

        override suspend fun start(reason: ServiceReason) {
            startFailure?.let { throw it }
            starts += reason
            events += "service:start:$reason"
        }

        override suspend fun stopWhenIdle() {
            stopAttempts++
            stopFailure?.let { throw it }
            stopCount++
            events += "service:stop"
        }
    }

    private class FakeSettingsRepository(initial: AppSettings = AppSettings(deviceName = "Pixel")) : SettingsRepository {
        val value = MutableStateFlow(initial)
        var backgroundWriteFailure: Exception? = null
        var backgroundWriteAttempts = 0
        override val settings: Flow<AppSettings> = value
        override suspend fun setDeviceName(name: String) { value.value = value.value.copy(deviceName = name) }
        override suspend fun setReceiveDirectory(treeUri: String?, displayName: String?) {
            value.value = value.value.copy(receiveTreeUri = treeUri, receiveDirectoryDisplayName = displayName)
        }
        override suspend fun setBackgroundReceiveEnabled(enabled: Boolean) {
            backgroundWriteAttempts++
            backgroundWriteFailure?.let { throw it }
            value.value = value.value.copy(backgroundReceiveEnabled = enabled)
        }
        override suspend fun setNotificationsEnabled(enabled: Boolean) {
            value.value = value.value.copy(notificationsEnabled = enabled)
        }
        override suspend fun setLanguage(language: AppLanguage) { value.value = value.value.copy(language = language) }
        override suspend fun setTheme(theme: AppTheme) { value.value = value.value.copy(theme = theme) }
    }

    private class MutableNotificationPermissionChecker(var granted: Boolean) : NotificationPermissionChecker {
        override fun isGranted(): Boolean = granted
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
