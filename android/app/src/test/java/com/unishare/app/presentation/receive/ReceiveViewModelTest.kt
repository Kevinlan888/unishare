package com.unishare.app.presentation.receive

import com.unishare.app.data.device.DeviceRepository
import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.data.sdk.IncomingOffer
import com.unishare.app.data.settings.AppSettings
import com.unishare.app.data.settings.SettingsRepository
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ActivityStatus
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareActivity
import com.unishare.app.domain.ShareDirection
import com.unishare.app.domain.ShareErrorKind
import com.unishare.app.domain.ShareOrigin
import com.unishare.app.domain.TransferKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveViewModelTest {
    @Test
    fun pending_activity_key_resolves_offer_metadata_device_and_persisted_destination() = runTest {
        val fixture = fixture(immediateBackgroundScope(), destination = "Pictures / UniShare")

        val state = fixture.viewModel.uiState.value

        assertEquals(ReceivePhase.PENDING, state.phase)
        assertEquals("IMG_3281.jpg", state.displayName)
        assertEquals("image/jpeg", state.mimeType)
        assertEquals(8_400_000, state.byteCount)
        assertEquals("Kevin's Windows PC", state.source.name)
        assertEquals(DevicePlatform.WINDOWS, state.source.platform)
        assertTrue(state.source.online)
        assertEquals("Pictures / UniShare", state.destinationDisplayName)
    }

    @Test
    fun destination_correction_clears_an_unsupported_tree_and_returns_to_downloads() = runTest {
        val fixture = fixture(immediateBackgroundScope(), destination = "Pictures / UniShare")

        fixture.viewModel.restoreDownloadsDestination()
        advanceUntilIdle()

        assertNull(fixture.settings.current.receiveTreeUri)
        assertNull(fixture.settings.current.receiveDirectoryDisplayName)
        assertNull(fixture.viewModel.uiState.value.destinationDisplayName)
        assertTrue(fixture.viewModel.uiState.value.showUnsupportedDestinationNotice)
        assertEquals(listOf(null to null), fixture.settings.directoryWrites)
    }

    @Test
    fun retry_waits_for_downloads_restore_before_using_publication_recovery() = runTest {
        val writeGate = CompletableDeferred<Unit>()
        val fixture = fixture(
            immediateBackgroundScope(),
            destination = "Pictures / UniShare",
            settingsWriteGate = writeGate,
        )
        fixture.viewModel.accept()
        advanceUntilIdle()
        fixture.operations.publish(
            activity(status = ActivityStatus.FAILED, error = ShareErrorKind.STORAGE_FAILED),
        )
        advanceUntilIdle()

        fixture.viewModel.restoreDownloadsDestination()
        fixture.viewModel.retryPublication()
        runCurrent()

        assertFalse(fixture.operations.calls.any { it.startsWith("retry:") })
        writeGate.complete(Unit)
        advanceUntilIdle()

        assertNull(fixture.settings.current.receiveTreeUri)
        assertEquals("retry:activity-1", fixture.operations.calls.last())
    }

    @Test
    fun expired_or_non_pending_deep_link_never_calls_accept_or_reject() = runTest {
        val operations = FakeReceiveOperations(pending = null)
        val viewModel = ReceiveViewModel(
            ACTIVITY_ID,
            operations,
            FakeReceiveDevices(),
            FakeSettingsRepository(),
            FinalContentReader { true },
            immediateBackgroundScope(),
        )

        viewModel.accept()
        viewModel.reject()
        advanceUntilIdle()

        assertEquals(ReceivePhase.EXPIRED, viewModel.uiState.value.phase)
        assertTrue(operations.calls.isEmpty())
    }

    @Test
    fun first_accept_or_reject_action_wins_and_disables_the_other() = runTest {
        val fixture = fixture(immediateBackgroundScope())

        fixture.viewModel.accept()
        fixture.viewModel.reject()
        assertFalse(fixture.viewModel.uiState.value.actionsEnabled)
        advanceUntilIdle()

        assertEquals(listOf("accept:activity-1"), fixture.operations.calls)
        assertEquals(ReceivePhase.RECEIVING, fixture.viewModel.uiState.value.phase)
    }

    @Test
    fun failed_accept_resolution_becomes_expired_instead_of_retrying_a_stale_request() = runTest {
        val fixture = fixture(immediateBackgroundScope())
        fixture.operations.acceptResult = false

        fixture.viewModel.accept()
        advanceUntilIdle()

        assertEquals(ReceivePhase.EXPIRED, fixture.viewModel.uiState.value.phase)
        fixture.viewModel.reject()
        advanceUntilIdle()
        assertEquals(listOf("accept:activity-1"), fixture.operations.calls)
    }

    @Test
    fun reject_command_failure_restores_pending_actions_for_the_still_live_offer() = runTest {
        val fixture = fixture(immediateBackgroundScope())
        fixture.operations.rejectFailure = IllegalStateException("peer unavailable")

        fixture.viewModel.reject()
        advanceUntilIdle()

        assertEquals(ReceivePhase.PENDING, fixture.viewModel.uiState.value.phase)
        assertTrue(fixture.viewModel.uiState.value.actionsEnabled)
        assertEquals(listOf("reject:activity-1"), fixture.operations.calls)
    }

    @Test
    fun storage_failure_exposes_directory_recovery_and_only_readable_final_uri_can_open() = runTest {
        val fixture = fixture(immediateBackgroundScope(), readable = true)
        fixture.viewModel.accept()
        advanceUntilIdle()

        fixture.operations.publish(
            activity(status = ActivityStatus.FAILED, error = ShareErrorKind.STORAGE_FAILED),
        )
        advanceUntilIdle()
        assertEquals(ReceivePhase.DIRECTORY_ERROR, fixture.viewModel.uiState.value.phase)
        assertTrue(fixture.viewModel.uiState.value.showDestinationRecovery)
        assertNull(fixture.viewModel.openFinalContent())

        val retryId = ActivityKey("retry-1")
        fixture.operations.retryResult = retryId
        fixture.viewModel.retryPublication()
        advanceUntilIdle()
        assertEquals("retry:activity-1", fixture.operations.calls.last())

        fixture.operations.publish(
            activity(
                id = retryId,
                status = ActivityStatus.COMPLETED,
                finalUri = "content://downloads/final-image",
            ),
        )
        advanceUntilIdle()

        assertEquals(ReceivePhase.COMPLETED, fixture.viewModel.uiState.value.phase)
        assertTrue(fixture.viewModel.uiState.value.canOpen)
        assertEquals("content://downloads/final-image", fixture.viewModel.openFinalContent())
    }

    @Test
    fun storage_failure_without_retained_publication_does_not_offer_a_broken_retry() = runTest {
        val fixture = fixture(immediateBackgroundScope())
        fixture.operations.publicationRecovery = false
        fixture.viewModel.accept()
        advanceUntilIdle()

        fixture.operations.publish(
            activity(status = ActivityStatus.FAILED, error = ShareErrorKind.STORAGE_FAILED),
        )
        advanceUntilIdle()

        assertEquals(ReceivePhase.FAILED, fixture.viewModel.uiState.value.phase)
        assertFalse(fixture.viewModel.uiState.value.showDestinationRecovery)
    }

    @Test
    fun repeated_publication_retry_uses_the_latest_failed_retry_activity() = runTest {
        val fixture = fixture(immediateBackgroundScope())
        fixture.viewModel.accept()
        advanceUntilIdle()
        fixture.operations.publish(
            activity(status = ActivityStatus.FAILED, error = ShareErrorKind.STORAGE_FAILED),
        )
        advanceUntilIdle()

        val firstRetry = ActivityKey("retry-1")
        fixture.operations.retryResult = firstRetry
        fixture.viewModel.retryPublication()
        advanceUntilIdle()
        fixture.operations.publish(
            activity(
                id = firstRetry,
                status = ActivityStatus.FAILED,
                error = ShareErrorKind.STORAGE_FAILED,
            ),
        )
        advanceUntilIdle()

        val secondRetry = ActivityKey("retry-2")
        fixture.operations.retryResult = secondRetry
        fixture.viewModel.retryPublication()
        advanceUntilIdle()

        assertEquals("retry:retry-1", fixture.operations.calls.last())
        fixture.operations.publish(
            activity(
                id = secondRetry,
                status = ActivityStatus.COMPLETED,
                finalUri = "content://downloads/third-attempt",
            ),
        )
        advanceUntilIdle()

        assertEquals(ReceivePhase.COMPLETED, fixture.viewModel.uiState.value.phase)
        assertEquals("content://downloads/third-attempt", fixture.viewModel.openFinalContent())
    }

    @Test
    fun expired_publication_recovery_becomes_terminal_instead_of_looping_retry() = runTest {
        val fixture = fixture(immediateBackgroundScope())
        fixture.viewModel.accept()
        advanceUntilIdle()
        fixture.operations.publish(
            activity(status = ActivityStatus.FAILED, error = ShareErrorKind.STORAGE_FAILED),
        )
        advanceUntilIdle()
        assertEquals(ReceivePhase.DIRECTORY_ERROR, fixture.viewModel.uiState.value.phase)

        fixture.operations.retryFailure = IllegalStateException("recovery expired")
        fixture.operations.publicationRecovery = false
        fixture.viewModel.retryPublication()
        advanceUntilIdle()

        assertEquals(ReceivePhase.FAILED, fixture.viewModel.uiState.value.phase)
        assertFalse(fixture.viewModel.uiState.value.showDestinationRecovery)
    }

    @Test
    fun unreadable_or_staging_uri_is_never_offered_for_opening() = runTest {
        val fixture = fixture(immediateBackgroundScope(), readable = false)
        fixture.viewModel.accept()
        advanceUntilIdle()
        fixture.operations.publish(
            activity(
                status = ActivityStatus.COMPLETED,
                finalUri = "content://com.unishare.app.staging/private/transfer",
            ),
        )
        advanceUntilIdle()

        assertEquals(ReceivePhase.COMPLETED, fixture.viewModel.uiState.value.phase)
        assertFalse(fixture.viewModel.uiState.value.canOpen)
        assertNull(fixture.viewModel.openFinalContent())
    }

    private fun fixture(
        scope: CoroutineScope,
        destination: String? = null,
        readable: Boolean = true,
        settingsWriteGate: CompletableDeferred<Unit>? = null,
    ): Fixture {
        val offer = offer()
        val operations = FakeReceiveOperations(offer)
        val devices = FakeReceiveDevices(
            DeviceSummary(
                id = offer.deviceId,
                name = "Kevin's Windows PC",
                platform = DevicePlatform.WINDOWS,
                online = true,
                trusted = true,
            ),
        )
        val settings = FakeSettingsRepository(
            AppSettings(
                receiveTreeUri = destination?.let { "content://destination/tree" },
                receiveDirectoryDisplayName = destination,
            ),
            settingsWriteGate,
        )
        val viewModel = ReceiveViewModel(
            ACTIVITY_ID,
            operations,
            devices,
            settings,
            FinalContentReader { readable },
            scope,
        )
        return Fixture(viewModel, operations, settings)
    }

    private data class Fixture(
        val viewModel: ReceiveViewModel,
        val operations: FakeReceiveOperations,
        val settings: FakeSettingsRepository,
    )

    companion object {
        private val ACTIVITY_ID = ActivityKey("activity-1")
        private val DEVICE_ID = DeviceKey("pc-kevin")
        private val TRANSFER_ID = TransferKey("transfer-1")

        private fun offer() = IncomingOffer(
            requestId = 77,
            transferId = TRANSFER_ID,
            deviceId = DEVICE_ID,
            kind = ContentKind.IMAGE,
            displayName = "IMG_3281.jpg",
            mimeType = "image/jpeg",
            byteCount = 8_400_000,
        )

        private fun activity(
            id: ActivityKey = ACTIVITY_ID,
            status: ActivityStatus,
            error: ShareErrorKind? = null,
            finalUri: String? = null,
        ) = ShareActivity(
            id = id,
            deviceId = DEVICE_ID,
            direction = ShareDirection.INCOMING,
            kind = ContentKind.IMAGE,
            origin = ShareOrigin.DIRECT,
            displayName = "IMG_3281.jpg",
            mimeType = "image/jpeg",
            byteCount = 8_400_000,
            createdAtMillis = 1,
            updatedAtMillis = 2,
            transferId = TRANSFER_ID,
            status = status,
            errorKind = error,
            finalContentUri = finalUri,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.immediateBackgroundScope(): CoroutineScope =
    CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

private class FakeReceiveOperations(
    private var pending: IncomingOffer?,
) : ReceiveOperations {
    private val activities = MutableStateFlow<List<ShareActivity>>(emptyList())
    val calls = mutableListOf<String>()
    var acceptResult = true
    var rejectResult = true
    var rejectFailure: Exception? = null
    var retryResult = ActivityKey("retry")
    var publicationRecovery = true
    var retryFailure: Exception? = null

    override fun pendingOffer(activityId: ActivityKey) = pending
    override fun hasPublicationRecovery(activityId: ActivityKey) = publicationRecovery
    override fun observeActivities(deviceId: DeviceKey): Flow<List<ShareActivity>> = activities
    override fun findActivity(activityId: ActivityKey) = activities.value.firstOrNull { it.id == activityId }
    override suspend fun accept(activityId: ActivityKey): Boolean {
        calls += "accept:${activityId.value}"
        if (acceptResult) pending = null
        return acceptResult
    }
    override suspend fun reject(activityId: ActivityKey): Boolean {
        calls += "reject:${activityId.value}"
        rejectFailure?.let { throw it }
        if (rejectResult) pending = null
        return rejectResult
    }
    override suspend fun retry(activityId: ActivityKey): ActivityKey {
        calls += "retry:${activityId.value}"
        retryFailure?.let { throw it }
        return retryResult
    }
    fun publish(activity: ShareActivity) { activities.value = listOf(activity) }
}

private class FakeReceiveDevices(vararg summaries: DeviceSummary) : DeviceRepository {
    override val devices = MutableStateFlow(summaries.toList())
    override fun find(deviceId: DeviceKey) = devices.value.firstOrNull { it.id == deviceId }
    override fun isTrusted(deviceId: DeviceKey) = find(deviceId)?.trusted == true
    override fun canSend(deviceId: DeviceKey) = false
    override suspend fun connect(deviceId: DeviceKey) = Unit
    override suspend fun forget(deviceId: DeviceKey) = Unit
}

private class FakeSettingsRepository(
    initial: AppSettings = AppSettings(),
    private val directoryWriteGate: CompletableDeferred<Unit>? = null,
) : SettingsRepository {
    private val mutable = MutableStateFlow(initial)
    val current: AppSettings get() = mutable.value
    val directoryWrites = mutableListOf<Pair<String?, String?>>()
    override val settings: Flow<AppSettings> = mutable
    override suspend fun setDeviceName(name: String) = Unit
    override suspend fun setReceiveDirectory(treeUri: String?, displayName: String?) {
        directoryWriteGate?.await()
        directoryWrites += treeUri to displayName
        mutable.value = mutable.value.copy(
            receiveTreeUri = treeUri,
            receiveDirectoryDisplayName = displayName,
        )
    }
    override suspend fun setBackgroundReceiveEnabled(enabled: Boolean) = Unit
    override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun setLanguage(language: com.unishare.app.data.settings.AppLanguage) = Unit
    override suspend fun setTheme(theme: com.unishare.app.data.settings.AppTheme) = Unit
}
