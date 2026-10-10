package com.unishare.app.presentation.session

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.unishare.app.data.device.DeviceRepository
import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ActivityPreview
import com.unishare.app.domain.ActivityStatus
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareActivity
import com.unishare.app.domain.ShareDirection
import com.unishare.app.domain.ShareErrorKind
import com.unishare.app.domain.ShareOrigin
import com.unishare.app.data.transfer.fakeUri
import com.unishare.app.data.transfer.RetainedContentUri
import com.unishare.app.data.transfer.ContentReadPermissionLease
import com.unishare.app.presentation.pairing.PairingUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    @Test
    fun forgetting_the_device_completes_before_leaving_the_session() = runTest(dispatcher) {
        val repository = FakeSessionDeviceRepository(device())
        val viewModel = SessionViewModel(
            deviceId = deviceId,
            devices = repository,
            activities = MutableStateFlow(emptyList()),
            previewFor = { null },
            operations = FakeSessionOperations(),
        )
        var leftSession = false

        viewModel.forgetDevice { leftSession = true }
        advanceUntilIdle()

        assertEquals(listOf(deviceId), repository.forgotten)
        assertTrue(leftSession)
    }

    @Test
    fun forgetting_failure_keeps_the_session_open_and_reports_the_device_error() = runTest(dispatcher) {
        val repository = FakeSessionDeviceRepository(device()).apply {
            forgetFailure = IllegalStateException("native failure")
        }
        val viewModel = SessionViewModel(
            deviceId = deviceId,
            devices = repository,
            activities = MutableStateFlow(emptyList()),
            previewFor = { null },
            operations = FakeSessionOperations(),
        )
        var leftSession = false
        val event = async { viewModel.events.first() }

        viewModel.forgetDevice { leftSession = true }
        advanceUntilIdle()

        assertFalse(leftSession)
        assertEquals(SessionUserMessage.FORGET_FAILED, event.await().message)
    }

    @Test
    fun only_the_matching_device_marks_its_session_as_pairing() = runTest(dispatcher) {
        val pairing = MutableStateFlow(
            PairingUiState(matchingDeviceIds = setOf(deviceId)),
        )
        val viewModel = SessionViewModel(
            deviceId = deviceId,
            devices = FakeSessionDeviceRepository(device()),
            activities = MutableStateFlow(emptyList()),
            previewFor = { null },
            operations = FakeSessionOperations(),
            pairingState = pairing,
        )
        runCurrent()

        assertTrue(viewModel.uiState.value.pairing)

        pairing.value = PairingUiState(
            matchingDeviceIds = setOf(DeviceKey("another-device")),
        )
        runCurrent()

        assertFalse(viewModel.uiState.value.pairing)
    }

    @Test fun outgoing_retry_waits_for_reconnection_after_background_stop() = runTest(dispatcher) {
        val repository = FakeSessionDeviceRepository(device())
        repository.connectGate = CompletableDeferred()
        val operations = FakeSessionOperations()
        val failed = activity("retry").copy(direction = ShareDirection.OUTGOING, status = ActivityStatus.FAILED)
        val vm = SessionViewModel(deviceId, repository, MutableStateFlow(listOf(failed)), { null }, operations)
        runCurrent()
        vm.retry(failed.id)
        runCurrent()
        assertEquals(1, repository.connectCalls)
        assertTrue(operations.retried.isEmpty())
        repository.connectGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(failed.id), operations.retried)
    }
    @Test fun real_runtime_stop_picker_callback_restart_discovery_and_connect_preserve_selection() = runTest(dispatcher) {
        val gateway = com.unishare.app.data.sdk.FakeShareSdkGateway().apply {
            snapshots = listOf(com.unishare.app.data.sdk.DeviceSnapshot(deviceId, "peer", DevicePlatform.WINDOWS))
            trusted = setOf(deviceId)
            connectCompletion = CompletableDeferred()
        }
        val session = com.unishare.app.data.sdk.ShareSessionManager(gateway, backgroundScope)
        val devices = com.unishare.app.data.device.DefaultDeviceRepository(session, backgroundScope)
        val settings = object : com.unishare.app.data.settings.SettingsRepository {
            override val settings = MutableStateFlow(com.unishare.app.data.settings.AppSettings())
            override suspend fun setDeviceName(name: String) = Unit
            override suspend fun setReceiveDirectory(treeUri: String?, displayName: String?) = Unit
            override suspend fun setBackgroundReceiveEnabled(enabled: Boolean) = Unit
            override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
            override suspend fun setLanguage(language: com.unishare.app.data.settings.AppLanguage) = Unit
            override suspend fun setTheme(theme: com.unishare.app.data.settings.AppTheme) = Unit
        }
        val service = object : com.unishare.app.service.ForegroundServiceGateway {
            override suspend fun start(reason: com.unishare.app.service.ServiceReason) = Unit
            override suspend fun stopWhenIdle() = Unit
        }
        val runtime = com.unishare.app.service.RuntimeController(settings, com.unishare.app.service.ShareSessionRuntime(session),
            service, MutableStateFlow(0), com.unishare.app.platform.NotificationPermissionChecker { true }, backgroundScope)
        runtime.setAppVisible(true); runCurrent()
        runtime.setAppVisible(false); runCurrent()
        assertFalse(devices.find(deviceId)!!.online)
        val operations = FakeSessionOperations()
        val lease = RecordingSessionContentLease()
        val vm = SessionViewModel(deviceId, devices, MutableStateFlow(emptyList()), { null }, operations, runtime::awaitForegroundReady)
        vm.sendPickedContent(RetainedContentUri(fakeUri(), lease), ContentKind.FILE)
        runCurrent()
        assertEquals(0, lease.closeCalls)
        assertEquals(0, operations.pickedContent.size)
        gateway.snapshots = emptyList()
        runtime.setAppVisible(true); runCurrent()
        assertEquals(0, operations.pickedContent.size)
        gateway.events.emit(com.unishare.app.data.sdk.GatewayEvent.DeviceOnline(
            com.unishare.app.data.sdk.DeviceSnapshot(deviceId, "peer", DevicePlatform.WINDOWS)))
        runCurrent()
        assertEquals(1, gateway.calls.count { it.startsWith("connect:") })
        assertEquals(0, lease.closeCalls)
        gateway.snapshots = listOf(com.unishare.app.data.sdk.DeviceSnapshot(deviceId, "peer", DevicePlatform.WINDOWS))
        gateway.connectCompletion!!.complete(Unit)
        runCurrent()
        assertEquals(1, operations.pickedContent.size)
        assertEquals(1, gateway.calls.count { it == "subscribe" })
        session.close()
    }
    @Test fun send_waits_for_authenticated_connection_and_serializes_pairing() = runTest(dispatcher) {
        val repository = FakeSessionDeviceRepository(device(trusted = false))
        repository.connectGate = CompletableDeferred()
        repository.trustOnConnect = true
        val operations = FakeSessionOperations()
        val vm = SessionViewModel(deviceId, repository, MutableStateFlow(emptyList()), { null }, operations)
        vm.onComposerChanged("hello")
        vm.sendComposer()
        runCurrent()
        assertEquals(1, repository.connectCalls)
        assertTrue(operations.sentTexts.isEmpty())
        repository.connectGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("hello"), operations.sentTexts)
    }

    @Test fun picker_result_before_resume_retains_lease_until_runtime_and_connection_ready() = runTest(dispatcher) {
        val runtime = CompletableDeferred<Unit>()
        val repository = FakeSessionDeviceRepository(device(online = false))
        repository.connectGate = CompletableDeferred()
        val operations = FakeSessionOperations()
        val lease = RecordingSessionContentLease()
        val vm = SessionViewModel(deviceId, repository, MutableStateFlow(emptyList()), { null }, operations,
            awaitRuntime = { runtime.await() })
        vm.sendPickedContent(RetainedContentUri(fakeUri(), lease), ContentKind.FILE)
        runCurrent()
        assertEquals(0, lease.closeCalls)
        runtime.complete(Unit)
        runCurrent()
        assertTrue(operations.pickedContent.isEmpty())
        repository.mutableDevices.value = listOf(device())
        runCurrent()
        assertEquals(0, lease.closeCalls)
        repository.connectGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, operations.pickedContent.size)
        assertEquals(0, lease.closeCalls)
    }
    private val dispatcher = StandardTestDispatcher()
    private val deviceId = DeviceKey("pc-kevin")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun offline_device_blocks_send_and_keeps_composer_content() = runTest(dispatcher) {
        val operations = FakeSessionOperations()
        val viewModel = viewModel(operations, device(online = false, trusted = true))
        viewModel.onComposerChanged("keep me")
        val event = async { viewModel.events.first() }

        viewModel.sendComposer()
        advanceUntilIdle()

        assertEquals("keep me", viewModel.uiState.value.composerText)
        assertTrue(operations.sentTexts.isEmpty())
        assertEquals(SessionUserMessage.DEVICE_OFFLINE, event.await().message)
    }

    @Test
    fun connected_device_can_send_without_discovery_or_reconnecting() = runTest(dispatcher) {
        val repository = FakeSessionDeviceRepository(device(online = false, connected = true))
        val operations = FakeSessionOperations()
        val viewModel = SessionViewModel(deviceId, repository, MutableStateFlow(emptyList()), { null }, operations)
        viewModel.onComposerChanged("still connected")

        viewModel.sendComposer()
        advanceUntilIdle()

        assertEquals(listOf("still connected"), operations.sentTexts)
        assertEquals(0, repository.connectCalls)
        assertTrue(viewModel.uiState.value.connected)
    }

    @Test
    fun connected_device_can_send_picked_content_without_waiting_for_discovery() = runTest(dispatcher) {
        val repository = FakeSessionDeviceRepository(device(online = false, connected = true))
        val operations = FakeSessionOperations()
        val lease = RecordingSessionContentLease()
        val viewModel = SessionViewModel(deviceId, repository, MutableStateFlow(emptyList()), { null }, operations)

        viewModel.sendPickedContent(RetainedContentUri(fakeUri(), lease), ContentKind.FILE)
        runCurrent()

        assertEquals(1, operations.pickedContent.size)
        assertEquals(0, repository.connectCalls)
        assertEquals(0, lease.closeCalls)
    }

    @Test
    fun untrusted_device_blocks_send_before_reaching_transfer_boundary() = runTest(dispatcher) {
        val operations = FakeSessionOperations()
        val viewModel = viewModel(operations, device(online = true, trusted = false))
        viewModel.onComposerChanged("hello")
        val event = async { viewModel.events.first() }

        viewModel.sendComposer()
        advanceUntilIdle()

        assertTrue(operations.sentTexts.isEmpty())
        assertEquals(SessionUserMessage.DEVICE_NOT_TRUSTED, event.await().message)
    }

    @Test
    fun blank_text_is_rejected_without_submitting_an_activity() = runTest(dispatcher) {
        val operations = FakeSessionOperations()
        val viewModel = viewModel(operations)
        viewModel.onComposerChanged("  \n ")
        val event = async { viewModel.events.first() }

        viewModel.sendComposer()
        advanceUntilIdle()

        assertTrue(operations.sentTexts.isEmpty())
        assertEquals(SessionUserMessage.TEXT_REQUIRED, event.await().message)
    }

    @Test
    fun composer_clears_only_after_submission_is_accepted() = runTest(dispatcher) {
        val operations = FakeSessionOperations().apply { sendGate = CompletableDeferred() }
        val viewModel = viewModel(operations)
        viewModel.onComposerChanged("Hello from Android!")

        viewModel.sendComposer()
        runCurrent()
        assertEquals("Hello from Android!", viewModel.uiState.value.composerText)

        operations.sendGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.composerText)
        assertEquals(listOf("Hello from Android!"), operations.sentTexts)
    }

    @Test
    fun picker_cancellation_is_a_no_op() = runTest(dispatcher) {
        val operations = FakeSessionOperations()
        val viewModel = viewModel(operations)

        viewModel.sendPickedContent(null, ContentKind.IMAGE)
        advanceUntilIdle()

        assertTrue(operations.pickedContent.isEmpty())
        assertNull(withTimeoutOrNull(1) { viewModel.events.first() })
    }

    @Test
    fun clearing_view_model_before_picker_work_starts_releases_unclaimed_uri_lease() = runTest(dispatcher) {
        val operations = FakeSessionOperations()
        val viewModel = viewModel(operations)
        val lease = RecordingSessionContentLease()
        val store = ViewModelStore().apply { put("session", viewModel) }

        viewModel.sendPickedContent(RetainedContentUri(fakeUri(), lease), ContentKind.IMAGE)
        store.clear()
        advanceUntilIdle()

        assertEquals(1, lease.closeCalls)
        assertTrue(operations.pickedContent.isEmpty())
    }

    @Test
    fun one_failed_submission_emits_one_non_replaying_user_event() = runTest(dispatcher) {
        val operations = FakeSessionOperations().apply { failure = IllegalStateException("provider secret") }
        val viewModel = viewModel(operations)
        viewModel.onComposerChanged("hello")
        val event = async { viewModel.events.first() }

        viewModel.sendComposer()
        advanceUntilIdle()

        assertEquals(SessionUserMessage.SEND_FAILED, event.await().message)
        assertEquals("hello", viewModel.uiState.value.composerText)
        assertNull(withTimeoutOrNull(1) { viewModel.events.first() })
    }

    @Test
    fun cancel_and_retry_target_the_selected_activity() = runTest(dispatcher) {
        val operations = FakeSessionOperations()
        val viewModel = viewModel(operations)
        val first = ActivityKey("first")
        val second = ActivityKey("second")

        viewModel.cancel(first)
        viewModel.retry(second)
        advanceUntilIdle()

        assertEquals(listOf(first), operations.cancelled)
        assertEquals(listOf(second), operations.retried)
    }

    @Test
    fun disappearing_device_keeps_historical_content_and_last_known_identity() = runTest(dispatcher) {
        val activities = MutableStateFlow(listOf(activity(text = "still visible")))
        val repository = FakeSessionDeviceRepository(device(name = "Kevin's Windows PC"))
        val viewModel = SessionViewModel(
            deviceId = deviceId,
            devices = repository,
            activities = activities,
            previewFor = { ActivityPreview.Text("still visible") },
            operations = FakeSessionOperations(),
        )
        advanceUntilIdle()
        repository.mutableDevices.value = emptyList()
        advanceUntilIdle()

        assertEquals("Kevin's Windows PC", viewModel.uiState.value.deviceName)
        assertFalse(viewModel.uiState.value.online)
        assertEquals("still visible", viewModel.uiState.value.activities.single().text)
    }

    @Test
    fun clipboard_is_never_read_when_activity_is_not_resumed() = runTest(dispatcher) {
        val operations = FakeSessionOperations()
        val viewModel = viewModel(operations)
        val event = async { viewModel.events.first() }

        viewModel.sendClipboard { false }
        advanceUntilIdle()

        assertEquals(0, operations.clipboardReads)
        assertEquals(SessionUserMessage.CLIPBOARD_REQUIRES_FOREGROUND, event.await().message)
    }

    @Test
    fun link_composer_mode_routes_to_link_boundary_and_resets_after_acceptance() = runTest(dispatcher) {
        val operations = FakeSessionOperations()
        val viewModel = viewModel(operations)
        viewModel.showShareSheet()
        viewModel.selectLinkComposer()
        viewModel.onComposerChanged("https://github.com")

        viewModel.sendComposer()
        advanceUntilIdle()

        assertEquals(listOf("https://github.com"), operations.sentLinks)
        assertTrue(operations.sentTexts.isEmpty())
        assertEquals("", viewModel.uiState.value.composerText)
        assertEquals(ComposerMode.TEXT, viewModel.uiState.value.composerMode)
        assertFalse(viewModel.uiState.value.shareSheetVisible)
    }

    @Test
    fun text_composer_mode_routes_to_normal_text_boundary() = runTest(dispatcher) {
        val operations = FakeSessionOperations()
        val viewModel = viewModel(operations)
        viewModel.showShareSheet()
        viewModel.selectTextComposer()
        viewModel.onComposerChanged("hello")

        viewModel.sendComposer()
        advanceUntilIdle()

        assertEquals(listOf("hello"), operations.sentTexts)
        assertTrue(operations.sentLinks.isEmpty())
    }

    @Test
    fun link_mode_rejects_non_http_links_without_submission() = runTest(dispatcher) {
        val operations = FakeSessionOperations()
        val viewModel = viewModel(operations)
        viewModel.selectLinkComposer()
        viewModel.onComposerChanged("github.com")
        val event = async { viewModel.events.first() }

        viewModel.sendComposer()
        advanceUntilIdle()

        assertTrue(operations.sentLinks.isEmpty())
        assertEquals(SessionUserMessage.INVALID_LINK, event.await().message)
    }

    private fun viewModel(
        operations: FakeSessionOperations,
        device: DeviceSummary = device(),
        activities: MutableStateFlow<List<ShareActivity>> = MutableStateFlow(emptyList()),
    ) = SessionViewModel(
        deviceId = deviceId,
        devices = FakeSessionDeviceRepository(device),
        activities = activities,
        previewFor = { null },
        operations = operations,
    )

    private fun device(
        name: String = "Kevin's Windows PC",
        online: Boolean = true,
        trusted: Boolean = true,
        connected: Boolean = false,
    ) = DeviceSummary(
        id = deviceId,
        name = name,
        platform = DevicePlatform.WINDOWS,
        online = online,
        trusted = trusted,
        connected = connected,
    )

    private fun activity(text: String) = ShareActivity(
        id = ActivityKey("activity-1"),
        deviceId = deviceId,
        direction = ShareDirection.INCOMING,
        kind = ContentKind.TEXT,
        origin = ShareOrigin.DIRECT,
        displayName = "Text",
        mimeType = "text/plain",
        byteCount = text.length.toLong(),
        createdAtMillis = 1_800_000_000_000,
        updatedAtMillis = 1_800_000_000_000,
        status = ActivityStatus.COMPLETED,
    )
}

private class FakeSessionOperations : SessionOperations {
    val sentTexts = mutableListOf<String>()
    val sentLinks = mutableListOf<String>()
    val pickedContent = mutableListOf<Pair<Uri, ContentKind>>()
    val cancelled = mutableListOf<ActivityKey>()
    val retried = mutableListOf<ActivityKey>()
    var clipboardReads = 0
    var sendGate: CompletableDeferred<Unit>? = null
    var failure: RuntimeException? = null

    override suspend fun sendText(deviceId: DeviceKey, text: String): ActivityKey {
        failure?.let { throw it }
        sentTexts += text
        sendGate?.await()
        return ActivityKey("sent-${sentTexts.size}")
    }

    override suspend fun sendLink(deviceId: DeviceKey, link: String): ActivityKey {
        failure?.let { throw it }
        sentLinks += link
        sendGate?.await()
        return ActivityKey("link-${sentLinks.size}")
    }

    override suspend fun sendPickedContent(deviceId: DeviceKey, selection: RetainedContentUri, kind: ContentKind): ActivityKey {
        failure?.let { throw it }
        pickedContent += selection.uri to kind
        return ActivityKey("picked-${pickedContent.size}")
    }

    override suspend fun sendClipboard(deviceId: DeviceKey, isForeground: () -> Boolean): ActivityKey? {
        if (!isForeground()) throw com.unishare.app.data.transfer.CoordinatorException(ShareErrorKind.PERMISSION_DENIED)
        clipboardReads++
        failure?.let { throw it }
        return ActivityKey("clipboard")
    }

    override suspend fun cancel(activityId: ActivityKey): Boolean {
        cancelled += activityId
        return true
    }

    override suspend fun retry(activityId: ActivityKey): ActivityKey {
        retried += activityId
        return ActivityKey("retry")
    }
}

private class RecordingSessionContentLease : ContentReadPermissionLease {
    var closeCalls = 0
        private set

    override fun close() {
        closeCalls++
    }
}

private class FakeSessionDeviceRepository(initial: DeviceSummary) : DeviceRepository {
    var connectGate: CompletableDeferred<Unit>? = null
    var connectCalls = 0
    var trustOnConnect = false
    var forgetFailure: RuntimeException? = null
    val forgotten = mutableListOf<DeviceKey>()
    val mutableDevices = MutableStateFlow(listOf(initial))
    override val devices = mutableDevices
    override fun find(deviceId: DeviceKey) = devices.value.firstOrNull { it.id == deviceId }
    override fun isTrusted(deviceId: DeviceKey) = find(deviceId)?.trusted == true
    override fun canSend(deviceId: DeviceKey) = find(deviceId)?.let { (it.online || it.connected) && it.trusted && !it.identityChanged } == true
    override suspend fun connect(deviceId: DeviceKey) {
        connectCalls++
        connectGate?.await()
        mutableDevices.value = mutableDevices.value.map { it.copy(connected = true, trusted = it.trusted || trustOnConnect) }
    }
    override suspend fun forget(deviceId: DeviceKey) {
        forgetFailure?.let { throw it }
        forgotten += deviceId
    }
}
