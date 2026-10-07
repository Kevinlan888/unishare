package com.unishare.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ActivityReducerTest {
    @Test
    fun progress_starts_transferring_without_mutating_the_original() {
        val current = activity()
        val result = ActivityReducer.reduce(current, ActivityEvent.Progress(40, 120))

        assertEquals(ActivityStatus.TRANSFERRING, result.status)
        assertEquals(40L, result.transferredBytes)
        assertEquals(120L, result.updatedAtMillis)
        assertEquals(ActivityStatus.AWAITING_PEER, current.status)
        assertEquals(0L, current.transferredBytes)
    }

    @Test
    fun progress_clamps_negative_bytes_to_zero() {
        assertEquals(0L, ActivityReducer.reduce(activity(), ActivityEvent.Progress(-1, 120)).transferredBytes)
    }

    @Test
    fun progress_clamps_overshoot_to_declared_size() {
        assertEquals(100L, ActivityReducer.reduce(activity(), ActivityEvent.Progress(Long.MAX_VALUE, 120)).transferredBytes)
    }

    @Test
    fun lower_progress_never_moves_bytes_backwards() {
        val current = ActivityReducer.reduce(activity(), ActivityEvent.Progress(80, 140))
        val result = ActivityReducer.reduce(current, ActivityEvent.Progress(20, 150))

        assertEquals(80L, result.transferredBytes)
        assertEquals(150L, result.updatedAtMillis)
    }

    @Test
    fun zero_byte_content_stays_zero_until_explicit_completion() {
        val progress = ActivityReducer.reduce(activity(byteCount = 0), ActivityEvent.Progress(10, 120))
        assertEquals(0L, progress.transferredBytes)
        assertEquals(ActivityStatus.TRANSFERRING, progress.status)

        val result = ActivityReducer.reduce(progress, ActivityEvent.Completed(130))
        assertEquals(ActivityStatus.COMPLETED, result.status)
        assertEquals(0L, result.transferredBytes)
        assertEquals(130L, result.completedAtMillis)
    }

    @Test
    fun full_progress_is_not_a_terminal_result() {
        val result = ActivityReducer.reduce(activity(), ActivityEvent.Progress(100, 120))
        assertEquals(ActivityStatus.TRANSFERRING, result.status)
        assertNull(result.completedAtMillis)
    }

    @Test
    fun completion_without_progress_marks_all_declared_bytes_complete() {
        val result = ActivityReducer.reduce(activity(), ActivityEvent.Completed(120))
        assertEquals(ActivityStatus.COMPLETED, result.status)
        assertEquals(100L, result.transferredBytes)
        assertNull(result.errorKind)
    }

    @Test
    fun duplicate_terminal_result_returns_the_same_snapshot() {
        val event = ActivityEvent.Completed(120)
        val result = ActivityReducer.reduce(activity(), event)
        assertSame(result, ActivityReducer.reduce(result, event))
    }

    @Test
    fun every_terminal_state_ignores_every_later_event() {
        val terminalEvents = listOf(
            ActivityEvent.Completed(120),
            ActivityEvent.Rejected(120),
            ActivityEvent.Cancelled(120),
            ActivityEvent.Failed(ShareErrorKind.NETWORK_LOST, 120),
        )
        val laterEvents = terminalEvents + listOf(
            ActivityEvent.Progress(50, 200),
            ActivityEvent.TransferBound(TransferKey("late-transfer"), 200),
            ActivityEvent.OperationBound(OperationKey("late-operation"), 200),
        )

        for (terminal in terminalEvents) {
            val current = ActivityReducer.reduce(activity(), terminal)
            for (event in laterEvents) {
                assertSame("${current.status} must ignore $event", current, ActivityReducer.reduce(current, event))
            }
        }
    }

    @Test
    fun rejected_and_cancelled_failures_map_to_their_terminal_statuses() {
        val rejected = ActivityReducer.reduce(activity(), ActivityEvent.Failed(ShareErrorKind.TRANSFER_REJECTED, 120))
        val cancelled = ActivityReducer.reduce(activity(), ActivityEvent.Failed(ShareErrorKind.CANCELLED, 120))

        assertEquals(ActivityStatus.REJECTED, rejected.status)
        assertEquals(ShareErrorKind.TRANSFER_REJECTED, rejected.errorKind)
        assertEquals(ActivityStatus.CANCELLED, cancelled.status)
        assertEquals(ShareErrorKind.CANCELLED, cancelled.errorKind)
    }

    @Test
    fun other_errors_remain_classified_failures() {
        for (error in ShareErrorKind.entries.filterNot { it == ShareErrorKind.TRANSFER_REJECTED || it == ShareErrorKind.CANCELLED }) {
            val result = ActivityReducer.reduce(activity(), ActivityEvent.Failed(error, 120))
            assertEquals(ActivityStatus.FAILED, result.status)
            assertEquals(error, result.errorKind)
            assertEquals(120L, result.completedAtMillis)
        }
    }

    @Test
    fun rejection_preserves_partial_bytes_and_records_a_stable_error() {
        val current = ActivityReducer.reduce(activity(), ActivityEvent.Progress(40, 120))
        val result = ActivityReducer.reduce(current, ActivityEvent.Rejected(130))
        assertEquals(ActivityStatus.REJECTED, result.status)
        assertEquals(40L, result.transferredBytes)
        assertEquals(ShareErrorKind.TRANSFER_REJECTED, result.errorKind)
    }

    @Test
    fun cancellation_preserves_partial_bytes_and_records_a_stable_error() {
        val current = ActivityReducer.reduce(activity(), ActivityEvent.Progress(40, 120))
        val result = ActivityReducer.reduce(current, ActivityEvent.Cancelled(130))
        assertEquals(ActivityStatus.CANCELLED, result.status)
        assertEquals(40L, result.transferredBytes)
        assertEquals(ShareErrorKind.CANCELLED, result.errorKind)
    }

    @Test
    fun earlier_progress_timestamp_cannot_rewind_time_or_bytes() {
        val current = ActivityReducer.reduce(activity(), ActivityEvent.Progress(60, 150))
        val result = ActivityReducer.reduce(current, ActivityEvent.Progress(20, 90))
        assertEquals(150L, result.updatedAtMillis)
        assertEquals(60L, result.transferredBytes)
        assertEquals(100L, result.createdAtMillis)
    }

    @Test
    fun earlier_terminal_timestamp_still_finishes_without_rewinding_time() {
        val current = ActivityReducer.reduce(activity(), ActivityEvent.Progress(60, 150))
        val result = ActivityReducer.reduce(current, ActivityEvent.Completed(90))
        assertEquals(ActivityStatus.COMPLETED, result.status)
        assertEquals(150L, result.updatedAtMillis)
        assertEquals(150L, result.completedAtMillis)
        assertEquals(100L, result.createdAtMillis)
    }

    @Test
    fun operation_and_transfer_binding_preserve_progress_and_original_identity() {
        val original = activity()
        val progress = ActivityReducer.reduce(original, ActivityEvent.Progress(60, 150))
        val operation = ActivityReducer.reduce(progress, ActivityEvent.OperationBound(OperationKey("operation-1"), 90))
        val result = ActivityReducer.reduce(operation, ActivityEvent.TransferBound(TransferKey("transfer-1"), 110))

        assertEquals(OperationKey("operation-1"), result.operationId)
        assertEquals(TransferKey("transfer-1"), result.transferId)
        assertEquals(ActivityStatus.TRANSFERRING, result.status)
        assertEquals(60L, result.transferredBytes)
        assertEquals(150L, result.updatedAtMillis)
        assertEquals(original.id, result.id)
        assertEquals(original.deviceId, result.deviceId)
    }

    @Test
    fun duplicate_or_conflicting_binding_cannot_replace_an_existing_identity() {
        val operation = ActivityReducer.reduce(activity(), ActivityEvent.OperationBound(OperationKey("operation-1"), 120))
        val current = ActivityReducer.reduce(operation, ActivityEvent.TransferBound(TransferKey("transfer-1"), 130))

        assertSame(current, ActivityReducer.reduce(current, ActivityEvent.OperationBound(OperationKey("operation-1"), 140)))
        assertSame(current, ActivityReducer.reduce(current, ActivityEvent.OperationBound(OperationKey("operation-2"), 140)))
        assertSame(current, ActivityReducer.reduce(current, ActivityEvent.TransferBound(TransferKey("transfer-1"), 140)))
        assertSame(current, ActivityReducer.reduce(current, ActivityEvent.TransferBound(TransferKey("transfer-2"), 140)))
    }

    @Test
    fun successful_incoming_files_and_images_keep_the_published_uri() {
        for (kind in listOf(ContentKind.FILE, ContentKind.IMAGE)) {
            val current = activity().copy(direction = ShareDirection.INCOMING, kind = kind)
            val result = ActivityReducer.reduce(current, ActivityEvent.Completed(120, "content://downloads/42"))
            assertEquals("content://downloads/42", result.finalContentUri)
        }
    }

    @Test
    fun text_and_outgoing_completion_do_not_retain_a_uri() {
        val incomingText = activity().copy(direction = ShareDirection.INCOMING, kind = ContentKind.TEXT)
        for (current in listOf(incomingText, activity())) {
            val result = ActivityReducer.reduce(current, ActivityEvent.Completed(120, "content://private/42"))
            assertNull(result.finalContentUri)
        }
    }

    private fun activity(byteCount: Long = 100) = ShareActivity(
        id = ActivityKey("activity-1"),
        deviceId = DeviceKey("device-1"),
        direction = ShareDirection.OUTGOING,
        kind = ContentKind.FILE,
        origin = ShareOrigin.DIRECT,
        displayName = "example.bin",
        mimeType = "application/octet-stream",
        byteCount = byteCount,
        createdAtMillis = 100,
        updatedAtMillis = 100,
    )
}
