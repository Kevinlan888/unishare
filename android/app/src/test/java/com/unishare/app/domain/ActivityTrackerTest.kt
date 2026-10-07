package com.unishare.app.domain

import com.unishare.app.data.history.ActivityRepository
import com.unishare.app.data.sdk.IncomingOffer
import com.unishare.app.data.sdk.ShareFailure
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityTrackerTest {
    @Test
    fun early_transfer_registration_is_replayed_when_operation_is_bound() = runTest {
        val repository = FakeActivityRepository()
        val tracker = tracker(repository)
        val activity = activity()

        tracker.reserve(activity)
        tracker.bindTransfer(OperationKey("operation-1"), TransferKey("transfer-1"), 110)
        tracker.bindOperation(activity.id, OperationKey("operation-1"), 120)
        runCurrent()

        assertEquals(TransferKey("transfer-1"), tracker.find(activity.id)?.transferId)
    }

    @Test
    fun progress_before_transfer_binding_is_replayed_without_losing_bytes() = runTest {
        val tracker = tracker(FakeActivityRepository())
        val activity = activity()
        tracker.reserve(activity)

        tracker.progress(TransferKey("transfer-1"), 64, 120)
        tracker.bindOperation(activity.id, OperationKey("operation-1"), 130)
        tracker.bindTransfer(OperationKey("operation-1"), TransferKey("transfer-1"), 140)
        runCurrent()

        val result = tracker.find(activity.id)!!
        assertEquals(ActivityStatus.TRANSFERRING, result.status)
        assertEquals(64L, result.transferredBytes)
    }

    @Test
    fun progress_before_incoming_offer_is_replayed_when_offer_registers_transfer() = runTest {
        val repository = FakeActivityRepository()
        val tracker = tracker(repository)
        val offer = offer()

        tracker.progress(offer.transferId, 55, 90)
        val activityId = tracker.registerIncoming(offer, 100)
        runCurrent()

        assertEquals(ActivityStatus.TRANSFERRING, tracker.find(activityId)?.status)
        assertEquals(55L, tracker.find(activityId)?.transferredBytes)
        assertEquals(
            listOf(ActivityStatus.AWAITING_PEER, ActivityStatus.TRANSFERRING),
            repository.upserts.map { it.status },
        )
    }

    @Test
    fun duplicate_incoming_offer_reuses_one_activity() = runTest {
        val tracker = tracker(FakeActivityRepository())
        val offer = offer()

        val first = tracker.registerIncoming(offer, 100)
        val second = tracker.registerIncoming(offer, 200)
        runCurrent()

        assertEquals(first, second)
        assertEquals(1, tracker.activities(offer.deviceId).value.size)
    }

    @Test
    fun late_history_collision_cannot_replace_a_live_transfer() = runTest {
        val repository = FakeActivityRepository(stateful = false)
        val tracker = tracker(repository)
        val live = activity().copy(transferId = TransferKey("transfer-1"), transferredBytes = 70, status = ActivityStatus.TRANSFERRING)
        tracker.reserve(live)

        repository.emitHistory(
            live.deviceId,
            listOf(activity(id = "loaded-id").copy(transferId = TransferKey("transfer-1"), transferredBytes = 10)),
        )
        runCurrent()

        assertEquals(listOf(live.id), tracker.activities(live.deviceId).value.map { it.id })
        assertSame(live, tracker.find(live.id))
    }

    @Test
    fun history_removal_clears_transfer_index_before_a_new_offer_reuses_the_id() = runTest {
        val repository = FakeActivityRepository()
        val tracker = tracker(repository)
        val loaded = activity(id = "loaded-id").copy(transferId = TransferKey("transfer-in"))
        tracker.activities(loaded.deviceId)
        repository.emitHistory(loaded.deviceId, listOf(loaded))
        runCurrent()
        repository.emitHistory(loaded.deviceId, emptyList())
        runCurrent()

        val incoming = tracker.registerIncoming(offer(), 200)
        runCurrent()

        assertEquals(ActivityKey("generated-1"), incoming)
        assertEquals(incoming, tracker.find(incoming)?.id)
    }

    @Test
    fun duplicate_terminal_and_later_progress_leave_the_first_terminal_snapshot_unchanged() = runTest {
        val tracker = tracker(FakeActivityRepository())
        val activity = activity().copy(transferId = TransferKey("transfer-1"))
        tracker.reserve(activity)

        tracker.terminal(TransferKey("transfer-1"), null, 120)
        val terminal = tracker.find(activity.id)
        tracker.terminal(TransferKey("transfer-1"), ShareFailure(ShareErrorKind.NETWORK_LOST), 130)
        tracker.progress(TransferKey("transfer-1"), 1, 140)
        runCurrent()

        assertSame(terminal, tracker.find(activity.id))
        assertEquals(ActivityStatus.COMPLETED, terminal?.status)
    }

    @Test
    fun manual_retry_creates_a_new_activity_linked_to_the_failed_one() = runTest {
        val tracker = tracker(FakeActivityRepository(), ids = ArrayDeque(listOf("retry-id")))
        val failed = activity().copy(
            status = ActivityStatus.FAILED,
            completedAtMillis = 120,
            errorKind = ShareErrorKind.NETWORK_LOST,
        )
        tracker.reserve(failed)

        val retry = tracker.reserveRetry(failed.id, 200)
        runCurrent()

        assertEquals(ActivityKey("retry-id"), retry)
        assertEquals(failed.id, tracker.find(retry)?.retryOf)
        assertEquals(ActivityStatus.AWAITING_PEER, tracker.find(retry)?.status)
        assertNull(tracker.find(retry)?.transferId)
    }

    @Test
    fun persistence_waits_for_history_hydration_and_preserves_write_order() = runTest {
        val repository = FakeActivityRepository(stateful = false)
        val tracker = tracker(repository)
        val activity = activity()

        tracker.reserve(activity)
        tracker.bindOperation(activity.id, OperationKey("operation-1"), 120)
        runCurrent()
        assertEquals(emptyList<ShareActivity>(), repository.upserts)

        repository.emitHistory(activity.deviceId, emptyList())
        runCurrent()

        assertEquals(listOf(null, OperationKey("operation-1")), repository.upserts.map { it.operationId })
    }

    @Test
    fun persisted_summaries_strip_path_segments_and_control_characters() = runTest {
        val repository = FakeActivityRepository()
        val tracker = tracker(repository)
        tracker.reserve(activity().copy(displayName = "C:\\secret\\bad\u0000name.bin", mimeType = "application/octet-stream\u0000"))
        runCurrent()

        assertEquals("badname.bin", repository.upserts.single().displayName)
        assertEquals("application/octet-stream", repository.upserts.single().mimeType)
    }

    private fun TestScope.tracker(repository: FakeActivityRepository, ids: ArrayDeque<String> = ArrayDeque()) =
        ActivityTracker(repository, backgroundScope, idFactory = {
            ActivityKey(if (ids.isEmpty()) "generated-${repository.generated++}" else ids.removeFirst())
        })

    private fun activity(id: String = "activity-1") = ShareActivity(
        id = ActivityKey(id),
        deviceId = DeviceKey("device-1"),
        direction = ShareDirection.OUTGOING,
        kind = ContentKind.FILE,
        origin = ShareOrigin.DIRECT,
        displayName = "example.bin",
        mimeType = "application/octet-stream",
        byteCount = 100,
        createdAtMillis = 100,
        updatedAtMillis = 100,
    )

    private fun offer() = IncomingOffer(
        requestId = 7,
        transferId = TransferKey("transfer-in"),
        deviceId = DeviceKey("device-1"),
        kind = ContentKind.FILE,
        displayName = "incoming.bin",
        mimeType = "application/octet-stream",
        byteCount = 100,
    )
}

private class FakeActivityRepository(private val stateful: Boolean = true) : ActivityRepository {
    private val stateFlows = mutableMapOf<DeviceKey, MutableStateFlow<List<ShareActivity>>>()
    private val sharedFlows = mutableMapOf<DeviceKey, MutableSharedFlow<List<ShareActivity>>>()
    val upserts = CopyOnWriteArrayList<ShareActivity>()
    var generated = 1

    override fun observeRecent(limit: Int): Flow<List<ShareActivity>> = MutableStateFlow(emptyList())
    override fun observeDevice(device: DeviceKey): Flow<List<ShareActivity>> =
        if (stateful) stateFlows.getOrPut(device) { MutableStateFlow(emptyList()) }
        else sharedFlows.getOrPut(device) { MutableSharedFlow(extraBufferCapacity = 1) }

    override suspend fun upsert(activity: ShareActivity) {
        upserts += activity
    }

    override suspend fun markNonTerminalInterrupted(nowMillis: Long) = Unit
    override suspend fun prune(nowMillis: Long) = Unit

    suspend fun emitHistory(device: DeviceKey, activities: List<ShareActivity>) {
        if (stateful) stateFlows.getOrPut(device) { MutableStateFlow(emptyList()) }.emit(activities)
        else sharedFlows.getOrPut(device) { MutableSharedFlow(extraBufferCapacity = 1) }.emit(activities)
    }
}
