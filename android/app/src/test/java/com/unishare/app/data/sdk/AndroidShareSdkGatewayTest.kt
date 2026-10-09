package com.unishare.app.data.sdk

import android.net.Uri
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.OperationKey
import com.unishare.app.domain.ShareErrorKind
import com.unishare.app.domain.TransferKey
import com.unishare.sdk.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BiConsumer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidShareSdkGatewayTest {
    private val device = DeviceKey("00000000-0000-0000-0000-000000000001")
    private val firstTransfer = TransferId(UUID(0, 10))
    private val secondTransfer = TransferId(UUID(0, 11))

    @Test fun observer_order_is_discovery_connection_transfer_and_handles_close_once() = runTest {
        val client = FakeClient()
        val gateway = AndroidShareSdkGateway(client, backgroundScope)
        try { gateway.beginSendText(device, "hello"); fail("Ready required") } catch (_: IllegalStateException) { }
        assertTrue(client.submissions.isEmpty())
        gateway.initialize(GatewayConfig("local"))
        val one = gateway.subscribe()
        val two = gateway.subscribe()
        assertSame(one, two)
        assertEquals(listOf("discovery", "connection", "transfer"), client.observers)
        gateway.close(); one.close(); gateway.close()
        assertEquals(listOf("discovery", "connection", "transfer", "close:transfer", "close:connection", "close:discovery", "close:sdk"), client.observers)
    }

    @Test fun send_registration_precedes_early_progress_and_late_progress_does_not_steal_next_send() = runTest {
        val client = FakeClient()
        val gateway = AndroidShareSdkGateway(client, backgroundScope)
        val events = mutableListOf<GatewayEvent>()
        backgroundScope.launch { gateway.events.collect { events += it } }
        gateway.initialize(GatewayConfig("local")); gateway.subscribe(); runCurrent()
        client.progressInsideSend = firstTransfer
        val first = gateway.beginSendText(device, "hello")
        runCurrent()
        assertEquals(GatewayEvent.OutgoingTransferRegistered(OutgoingBinding(first.operationId, TransferKey(firstTransfer.value.toString()))), events.first())
        client.progressInsideSend = null
        client.submissions[0].result.complete(OperationResult(1, transferId = firstTransfer))
        val second = gateway.beginSendText(device, "world")
        client.transfer!!(TransferEvent.Progress(firstTransfer, 2, 2))
        client.transfer!!(TransferEvent.Progress(secondTransfer, 1, 2)); runCurrent()
        assertEquals(listOf(OperationKey("1"), OperationKey("2")), events.filterIsInstance<GatewayEvent.OutgoingTransferRegistered>().map { it.binding.operationId })
        assertEquals(firstTransfer.value.toString(), first.completion.await().transferId!!.value)
        client.submissions[1].result.complete(OperationResult(2, transferId = secondTransfer))
        assertEquals(secondTransfer.value.toString(), second.completion.await().transferId!!.value)
        gateway.close()
    }

    @Test fun result_before_progress_and_incoming_progress_cannot_consume_next_operation() = runTest {
        val client = FakeClient()
        val gateway = AndroidShareSdkGateway(client, backgroundScope)
        val events = mutableListOf<GatewayEvent>()
        backgroundScope.launch { gateway.events.collect { events += it } }
        gateway.initialize(GatewayConfig("local")); gateway.subscribe(); runCurrent()
        gateway.beginSendText(device, "first")
        client.submissions[0].result.complete(OperationResult(1, transferId = firstTransfer))
        val next = gateway.beginSendText(device, "second")
        client.transfer!!(TransferEvent.Progress(firstTransfer, 2, 2))
        val incoming = TransferId(UUID(0, 12))
        client.transfer!!(TransferEvent.IncomingOffer(42, incoming, DeviceId(UUID.fromString(device.value)), ContentType.TEXT, "text", "text/plain", 2))
        client.transfer!!(TransferEvent.Progress(incoming, 1, 2))
        client.transfer!!(TransferEvent.Progress(secondTransfer, 1, 2)); runCurrent()
        assertEquals(listOf(next.operationId), events.filterIsInstance<GatewayEvent.OutgoingTransferRegistered>().map { it.binding.operationId })
        gateway.close()
    }

    @Test fun cancellation_of_completion_and_await_does_not_cancel_sdk_owned_futures() = runTest {
        val client = FakeClient()
        val gateway = AndroidShareSdkGateway(client, backgroundScope)
        gateway.initialize(GatewayConfig("local")); gateway.subscribe()
        val send = gateway.beginSendText(device, "hello")
        send.completion.cancel()
        assertFalse(client.submissions.single().result.isCancelled)
        assertFalse(client.submissions.single().result.isDone)
        val commandFuture = CompletableFuture<OperationResult>()
        val awaiting = launch { commandFuture.awaitSdkResult() }
        runCurrent(); awaiting.cancel(); awaiting.join()
        assertFalse(commandFuture.isDone)
        commandFuture.complete(OperationResult(10))
        client.submissions.single().result.complete(OperationResult(1, transferId = firstTransfer))
        gateway.close()
    }

    @Test fun every_sdk_error_maps_one_to_one_and_clipboard_bytes_are_copied() = runTest {
        for (category in ErrorCategory.entries) assertEquals(ShareErrorKind.valueOf(category.name), ShareError(category, 7).toFailure().kind)
        val sdkImage = ShareContent.Image("image/png", byteArrayOf(1, 2))
        val copied = sdkImage.toClipboardContent() as ClipboardContent.Image
        val exposed = copied.bytes
        exposed[0] = 9
        assertArrayEquals(byteArrayOf(1, 2), copied.bytes)
        assertArrayEquals(byteArrayOf(1, 2), sdkImage.bytes)
    }

    @Test fun identity_callback_blocks_send_immediately_before_flow_collection() = runTest {
        val client = FakeClient()
        val gateway = AndroidShareSdkGateway(client, backgroundScope)
        gateway.initialize(GatewayConfig("local")); gateway.subscribe()
        client.connection!!(ConnectionEvent.IdentityChanged(DeviceId(UUID.fromString(device.value))))
        try { gateway.beginSendText(device, "hello"); fail("Identity changed") } catch (error: GatewayException) { assertEquals(ShareErrorKind.IDENTITY_CHANGED, error.failure.kind) }
        assertTrue(client.submissions.isEmpty())
        gateway.forgetDevice(device)
        gateway.beginSendText(device, "after forget")
        assertEquals(1, client.submissions.size)
        gateway.close()
    }

    @Test fun newer_identity_callback_after_forget_completion_cannot_be_cleared_by_a_delayed_coroutine() = runTest {
        val client = FakeClient()
        val gateway = AndroidShareSdkGateway(client, backgroundScope)
        gateway.initialize(GatewayConfig("local")); gateway.subscribe()
        val identity = ConnectionEvent.IdentityChanged(DeviceId(UUID.fromString(device.value)))
        client.connection!!(identity)
        client.forgetResult = CompletableFuture()
        val forgetting = backgroundScope.launch { gateway.forgetDevice(device) }
        runCurrent()
        client.forgetResult!!.complete(OperationResult(99))
        // A new SDK callback arrives before the awaiting app coroutine resumes.
        client.connection!!(identity)
        runCurrent(); forgetting.join()
        try { gateway.beginSendText(device, "blocked"); fail("New identity callback must remain blocked") }
        catch (error: GatewayException) { assertEquals(ShareErrorKind.IDENTITY_CHANGED, error.failure.kind) }
        assertTrue(client.submissions.isEmpty())
        gateway.close()
    }

    @Test fun completed_forget_future_cannot_clear_identity_callback_in_handler_installation_window() = runTest {
        val client = FakeClient()
        val gateway = AndroidShareSdkGateway(client, backgroundScope)
        gateway.initialize(GatewayConfig("local")); gateway.subscribe()
        val identity = ConnectionEvent.IdentityChanged(DeviceId(UUID.fromString(device.value)))
        client.connection!!(identity)
        val callbackFinished = AtomicBoolean(false)
        val newerCallback = Thread {
            client.connection!!(identity)
            callbackFinished.set(true)
        }
        val future = BeforeFirstHandlerFuture {
            newerCallback.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            // The callback either runs before installation (the defect), or waits at the
            // gateway submission lock until the successful completion handler is installed.
            while (!callbackFinished.get() && newerCallback.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            check(callbackFinished.get() || newerCallback.state == Thread.State.BLOCKED)
        }
        future.complete(OperationResult(99))
        client.forgetResult = future
        gateway.forgetDevice(device)
        newerCallback.join(5000)
        assertFalse("Callback thread must finish", newerCallback.isAlive)
        try { gateway.beginSendText(device, "blocked"); fail("New callback must remain blocked") }
        catch (error: GatewayException) { assertEquals(ShareErrorKind.IDENTITY_CHANGED, error.failure.kind) }
        assertTrue(client.submissions.isEmpty())
        gateway.close()
    }

    private class BeforeFirstHandlerFuture(private val beforeFirstHandler: () -> Unit) : CompletableFuture<OperationResult>() {
        private val first = AtomicBoolean(true)
        override fun whenComplete(action: BiConsumer<in OperationResult, in Throwable?>): CompletableFuture<OperationResult> {
            if (first.compareAndSet(true, false)) beforeFirstHandler()
            return super.whenComplete(action)
        }
    }

    @Test fun initialize_cannot_return_before_ready_publication_even_if_await_handler_runs_first() = runTest {
        val client = FakeClient()
        val future = DelayedFirstHandlerFuture()
        client.initializeResult = future
        val gateway = AndroidShareSdkGateway(client, backgroundScope)
        val outcome = CompletableDeferred<Result<Unit>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            outcome.complete(runCatching { gateway.initialize(GatewayConfig("local")); gateway.subscribe(); Unit })
        }
        future.complete(OperationResult(99))
        val earlyOutcome = if (outcome.isCompleted) outcome.await() else null
        future.releaseFirstHandler()
        assertNull("An early successful initialize must already permit subscription", earlyOutcome?.exceptionOrNull())
        assertTrue(outcome.await().isSuccess)
        assertEquals(listOf("discovery", "connection", "transfer"), client.observers)
        gateway.close()
    }

    @Test fun stop_waits_until_callbacks_queued_before_stop_are_delivered() = runTest {
        val client = FakeClient()
        val deliveryScheduler = TestCoroutineScheduler()
        val gateway = AndroidShareSdkGateway(
            client,
            CoroutineScope(StandardTestDispatcher(deliveryScheduler) + Job()),
        )
        val events = mutableListOf<GatewayEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gateway.events.collect { events += it } }
        gateway.initialize(GatewayConfig("local")); gateway.subscribe()
        client.connection!!(ConnectionEvent.Connected(DeviceId(UUID.fromString(device.value))))

        val stopping = backgroundScope.launch { gateway.stop() }
        runCurrent()

        assertFalse("Stop must not overtake a queued callback", stopping.isCompleted)
        assertTrue(events.isEmpty())
        deliveryScheduler.runCurrent()
        stopping.join()
        assertEquals(listOf(GatewayEvent.ConnectionChanged(device, connected = true)), events)
        gateway.close()
    }

    private class DelayedFirstHandlerFuture : CompletableFuture<OperationResult>() {
        private var firstHandler: BiConsumer<in OperationResult, in Throwable?>? = null
        private val published = CompletableFuture<OperationResult>()
        override fun whenComplete(action: BiConsumer<in OperationResult, in Throwable?>): CompletableFuture<OperationResult> {
            if (firstHandler == null) { firstHandler = action; return published }
            return super.whenComplete(action)
        }
        fun releaseFirstHandler() {
            val value = getNow(null)
            firstHandler!!.accept(value, null)
            published.complete(value)
        }
    }

    private class FakeClient : SdkClient {
        val observers = mutableListOf<String>()
        val submissions = mutableListOf<SdkSubmission>()
        var transfer: ((TransferEvent) -> Unit)? = null
        var connection: ((ConnectionEvent) -> Unit)? = null
        var progressInsideSend: TransferId? = null
        var forgetResult: CompletableFuture<OperationResult>? = null
        var initializeResult: CompletableFuture<OperationResult>? = null
        override fun initialize(config: SdkConfig) = initializeResult?.let { SdkSubmission(99, it) } ?: done()
        override fun start() = done()
        override fun stop() = done()
        override fun setForeground(value: Boolean) = Unit
        override fun devices() = emptyList<DeviceInfo>()
        override fun trustedDevices() = emptyList<TrustedDevice>()
        override fun observeDiscovery(listener: (DiscoveryEvent) -> Unit) = observer("discovery")
        override fun observeConnections(listener: (ConnectionEvent) -> Unit): AutoCloseable { connection = listener; return observer("connection") }
        override fun observeTransfers(listener: (TransferEvent) -> Unit): AutoCloseable { transfer = listener; return observer("transfer") }
        override fun connect(device: DeviceId) = done()
        override fun forgetDevice(device: DeviceId) = forgetResult?.let { SdkSubmission(99, it) } ?: done()
        override fun confirmPairing(requestId: Long, matches: Boolean) = done()
        override fun rejectPairing(requestId: Long) = done()
        override fun sendText(device: DeviceId, text: String): SdkSubmission {
            val result = SdkSubmission(submissions.size + 1L, CompletableFuture())
            submissions += result
            progressInsideSend?.let { transfer!!(TransferEvent.Progress(it, 1, 2)) }
            return result
        }
        override fun sendImage(device: DeviceId, source: Uri, mimeType: String, byteCount: Long, displayName: String) = sendText(device, "")
        override fun sendFile(device: DeviceId, source: Uri, displayName: String, byteCount: Long, mimeType: String) = sendText(device, "")
        override fun accept(requestId: Long, destination: Uri) = done()
        override fun reject(requestId: Long) = done()
        override fun cancel(transfer: TransferId) = done()
        override fun readClipboard() = done()
        override fun close() { observers += "close:sdk" }
        private fun done() = SdkSubmission(99, CompletableFuture.completedFuture(OperationResult(99)))
        private fun observer(kind: String): AutoCloseable { observers += kind; return AutoCloseable { observers += "close:$kind" } }
    }
}
