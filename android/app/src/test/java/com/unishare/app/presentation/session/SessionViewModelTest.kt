package com.unishare.app.presentation.session

import android.net.Uri
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

        viewModel.sendClipboard(activityResumed = false)
        advanceUntilIdle()

        assertEquals(0, operations.clipboardReads)
        assertEquals(SessionUserMessage.CLIPBOARD_REQUIRES_FOREGROUND, event.await().message)
    }

    @Test
    fun link_uses_the_same_text_transfer_boundary() = runTest(dispatcher) {
        val operations = FakeSessionOperations()
        val viewModel = viewModel(operations)

        viewModel.sendLink("https://github.com")
        advanceUntilIdle()

        assertEquals(listOf("https://github.com"), operations.sentTexts)
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
    ) = DeviceSummary(
        id = deviceId,
        name = name,
        platform = DevicePlatform.WINDOWS,
        online = online,
        trusted = trusted,
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

    override suspend fun sendPickedContent(deviceId: DeviceKey, uri: Uri, kind: ContentKind): ActivityKey {
        failure?.let { throw it }
        pickedContent += uri to kind
        return ActivityKey("picked-${pickedContent.size}")
    }

    override suspend fun sendClipboard(deviceId: DeviceKey): ActivityKey? {
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

private class FakeSessionDeviceRepository(initial: DeviceSummary) : DeviceRepository {
    val mutableDevices = MutableStateFlow(listOf(initial))
    override val devices = mutableDevices
    override fun find(deviceId: DeviceKey) = devices.value.firstOrNull { it.id == deviceId }
    override fun isTrusted(deviceId: DeviceKey) = find(deviceId)?.trusted == true
    override fun canSend(deviceId: DeviceKey) = find(deviceId)?.let { it.online && it.trusted && !it.identityChanged } == true
    override suspend fun connect(deviceId: DeviceKey) = Unit
    override suspend fun forget(deviceId: DeviceKey) = Unit
}
