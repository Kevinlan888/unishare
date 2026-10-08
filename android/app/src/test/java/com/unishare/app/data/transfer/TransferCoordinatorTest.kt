package com.unishare.app.data.transfer

import android.net.Uri
import com.unishare.app.data.content.PreparedStaging
import com.unishare.app.data.device.DeviceRepository
import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.data.history.ActivityRepository
import com.unishare.app.data.sdk.*
import com.unishare.app.data.settings.*
import com.unishare.app.domain.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class TransferCoordinatorTest {
    @Test
    fun text_photo_and_file_use_the_matching_sdk_submission() = runTest {
        val fixture = fixture()

        fixture.coordinator.sendText(device, "hello")
        fixture.coordinator.sendContent(device, selected(ContentKind.IMAGE, "image/jpeg", "photo.jpg"))
        fixture.coordinator.sendContent(device, selected(ContentKind.FILE, "application/pdf", "report.pdf"))

        assertEquals(listOf("text:hello", "image:photo.jpg:image/jpeg:9", "file:report.pdf:application/pdf:9"), fixture.gateway.submissions)
        assertEquals(3, fixture.coordinator.activeTransferCount.value)
    }

    @Test
    fun outgoing_image_keeps_only_a_process_preview_reference_for_the_session_ui() = runTest {
        val fixture = fixture()
        val activityId = fixture.coordinator.sendContent(
            device,
            selected(ContentKind.IMAGE, "image/jpeg", "photo.jpg"),
        )

        val preview = fixture.tracker.preview(activityId) as ActivityPreview.Image

        assertEquals("content://test/content", preview.reference)
    }

    @Test
    fun picked_uri_lease_survives_async_open_and_failure_then_transfers_to_retry_until_success() = runTest {
        val fixture = fixture()
        val lease = RecordingContentReadPermissionLease()
        val original = fixture.coordinator.sendContent(
            device,
            selected(ContentKind.IMAGE, "image/jpeg", "photo.jpg").copy(readPermission = lease),
        )

        assertFalse("SDK stream acquisition is asynchronous, so submission must keep the grant", lease.closed)

        fixture.gateway.completeNext(
            TransferTerminal(TransferKey("failed-transfer"), ShareFailure(ShareErrorKind.NETWORK_LOST)),
        )
        runCurrent()
        assertFalse("a retryable failure must keep the grant", lease.closed)

        val retry = fixture.coordinator.retry(original)
        assertEquals(original, fixture.tracker.find(retry)?.retryOf)
        assertFalse("ownership moves to the retry", lease.closed)

        fixture.gateway.completeNext(TransferTerminal(TransferKey("successful-retry")))
        runCurrent()

        assertTrue("the successful terminal result releases the grant", lease.closed)
        assertEquals(1, lease.closeCalls)
    }

    @Test
    fun rejected_outgoing_content_releases_its_lease_and_cannot_retry() = runTest {
        val fixture = fixture()
        val lease = RecordingContentReadPermissionLease()
        val activityId = fixture.coordinator.sendContent(
            device,
            selected(ContentKind.FILE, "application/pdf", "report.pdf").copy(readPermission = lease),
        )

        fixture.gateway.completeNext(
            TransferTerminal(TransferKey("rejected-transfer"), ShareFailure(ShareErrorKind.TRANSFER_REJECTED)),
        )
        runCurrent()

        assertEquals(ActivityStatus.REJECTED, fixture.tracker.find(activityId)?.status)
        assertEquals(1, lease.closeCalls)
        val retryFailure = runCatching { fixture.coordinator.retry(activityId) }.exceptionOrNull()
        assertEquals(ShareErrorKind.INVALID_INPUT, (retryFailure as CoordinatorException).errorKind)
    }

    @Test
    fun cancelled_outgoing_content_releases_its_lease_and_cannot_retry() = runTest {
        val fixture = fixture()
        val lease = RecordingContentReadPermissionLease()
        val activityId = fixture.coordinator.sendContent(
            device,
            selected(ContentKind.IMAGE, "image/jpeg", "photo.jpg").copy(readPermission = lease),
        )

        fixture.gateway.completeNext(
            TransferTerminal(TransferKey("cancelled-transfer"), ShareFailure(ShareErrorKind.CANCELLED)),
        )
        runCurrent()

        assertEquals(ActivityStatus.CANCELLED, fixture.tracker.find(activityId)?.status)
        assertEquals(1, lease.closeCalls)
        val retryFailure = runCatching { fixture.coordinator.retry(activityId) }.exceptionOrNull()
        assertEquals(ShareErrorKind.INVALID_INPUT, (retryFailure as CoordinatorException).errorKind)
    }

    @Test
    fun coordinator_retirement_releases_a_retryable_uri_lease_exactly_once() = runTest {
        val fixture = fixture()
        val lease = RecordingContentReadPermissionLease()
        fixture.coordinator.sendContent(
            device,
            selected(ContentKind.FILE, "application/pdf", "report.pdf").copy(readPermission = lease),
        )
        fixture.gateway.completeNext(
            TransferTerminal(TransferKey("failed-transfer"), ShareFailure(ShareErrorKind.NETWORK_LOST)),
        )
        runCurrent()

        fixture.coordinator.retire()

        assertTrue(lease.closed)
        assertEquals(1, lease.closeCalls)
    }

    @Test
    fun caller_cancellation_cannot_revoke_uri_before_app_owned_async_submission() = runTest {
        val workerScheduler = TestCoroutineScheduler()
        val workerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(workerScheduler))
        val fixture = fixture(workerScope = workerScope)
        val lease = RecordingContentReadPermissionLease()

        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.sendContent(
                device,
                selected(ContentKind.IMAGE, "image/jpeg", "photo.jpg").copy(readPermission = lease),
            )
        }
        caller.cancel()
        runCurrent()

        assertFalse("ownership was transferred before the app-owned job was scheduled", lease.closed)
        workerScheduler.runCurrent()
        fixture.gateway.completeNext(TransferTerminal(TransferKey("completed-after-caller-cancel")))
        workerScheduler.runCurrent()
        assertTrue(lease.closed)
        assertEquals(1, lease.closeCalls)
        workerScope.cancel()
    }

    @Test
    fun concurrent_retries_atomically_claim_one_content_payload_and_release_it_once() = runTest {
        val retryBarrier = CyclicBarrier(2)
        val synchronizeRetries = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)
        val workerDispatcher = executor.asCoroutineDispatcher()
        val workerScope = CoroutineScope(SupervisorJob() + workerDispatcher)
        val fixture = fixture(
            workerScope = workerScope,
            onCanSend = {
                if (synchronizeRetries.get()) retryBarrier.await(5, TimeUnit.SECONDS)
            },
        )
        val lease = RecordingContentReadPermissionLease()
        fixture.gateway.beginFailure = GatewayException(ShareFailure(ShareErrorKind.NETWORK_LOST))
        runCatching {
            fixture.coordinator.sendContent(
                device,
                selected(ContentKind.FILE, "application/pdf", "report.pdf").copy(readPermission = lease),
            )
        }
        val original = fixture.tracker.activities(device).value.single()
        fixture.gateway.beginFailure = null
        synchronizeRetries.set(true)

        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.coordinator.retry(original.id) } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.coordinator.retry(original.id) } }
        val outcomes = listOf(first.await(), second.await())

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { (it.exceptionOrNull() as? CoordinatorException)?.errorKind == ShareErrorKind.INVALID_INPUT })
        assertEquals(1, fixture.gateway.submissions.size)
        assertFalse(lease.closed)

        fixture.gateway.completeNext(TransferTerminal(TransferKey("single-retry-success")))
        assertTrue("terminal completion should close the claimed lease", lease.awaitClosed())
        assertEquals(1, lease.closeCalls)
        workerScope.cancel()
        workerDispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun failed_retry_submission_retains_one_reachable_payload_until_a_later_success() = runTest {
        val fixture = fixture()
        val lease = RecordingContentReadPermissionLease()
        fixture.gateway.beginFailure = GatewayException(ShareFailure(ShareErrorKind.NETWORK_LOST))
        runCatching {
            fixture.coordinator.sendContent(
                device,
                selected(ContentKind.IMAGE, "image/jpeg", "photo.jpg").copy(readPermission = lease),
            )
        }
        val original = fixture.tracker.activities(device).value.single()

        val retryFailure = runCatching { fixture.coordinator.retry(original.id) }.exceptionOrNull()

        assertEquals(ShareErrorKind.NETWORK_LOST, (retryFailure as CoordinatorException).errorKind)
        assertFalse(lease.closed)
        val retainedRetry = fixture.tracker.activities(device).value.single { it.retryOf == original.id }
        assertEquals(ActivityStatus.FAILED, retainedRetry.status)

        fixture.gateway.beginFailure = null
        fixture.coordinator.retry(retainedRetry.id)
        fixture.gateway.completeNext(TransferTerminal(TransferKey("eventual-success")))
        runCurrent()

        assertEquals(1, lease.closeCalls)
    }

    @Test
    fun clipboard_text_and_image_submit_only_after_explicit_read() = runTest {
        val fixture = fixture()
        fixture.gateway.clipboard = ClipboardContent.Text("clip text")
        assertNotNull(fixture.coordinator.sendClipboard(device) { true })
        fixture.gateway.clipboard = ClipboardContent.Image("image/png", byteArrayOf(1, 2, 3))
        assertNotNull(fixture.coordinator.sendClipboard(device) { true })

        assertEquals(listOf("clipboard", "text:clip text", "clipboard", "image:clipboard.png:image/png:3"), fixture.gateway.calls.filter {
            it == "clipboard" || it.startsWith("text:") || it.startsWith("image:")
        })
    }

    @Test
    fun unsupported_clipboard_creates_no_activity() = runTest {
        val fixture = fixture()
        fixture.gateway.clipboard = null

        assertNull(fixture.coordinator.sendClipboard(device) { true })
        assertTrue(fixture.tracker.activities(device).value.isEmpty())
    }

    @Test
    fun pause_after_click_but_before_coordinator_read_prevents_clipboard_access() = runTest {
        val fixture = fixture()
        var resumed = true

        val submission = async {
            runCatching { fixture.coordinator.sendClipboard(device) { resumed } }
        }
        resumed = false
        runCurrent()

        val failure = submission.await().exceptionOrNull() as? CoordinatorException
            ?: throw AssertionError("Expected foreground denial")
        assertEquals(ShareErrorKind.PERMISSION_DENIED, failure.errorKind)
        assertFalse("clipboard must not be read after pause", "clipboard" in fixture.gateway.calls)
    }

    @Test
    fun trusted_text_auto_accepts_but_every_image_and_file_waits_for_prompt() = runTest {
        val fixture = fixture(trusted = true)
        fixture.staging.textToWrite = "received text"

        fixture.gateway.emit(offer(ContentKind.TEXT, "text/plain", "Text", "text-transfer"))
        fixture.gateway.emit(offer(ContentKind.IMAGE, "image/png", "photo.png", "image-transfer"))
        fixture.gateway.emit(offer(ContentKind.FILE, "application/pdf", "report.pdf", "file-transfer"))
        runCurrent()

        assertEquals(1, fixture.gateway.accepted.size)
        assertEquals(2, fixture.coordinator.pendingOffers().size)
        fixture.gateway.emit(GatewayEvent.TransferCompleted(TransferTerminal(TransferKey("text-transfer"))))
        runCurrent()
        val textActivity = fixture.tracker.activities(device).value.single { it.kind == ContentKind.TEXT }
        assertEquals("received text", (fixture.tracker.preview(textActivity.id) as ActivityPreview.Text).text)
    }

    @Test
    fun untrusted_text_waits_for_prompt() = runTest {
        val fixture = fixture(trusted = false)
        fixture.gateway.emit(offer(ContentKind.TEXT, "text/plain", "Text", "text-transfer"))
        runCurrent()

        assertTrue(fixture.gateway.accepted.isEmpty())
        assertEquals(1, fixture.coordinator.pendingOffers().size)
    }

    @Test
    fun accept_and_reject_race_allows_exactly_one_sdk_decision() = runTest {
        val fixture = fixture(trusted = false)
        fixture.gateway.emit(offer(ContentKind.FILE, "application/pdf", "report.pdf", "file-transfer"))
        runCurrent()
        val activityId = fixture.coordinator.pendingOffers().single().first

        val accepted = async { fixture.coordinator.accept(activityId) }
        val rejected = async { fixture.coordinator.reject(activityId) }
        runCurrent()

        assertEquals(1, listOf(accepted.await(), rejected.await()).count { it })
        assertEquals(1, fixture.gateway.accepted.size + fixture.gateway.rejected.size)
    }

    @Test
    fun duplicate_offer_after_rejection_cannot_reopen_the_prompt() = runTest {
        val fixture = fixture(trusted = false)
        val duplicate = offer(ContentKind.FILE, "application/pdf", "report.pdf", "file-transfer")
        fixture.gateway.emit(duplicate)
        runCurrent()
        val activityId = fixture.coordinator.pendingOffers().single().first
        fixture.coordinator.reject(activityId)

        fixture.gateway.emit(duplicate)
        runCurrent()

        assertTrue(fixture.coordinator.pendingOffers().isEmpty())
        assertEquals(1, fixture.gateway.rejected.size)
    }

    @Test
    fun failed_reject_command_keeps_offer_pending_for_an_explicit_retry() = runTest {
        val fixture = fixture(trusted = false)
        fixture.gateway.emit(offer(ContentKind.FILE, "application/pdf", "report.pdf", "file-transfer"))
        runCurrent()
        val activityId = fixture.coordinator.pendingOffers().single().first
        fixture.gateway.rejectFailure = GatewayException(ShareFailure(ShareErrorKind.NETWORK_LOST))

        try {
            fixture.coordinator.reject(activityId)
            throw AssertionError("Expected reject command failure")
        } catch (failure: CoordinatorException) {
            assertEquals(ShareErrorKind.NETWORK_LOST, failure.errorKind)
        }

        assertEquals(ActivityStatus.AWAITING_PEER, fixture.tracker.find(activityId)?.status)
        assertEquals(activityId, fixture.coordinator.pendingOffers().single().first)
    }

    @Test
    fun completed_transfer_wins_a_late_cancel_race() = runTest {
        val fixture = fixture()
        val activityId = fixture.coordinator.sendText(device, "hello")
        fixture.gateway.completeNext(TransferTerminal(TransferKey("outgoing-transfer")))
        runCurrent()

        fixture.coordinator.cancel(activityId)
        runCurrent()

        assertEquals(ActivityStatus.COMPLETED, fixture.tracker.find(activityId)?.status)
    }

    @Test
    fun failed_cancel_command_does_not_invent_a_transfer_terminal() = runTest {
        val fixture = fixture()
        val activityId = fixture.coordinator.sendText(device, "hello")
        fixture.gateway.emit(GatewayEvent.OutgoingTransferRegistered(
            OutgoingBinding(OperationKey("operation-1"), TransferKey("cancel-transfer")),
        ))
        fixture.gateway.emit(GatewayEvent.ProgressReceived(TransferProgress(TransferKey("cancel-transfer"), 2, 5)))
        runCurrent()
        fixture.gateway.cancelFailure = GatewayException(ShareFailure(ShareErrorKind.NETWORK_LOST))

        try {
            fixture.coordinator.cancel(activityId)
            throw AssertionError("Expected cancel command failure")
        } catch (failure: CoordinatorException) {
            assertEquals(ShareErrorKind.NETWORK_LOST, failure.errorKind)
        }

        assertEquals(ActivityStatus.TRANSFERRING, fixture.tracker.find(activityId)?.status)
        assertEquals(1, fixture.coordinator.activeTransferCount.value)
    }

    @Test
    fun successful_cancel_ack_waits_for_the_sdk_terminal_result() = runTest {
        val fixture = fixture()
        val activityId = fixture.coordinator.sendText(device, "hello")
        val transferId = TransferKey("cancel-transfer")
        fixture.gateway.emit(GatewayEvent.OutgoingTransferRegistered(
            OutgoingBinding(OperationKey("operation-1"), transferId),
        ))
        fixture.gateway.emit(GatewayEvent.ProgressReceived(TransferProgress(transferId, 2, 5)))
        runCurrent()

        assertTrue(fixture.coordinator.cancel(activityId))
        runCurrent()

        assertEquals(ActivityStatus.TRANSFERRING, fixture.tracker.find(activityId)?.status)
        assertEquals(1, fixture.coordinator.activeTransferCount.value)

        fixture.gateway.emit(GatewayEvent.TransferCompleted(
            TransferTerminal(transferId, ShareFailure(ShareErrorKind.CANCELLED)),
        ))
        runCurrent()

        assertEquals(ActivityStatus.CANCELLED, fixture.tracker.find(activityId)?.status)
        assertEquals(0, fixture.coordinator.activeTransferCount.value)
    }

    @Test
    fun cancel_oldest_active_transfer_targets_the_first_cancellable_activity() = runTest {
        val fixture = fixture()
        fixture.coordinator.sendText(device, "first")
        fixture.coordinator.sendText(device, "second")
        fixture.gateway.emit(GatewayEvent.OutgoingTransferRegistered(
            OutgoingBinding(OperationKey("operation-1"), TransferKey("first-transfer")),
        ))
        fixture.gateway.emit(GatewayEvent.OutgoingTransferRegistered(
            OutgoingBinding(OperationKey("operation-2"), TransferKey("second-transfer")),
        ))
        runCurrent()

        assertTrue(fixture.coordinator.cancelOldestActive())

        assertTrue("cancel:first-transfer" in fixture.gateway.calls)
        assertFalse("cancel:second-transfer" in fixture.gateway.calls)
    }

    @Test
    fun incoming_file_completes_only_after_successful_publication() = runTest {
        val fixture = fixture(trusted = false)
        fixture.gateway.emit(offer(ContentKind.FILE, "application/pdf", "report.pdf", "file-transfer"))
        runCurrent()
        val activityId = fixture.coordinator.pendingOffers().single().first
        fixture.publication.beforePublish = {
            assertEquals(ActivityStatus.AWAITING_PEER, fixture.tracker.find(activityId)?.status)
        }

        assertTrue(fixture.coordinator.accept(activityId))
        runCurrent()

        assertEquals(ActivityStatus.AWAITING_PEER, fixture.tracker.find(activityId)?.status)
        assertEquals(1, fixture.coordinator.activeTransferCount.value)
        assertFalse(fixture.publication.beforePublishCalled)
        assertFalse(TransferKey("file-transfer") in fixture.staging.deleted)

        fixture.gateway.emit(GatewayEvent.TransferCompleted(TransferTerminal(TransferKey("file-transfer"))))
        runCurrent()

        val activity = fixture.tracker.find(activityId)!!
        assertEquals(ActivityStatus.COMPLETED, activity.status)
        assertEquals("content://downloads/final", activity.finalContentUri)
        assertTrue(fixture.publication.beforePublishCalled)
    }

    @Test
    fun publication_failure_is_recoverable_for_ten_minutes_with_a_linked_activity() = runTest {
        var now = 1_000L
        val fixture = fixture(clock = { now })
        fixture.gateway.emit(offer(ContentKind.FILE, "application/pdf", "report.pdf", "file-transfer"))
        runCurrent()
        val original = fixture.coordinator.pendingOffers().single().first
        fixture.publication.failure = IllegalStateException("private path")

        fixture.coordinator.accept(original)
        fixture.gateway.emit(GatewayEvent.TransferCompleted(TransferTerminal(TransferKey("file-transfer"))))
        runCurrent()
        assertEquals(ShareErrorKind.STORAGE_FAILED, fixture.tracker.find(original)?.errorKind)
        assertFalse(fixture.staging.deleted.contains(TransferKey("file-transfer")))

        now += 599_999
        fixture.publication.failure = null
        val recovered = fixture.coordinator.retry(original)
        runCurrent()

        assertEquals(original, fixture.tracker.find(recovered)?.retryOf)
        assertEquals(ActivityStatus.COMPLETED, fixture.tracker.find(recovered)?.status)
        assertEquals("content://downloads/final", fixture.tracker.find(recovered)?.finalContentUri)
    }

    @Test
    fun failed_republication_cannot_extend_the_original_ten_minute_window() = runTest {
        var now = 1_000L
        val fixture = fixture(clock = { now })
        fixture.gateway.emit(offer(ContentKind.FILE, "application/pdf", "report.pdf", "file-transfer"))
        runCurrent()
        val original = fixture.coordinator.pendingOffers().single().first
        fixture.publication.failure = IllegalStateException("failure")
        fixture.coordinator.accept(original)
        fixture.gateway.emit(GatewayEvent.TransferCompleted(TransferTerminal(TransferKey("file-transfer"))))
        runCurrent()

        now += 599_999
        val failedRetry = fixture.coordinator.retry(original)
        now += 1
        try {
            fixture.coordinator.retry(failedRetry)
            throw AssertionError("Expected original recovery window to expire")
        } catch (failure: CoordinatorException) {
            assertEquals(ShareErrorKind.STORAGE_FAILED, failure.errorKind)
        }
        assertTrue(TransferKey("file-transfer") in fixture.staging.deleted)
    }

    @Test
    fun blocking_receive_and_republication_paths_use_the_injected_io_dispatcher() = runTest {
        val io = GuardedDispatcher(StandardTestDispatcher(testScheduler))
        val fixture = fixture(trusted = false, ioDispatcher = io)
        fixture.staging.beforeDelete = { check(io.isActive()) }

        fixture.staging.textToWrite = "received text"
        fixture.gateway.emit(offer(ContentKind.TEXT, "text/plain", "Text", "text-transfer"))
        runCurrent()
        val textActivity = fixture.coordinator.pendingOffers().single().first
        fixture.coordinator.accept(textActivity)
        fixture.gateway.emit(GatewayEvent.TransferCompleted(TransferTerminal(TransferKey("text-transfer"))))
        runCurrent()
        assertEquals(ActivityStatus.COMPLETED, fixture.tracker.find(textActivity)?.status)

        fixture.publication.beforePublish = { check(io.isActive()) }
        fixture.publication.failure = IllegalStateException("first publication fails")
        fixture.gateway.emit(offer(ContentKind.FILE, "application/pdf", "report.pdf", "file-transfer"))
        runCurrent()
        val fileActivity = fixture.coordinator.pendingOffers().single().first
        fixture.coordinator.accept(fileActivity)
        fixture.gateway.emit(GatewayEvent.TransferCompleted(TransferTerminal(TransferKey("file-transfer"))))
        runCurrent()
        assertEquals(ActivityStatus.FAILED, fixture.tracker.find(fileActivity)?.status)

        fixture.publication.failure = null
        val retry = fixture.coordinator.retry(fileActivity)
        runCurrent()

        assertEquals(ActivityStatus.COMPLETED, fixture.tracker.find(retry)?.status)
        assertEquals(2, fixture.publication.publishCalls)
    }

    @Test
    fun expired_publication_recovery_deletes_staging_and_refuses_retry() = runTest {
        var now = 1_000L
        val fixture = fixture(clock = { now })
        fixture.gateway.emit(offer(ContentKind.FILE, "application/pdf", "report.pdf", "file-transfer"))
        runCurrent()
        val original = fixture.coordinator.pendingOffers().single().first
        fixture.publication.failure = IllegalStateException("failure")
        fixture.coordinator.accept(original)
        fixture.gateway.emit(GatewayEvent.TransferCompleted(TransferTerminal(TransferKey("file-transfer"))))
        runCurrent()

        now += 600_000
        try {
            fixture.coordinator.retry(original)
            throw AssertionError("Expected retry failure")
        } catch (failure: CoordinatorException) {
            assertEquals(ShareErrorKind.STORAGE_FAILED, failure.errorKind)
        }

        assertTrue(TransferKey("file-transfer") in fixture.staging.deleted)
    }

    @Test
    fun failed_incoming_transfer_cleans_private_staging() = runTest {
        val fixture = fixture(trusted = false)
        fixture.gateway.emit(offer(ContentKind.FILE, "application/pdf", "report.pdf", "file-transfer"))
        runCurrent()
        val activityId = fixture.coordinator.pendingOffers().single().first

        fixture.coordinator.accept(activityId)
        fixture.gateway.emit(GatewayEvent.TransferCompleted(
            TransferTerminal(TransferKey("file-transfer"), ShareFailure(ShareErrorKind.NETWORK_LOST)),
        ))
        runCurrent()

        assertTrue(TransferKey("file-transfer") in fixture.staging.deleted)
        assertEquals(ShareErrorKind.NETWORK_LOST, fixture.tracker.find(activityId)?.errorKind)
    }

    @Test
    fun outgoing_completion_without_progress_binds_transfer_and_finishes_activity() = runTest {
        val fixture = fixture()
        val activityId = fixture.coordinator.sendText(device, "hello")

        fixture.gateway.completeNext(TransferTerminal(TransferKey("terminal-only")))
        runCurrent()

        assertEquals(TransferKey("terminal-only"), fixture.tracker.find(activityId)?.transferId)
        assertEquals(ActivityStatus.COMPLETED, fixture.tracker.find(activityId)?.status)
        assertEquals(0, fixture.coordinator.activeTransferCount.value)
    }

    @Test
    fun sdk_terminal_event_releases_active_count_before_operation_future_completes() = runTest {
        val fixture = fixture()
        val activityId = fixture.coordinator.sendText(device, "hello")
        fixture.gateway.emit(GatewayEvent.OutgoingTransferRegistered(
            OutgoingBinding(OperationKey("operation-1"), TransferKey("event-terminal")),
        ))
        fixture.gateway.emit(GatewayEvent.TransferCompleted(TransferTerminal(TransferKey("event-terminal"))))
        runCurrent()

        assertEquals(ActivityStatus.COMPLETED, fixture.tracker.find(activityId)?.status)
        assertEquals(0, fixture.coordinator.activeTransferCount.value)
        assertEquals(1, fixture.gateway.pending.size)
    }

    @Test
    fun reentrant_terminal_during_submission_cannot_reactivate_a_completed_transfer() = runTest {
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val workerScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val fixture = fixture(workerScope = workerScope)
            val sentinel = offer(ContentKind.FILE, "application/pdf", "sentinel.pdf", "sentinel")
            fixture.gateway.eventsOnBegin = listOf(
                GatewayEvent.OutgoingTransferRegistered(
                    OutgoingBinding(OperationKey("operation-1"), TransferKey("reentrant-terminal")),
                ),
                GatewayEvent.TransferCompleted(TransferTerminal(TransferKey("reentrant-terminal"))),
                sentinel,
            )
            fixture.gateway.afterEventsOnBegin = {
                val deadline = System.nanoTime() + 2_000_000_000L
                while (fixture.coordinator.pendingOffers().none { it.second.transferId == TransferKey("sentinel") } &&
                    System.nanoTime() < deadline
                ) Thread.yield()
                check(fixture.coordinator.pendingOffers().any { it.second.transferId == TransferKey("sentinel") })
            }

            val activityId = fixture.coordinator.sendText(device, "hello")

            assertEquals(ActivityStatus.COMPLETED, fixture.tracker.find(activityId)?.status)
            assertEquals(0, fixture.coordinator.activeTransferCount.value)
        } finally {
            workerScope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun retirement_rejects_new_work_and_waits_for_owned_completion() = runTest {
        val fixture = fixture()
        fixture.coordinator.sendText(device, "hello")
        val retirement = async { fixture.coordinator.retire() }
        runCurrent()
        assertFalse(retirement.isCompleted)

        try {
            fixture.coordinator.sendText(device, "later")
            throw AssertionError("Expected retired coordinator")
        } catch (failure: CoordinatorException) {
            assertEquals(ShareErrorKind.INTERRUPTED, failure.errorKind)
        }

        fixture.gateway.completeNext(TransferTerminal(TransferKey("done")))
        runCurrent()
        assertTrue(retirement.isCompleted)
    }

    @Test
    fun retirement_waits_for_a_manual_accept_continuation() = runTest {
        val fixture = fixture(trusted = false)
        fixture.gateway.emit(offer(ContentKind.FILE, "application/pdf", "report.pdf", "file-transfer"))
        runCurrent()
        val activityId = fixture.coordinator.pendingOffers().single().first
        fixture.gateway.acceptCompletion = CompletableDeferred()
        val acceptance = async { fixture.coordinator.accept(activityId) }
        runCurrent()

        val retirement = async { fixture.coordinator.retire() }
        runCurrent()
        assertFalse(retirement.isCompleted)

        fixture.gateway.acceptCompletion!!.complete(TransferTerminal(TransferKey("file-transfer")))
        acceptance.await()
        runCurrent()
        assertFalse(retirement.isCompleted)

        fixture.gateway.emit(GatewayEvent.TransferCompleted(TransferTerminal(TransferKey("file-transfer"))))
        runCurrent()
        assertTrue(retirement.isCompleted)
    }

    @Test
    fun immediate_submission_failure_retains_a_process_only_manual_retry_recipe() = runTest {
        val fixture = fixture()
        fixture.gateway.beginFailure = GatewayException(ShareFailure(ShareErrorKind.NETWORK_LOST))
        try {
            fixture.coordinator.sendText(device, "hello")
            throw AssertionError("Expected submission failure")
        } catch (failure: CoordinatorException) {
            assertEquals(ShareErrorKind.NETWORK_LOST, failure.errorKind)
        }
        val failed = fixture.tracker.activities(device).value.single()
        fixture.gateway.beginFailure = null

        val retry = fixture.coordinator.retry(failed.id)

        assertEquals(failed.id, fixture.tracker.find(retry)?.retryOf)
        assertEquals(listOf("text:hello"), fixture.gateway.submissions)
    }

    private suspend fun TestScope.fixture(
        trusted: Boolean = true,
        clock: () -> Long = { 1_000L },
        workerScope: CoroutineScope = backgroundScope,
        ioDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        onCanSend: () -> Unit = {},
    ): Fixture {
        val repository = CoordinatorActivityRepository()
        val tracker = ActivityTracker(repository, workerScope, idFactory = sequentialIds())
        val gateway = CoordinatorGateway()
        val session = ShareSessionManager(gateway, workerScope)
        session.initialize(GatewayConfig("test"))
        val staging = FakeStagingAccess()
        val publication = FakePublication()
        val settings = FakeSettingsRepository()
        val devices = FakeDeviceRepository(if (trusted) setOf(device) else emptySet(), onCanSend)
        val coordinator = TransferCoordinator(
            session = session,
            tracker = tracker,
            devices = devices,
            settings = settings,
            staging = staging,
            publication = publication,
            scope = workerScope,
            ioDispatcher = ioDispatcher,
            clock = clock,
            idFactory = sequentialIds("coordinator"),
        )
        runCurrent()
        return Fixture(coordinator, tracker, gateway, staging, publication)
    }

    private fun selected(kind: ContentKind, mime: String, name: String) =
        SelectedContent(fakeUri(), kind, name, mime, 9)

    private fun offer(kind: ContentKind, mime: String, name: String, transfer: String) = GatewayEvent.OfferReceived(
        IncomingOffer(7L + transfer.hashCode(), TransferKey(transfer), device, kind, name, mime, if (kind == ContentKind.TEXT) 13 else 9),
    )

    private fun sequentialIds(prefix: String = "activity"): () -> ActivityKey {
        var next = 0
        return { ActivityKey("$prefix-${++next}") }
    }

    private data class Fixture(
        val coordinator: TransferCoordinator,
        val tracker: ActivityTracker,
        val gateway: CoordinatorGateway,
        val staging: FakeStagingAccess,
        val publication: FakePublication,
    )

    private companion object { val device = DeviceKey("00000000-0000-0000-0000-000000000001") }
}

private class CoordinatorActivityRepository : ActivityRepository {
    private val flows = mutableMapOf<DeviceKey, MutableStateFlow<List<ShareActivity>>>()
    val upserts = CopyOnWriteArrayList<ShareActivity>()
    override fun observeRecent(limit: Int): Flow<List<ShareActivity>> = MutableStateFlow(emptyList())
    override fun observeDevice(device: DeviceKey): Flow<List<ShareActivity>> = flows.getOrPut(device) { MutableStateFlow(emptyList()) }
    override suspend fun upsert(activity: ShareActivity) { upserts += activity }
    override suspend fun markNonTerminalInterrupted(nowMillis: Long) = Unit
    override suspend fun prune(nowMillis: Long) = Unit
}

private class CoordinatorGateway : ShareSdkGateway {
    override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 20)
    val calls = CopyOnWriteArrayList<String>()
    val submissions = CopyOnWriteArrayList<String>()
    val accepted = CopyOnWriteArrayList<Long>()
    val rejected = CopyOnWriteArrayList<Long>()
    val pending = ArrayDeque<CompletableDeferred<TransferTerminal>>()
    var clipboard: ClipboardContent? = null
    var acceptResult = TransferTerminal(null)
    var acceptCompletion: CompletableDeferred<TransferTerminal>? = null
    var beginFailure: GatewayException? = null
    var rejectFailure: GatewayException? = null
    var cancelFailure: GatewayException? = null
    var eventsOnBegin: List<GatewayEvent> = emptyList()
    var afterEventsOnBegin: (() -> Unit)? = null
    private var nextOperation = 0
    private var offerActivities: ((TransferKey) -> ActivityKey?)? = null

    override suspend fun initialize(config: GatewayConfig) = Unit
    override fun subscribe() = AutoCloseable { }
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun setForeground(value: Boolean) = Unit
    override fun devices() = emptyList<DeviceSnapshot>()
    override fun trustedDevices() = emptySet<DeviceKey>()
    override suspend fun connect(deviceId: DeviceKey) = Unit
    override suspend fun forgetDevice(deviceId: DeviceKey) = Unit
    override suspend fun confirmPairing(requestId: Long, codeMatches: Boolean) = Unit
    override suspend fun rejectPairing(requestId: Long) = Unit
    override fun beginSendText(deviceId: DeviceKey, text: String): PendingSend = pending("text:$text")
    override fun beginSendImage(deviceId: DeviceKey, source: Uri, mimeType: String, byteCount: Long, displayName: String): PendingSend =
        pending("image:$displayName:$mimeType:$byteCount")
    override fun beginSendFile(deviceId: DeviceKey, source: Uri, displayName: String, byteCount: Long, mimeType: String): PendingSend =
        pending("file:$displayName:$mimeType:$byteCount")
    override suspend fun accept(requestId: Long, destination: Uri): TransferTerminal {
        calls += "accept:$requestId"
        accepted += requestId
        return acceptCompletion?.await() ?: acceptResult
    }
    override suspend fun reject(requestId: Long) {
        calls += "reject:$requestId"
        rejectFailure?.let { throw it }
        rejected += requestId
    }
    override suspend fun cancel(transferId: TransferKey) {
        calls += "cancel:${transferId.value}"
        cancelFailure?.let { throw it }
    }
    override suspend fun readClipboard(): ClipboardContent? { calls += "clipboard"; return clipboard }
    override fun close() = Unit

    fun emit(event: GatewayEvent) { check(events.tryEmit(event)) }
    @Synchronized
    fun completeNext(terminal: TransferTerminal) { pending.removeFirst().complete(terminal) }

    @Synchronized
    private fun pending(description: String): PendingSend {
        beginFailure?.let { throw it }
        calls += description
        submissions += description
        val completion = CompletableDeferred<TransferTerminal>()
        pending += completion
        val result = PendingSend(OperationKey("operation-${++nextOperation}"), completion)
        eventsOnBegin.forEach { check(events.tryEmit(it)) }
        afterEventsOnBegin?.invoke()
        return result
    }
}

private class FakeDeviceRepository(
    private val trusted: Set<DeviceKey>,
    private val onCanSend: () -> Unit = {},
) : DeviceRepository {
    override val devices: StateFlow<List<DeviceSummary>> = MutableStateFlow(emptyList())
    override fun find(deviceId: DeviceKey): DeviceSummary? = DeviceSummary(deviceId, "", null, true, deviceId in trusted)
    override fun isTrusted(deviceId: DeviceKey) = deviceId in trusted
    override fun canSend(deviceId: DeviceKey): Boolean {
        onCanSend()
        return true
    }
    override suspend fun connect(deviceId: DeviceKey) = Unit
    override suspend fun forget(deviceId: DeviceKey) = Unit
}

private class FakeSettingsRepository : SettingsRepository {
    override val settings = MutableStateFlow(AppSettings())
    override suspend fun setDeviceName(name: String) = Unit
    override suspend fun setReceiveDirectory(treeUri: String?, displayName: String?) = Unit
    override suspend fun setBackgroundReceiveEnabled(enabled: Boolean) = Unit
    override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun setLanguage(language: AppLanguage) = Unit
    override suspend fun setTheme(theme: AppTheme) = Unit
}

private class FakeStagingAccess : StagingAccess {
    val deleted = CopyOnWriteArrayList<TransferKey>()
    var textToWrite: String? = null
    var beforeDelete: (() -> Unit)? = null
    override fun create(transferId: TransferKey): PreparedStaging {
        val file = kotlin.io.path.createTempFile("unishare-test", ".data").toFile()
        textToWrite?.let(file::writeText)
        return PreparedStaging(transferId, fakeUri(), file, 1_000)
    }
    override fun delete(transferId: TransferKey) {
        beforeDelete?.invoke()
        deleted += transferId
    }
}

private class FakePublication : ContentPublication {
    var failure: Exception? = null
    var beforePublish: (() -> Unit)? = null
    var beforePublishCalled = false
    var publishCalls = 0
    override fun publish(staging: PreparedStaging, displayName: String, mimeType: String, destination: com.unishare.app.data.content.ReceiveDestination): String {
        beforePublish?.invoke()
        beforePublishCalled = true
        publishCalls++
        failure?.let { throw it }
        return "content://downloads/final"
    }
}

private class RecordingContentReadPermissionLease : ContentReadPermissionLease {
    private val closedSignal = CountDownLatch(1)
    var closeCalls = 0
        private set
    val closed: Boolean get() = closeCalls > 0

    override fun close() {
        closeCalls++
        closedSignal.countDown()
    }

    fun awaitClosed(): Boolean = closedSignal.await(5, TimeUnit.SECONDS)
}

private class GuardedDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    private val depth = ThreadLocal.withInitial { 0 }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) {
            depth.set((depth.get() ?: 0) + 1)
            try {
                block.run()
            } finally {
                depth.set((depth.get() ?: 1) - 1)
            }
        }
    }

    fun isActive(): Boolean = (depth.get() ?: 0) > 0
}
