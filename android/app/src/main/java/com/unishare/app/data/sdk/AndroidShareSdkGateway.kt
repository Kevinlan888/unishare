package com.unishare.app.data.sdk

import android.content.Context
import android.net.Uri
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.OperationKey
import com.unishare.app.domain.ShareErrorKind
import com.unishare.app.domain.TransferKey
import com.unishare.sdk.*
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** The application graph creates one of these, owned exclusively by ShareSessionManager. */
class AndroidShareSdkGateway internal constructor(private val client: SdkClient, scope: CoroutineScope) : ShareSdkGateway {
    constructor(context: Context, scope: CoroutineScope) : this(ShareSdkClient(ShareSdk(context.applicationContext)), scope)

    private val gate = Any()
    private val registry = OutgoingTransferRegistry()
    private val blockedDevices = HashSet<DeviceKey>()
    private val pending = LinkedHashMap<OperationKey, CompletableDeferred<TransferTerminal>>()
    private val deferredCallbacks = java.util.ArrayDeque<GatewayEvent>()
    private var submitting = false
    @Volatile private var closed = false
    private var ready = false
    private var initialization: CompletableFuture<OperationResult>? = null
    private var subscriptions: AutoCloseable? = null
    private val deliveryScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private sealed interface DeliveryEnvelope {
        data class Event(val value: GatewayEventEnvelope) : DeliveryEnvelope
        data class Barrier(val completion: CompletableDeferred<Unit>) : DeliveryEnvelope
    }
    private val envelopes = Channel<DeliveryEnvelope>(Channel.UNLIMITED)
    private val delivered = MutableSharedFlow<GatewayEventEnvelope>()
    override val eventEnvelopes: Flow<GatewayEventEnvelope> = delivered.filter { !closed }
    override val events: Flow<GatewayEvent> = eventEnvelopes.map { it.event }
    @Volatile override var eventGeneration = 0L
        private set

    init {
        deliveryScope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (envelope in envelopes) when (envelope) {
                is DeliveryEnvelope.Event -> if (!closed) delivered.emit(envelope.value)
                is DeliveryEnvelope.Barrier -> envelope.completion.complete(Unit)
            }
        }
    }

    override suspend fun initialize(config: GatewayConfig) {
        val result = synchronized(gate) {
            ensureOpen()
            if (ready) return
            initialization ?: sdkCall { client.initialize(SdkConfig(config.deviceName)) }.result.let { future ->
                future.whenComplete { value, failure ->
                    synchronized(gate) {
                        if (!closed) ready = failure == null && value?.isSuccess == true
                        initialization = null
                    }
                }.also { publication ->
                    // An immediate failure already cleared initialization; keep it retryable.
                    if (!publication.isDone) initialization = publication
                }
            }
        }
        result.awaitSdkResult().requireSuccess()
    }

    override fun subscribe(): AutoCloseable = synchronized(gate) {
        ensureReady()
        subscriptions?.let { return@synchronized it }
        val handles = mutableListOf<AutoCloseable>()
        try {
            // Register only after initialize succeeds; preserve SDK observer registration order.
            handles += sdkCall { client.observeDiscovery { dispatchCopied(it.toGatewayEvent()) } }
            handles += sdkCall { client.observeConnections { dispatchCopied(it.toGatewayEvent()) } }
            handles += sdkCall { client.observeTransfers { dispatchCopied(it.toGatewayEvent()) } }
        } catch (failure: Exception) {
            handles.asReversed().forEach { try { it.close() } catch (_: Exception) { } }
            throw failure
        }
        val retired = AtomicBoolean(false)
        AutoCloseable {
            if (retired.compareAndSet(false, true)) {
                var failure: Exception? = null
                handles.asReversed().forEach { try { it.close() } catch (error: Exception) { failure = failure ?: error } }
                failure?.let { throw GatewayException(it.toFailure()) }
            }
        }.also { subscriptions = it }
    }

    override suspend fun start() { command { client.start() }.requireSuccess() }
    override suspend fun stop() {
        command { client.stop() }.requireSuccess()
        // The native stop completion drains native callbacks, but their copied events may
        // still be queued on our coroutine delivery lane. Do not let STOPPED publication
        // overtake those events or a later restart could revive stale connection state.
        val barrier = CompletableDeferred<Unit>()
        check(envelopes.trySend(DeliveryEnvelope.Barrier(barrier)).isSuccess) { "Event delivery is closed" }
        barrier.await()
        synchronized(gate) {
            eventGeneration++
            registry.clear()
        }
    }
    override fun setForeground(value: Boolean) = synchronized(gate) { ensureReady(); sdkCall { client.setForeground(value) } }
    override fun devices(): List<DeviceSnapshot> = synchronized(gate) {
        ensureReady()
        Collections.unmodifiableList(sdkCall { client.devices() }.map { it.toSnapshot() })
    }
    override fun trustedDevices(): Set<DeviceKey> = synchronized(gate) {
        ensureReady()
        // Key fingerprints remain owned by the SDK; the app only needs the stable trusted IDs.
        Collections.unmodifiableSet(sdkCall { client.trustedDevices() }.mapTo(LinkedHashSet()) { it.id.toKey() })
    }
    override suspend fun connect(deviceId: DeviceKey) { command(deviceId) { client.connect(deviceId.toSdkId()) }.requireSuccess() }
    override suspend fun forgetDevice(deviceId: DeviceKey) {
        val completion = synchronized(gate) {
            ensureReady()
            sdkCall { client.forgetDevice(deviceId.toSdkId()) }.result.whenComplete { value, failure ->
                // Install while callbacks are excluded, including an already completed result.
                if (failure == null && value?.isSuccess == true) synchronized(gate) {
                    if (!closed) blockedDevices.remove(deviceId)
                }
            }
        }
        // Depend on state publication, not a sibling handler on the SDK-owned future.
        completion.awaitSdkResult().requireSuccess()
    }
    override suspend fun confirmPairing(requestId: Long, codeMatches: Boolean) { command { client.confirmPairing(requestId, codeMatches) }.requireSuccess() }
    override suspend fun rejectPairing(requestId: Long) { command { client.rejectPairing(requestId) }.requireSuccess() }

    override fun beginSendText(deviceId: DeviceKey, text: String) = beginSend(deviceId) { client.sendText(deviceId.toSdkId(), text) }
    override fun beginSendImage(deviceId: DeviceKey, source: Uri, mimeType: String, byteCount: Long, displayName: String) =
        beginSend(deviceId) { client.sendImage(deviceId.toSdkId(), source, mimeType, byteCount, displayName) }
    override fun beginSendFile(deviceId: DeviceKey, source: Uri, displayName: String, byteCount: Long, mimeType: String) =
        beginSend(deviceId) { client.sendFile(deviceId.toSdkId(), source, displayName, byteCount, mimeType) }

    private fun beginSend(deviceId: DeviceKey, submit: () -> SdkSubmission): PendingSend = synchronized(gate) {
        ensureReady(); ensureIdentity(deviceId)
        // All SDK send variants use its serialized stream-acquisition queue. This lock preserves
        // the same submission order in our FIFO, and excludes callbacks until registration.
        submitting = true
        val operation = try { sdkCall(submit) } catch (failure: Exception) {
            submitting = false
            drainCallbacks()
            throw failure
        }
        val key = OperationKey(operation.id.toString())
        val completion = CompletableDeferred<TransferTerminal>()
        registry.register(key)
        pending[key] = completion
        submitting = false
        drainCallbacks()
        operation.result.whenComplete { value, failure ->
            val terminal = if (failure == null && value != null) value.toTerminal() else TransferTerminal(null, (failure ?: IllegalStateException()).toFailure())
            synchronized(gate) {
                if (!closed) registry.complete(key, terminal.transferId)
                pending.remove(key)
            }
            // Cancelling this app-owned Deferred never changes the SDK-owned future.
            completion.complete(terminal)
        }
        PendingSend(key, completion)
    }

    override suspend fun accept(requestId: Long, destination: Uri): TransferTerminal = command { client.accept(requestId, destination) }.toTerminal()
    override suspend fun reject(requestId: Long) { command { client.reject(requestId) }.requireSuccess() }
    override suspend fun cancel(transferId: TransferKey) { command { client.cancel(TransferId(UUID.fromString(transferId.value))) }.requireSuccess() }
    override suspend fun readClipboard(): ClipboardContent? = command { client.readClipboard() }.let { it.requireSuccess(); it.content?.toClipboardContent() }

    private suspend fun command(deviceId: DeviceKey? = null, submit: () -> SdkSubmission): OperationResult {
        val operation = synchronized(gate) { ensureReady(); deviceId?.let(::ensureIdentity); sdkCall(submit) }
        return operation.result.awaitSdkResult()
    }

    private fun dispatchCopied(event: GatewayEvent) = synchronized(gate) {
        if (closed) return@synchronized
        // SDK callbacks are normally asynchronous. Deferring also protects reentrant executors.
        if (submitting) deferredCallbacks.addLast(event) else processCopied(event)
    }
    private fun drainCallbacks() { while (deferredCallbacks.isNotEmpty()) processCopied(deferredCallbacks.removeFirst()) }
    private fun processCopied(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.IdentityChanged -> blockedDevices.add(event.deviceId)
            is GatewayEvent.OfferReceived -> registry.observeIncoming(event.offer.transferId)
            is GatewayEvent.ProgressReceived -> registry.observeProgress(event.progress.transferId)?.let {
                envelopes.trySend(DeliveryEnvelope.Event(GatewayEventEnvelope(
                    GatewayEvent.OutgoingTransferRegistered(it), eventGeneration,
                )))
            }
            is GatewayEvent.TransferCompleted -> event.terminal.transferId?.let(registry::observeIncoming)
            else -> Unit
        }
        envelopes.trySend(DeliveryEnvelope.Event(GatewayEventEnvelope(event, eventGeneration)))
    }

    override fun close() {
        val teardown = synchronized(gate) {
            if (closed) return
            closed = true
            ready = false
            registry.clear(); deferredCallbacks.clear(); blockedDevices.clear()
            val completions = pending.values.toList()
            pending.clear()
            subscriptions to completions
        }
        envelopes.close(); deliveryScope.cancel()
        var failure: Exception? = null
        try { teardown.first?.close() } catch (error: Exception) { failure = error }
        // Do not hold our callback lock while SDK.close drains its own callback gate.
        try { client.close() } catch (error: Exception) { failure = failure ?: error }
        teardown.second.forEach { it.complete(TransferTerminal(null, ShareFailure(ShareErrorKind.CANCELLED))) }
        failure?.let { throw GatewayException(it.toFailure()) }
    }

    private fun ensureOpen() { check(!closed) { "Session is closed" } }
    private fun ensureReady() { ensureOpen(); check(ready) { "Initialize must complete successfully first" } }
    private fun ensureIdentity(deviceId: DeviceKey) {
        if (deviceId in blockedDevices) throw GatewayException(ShareFailure(ShareErrorKind.IDENTITY_CHANGED))
    }
}

/** Cancellation retires the waiting coroutine only; never cancel or complete the future. */
internal suspend fun CompletableFuture<OperationResult>.awaitSdkResult(): OperationResult = suspendCancellableCoroutine { continuation ->
    whenComplete { value, failure ->
        if (continuation.isActive) {
            if (failure != null || value == null) continuation.resumeWithException(GatewayException((failure ?: IllegalStateException()).toFailure()))
            else continuation.resume(value)
        }
    }
}

private fun OperationResult.requireSuccess() { error?.let { throw GatewayException(it.toFailure()) } }
private fun OperationResult.toTerminal() = TransferTerminal(transferId?.toKey(), error?.toFailure())
internal fun ShareError.toFailure() = ShareFailure(when (category) {
    ErrorCategory.PERMISSION_DENIED -> ShareErrorKind.PERMISSION_DENIED
    ErrorCategory.DISCOVERY_FAILED -> ShareErrorKind.DISCOVERY_FAILED
    ErrorCategory.VERSION_MISMATCH -> ShareErrorKind.VERSION_MISMATCH
    ErrorCategory.PAIRING_REJECTED -> ShareErrorKind.PAIRING_REJECTED
    ErrorCategory.AUTHENTICATION_FAILED -> ShareErrorKind.AUTHENTICATION_FAILED
    ErrorCategory.IDENTITY_CHANGED -> ShareErrorKind.IDENTITY_CHANGED
    ErrorCategory.TRANSFER_REJECTED -> ShareErrorKind.TRANSFER_REJECTED
    ErrorCategory.CANCELLED -> ShareErrorKind.CANCELLED
    ErrorCategory.TIMEOUT -> ShareErrorKind.TIMEOUT
    ErrorCategory.NETWORK_LOST -> ShareErrorKind.NETWORK_LOST
    ErrorCategory.STORAGE_FAILED -> ShareErrorKind.STORAGE_FAILED
    ErrorCategory.INTEGRITY_FAILED -> ShareErrorKind.INTEGRITY_FAILED
    ErrorCategory.INVALID_INPUT -> ShareErrorKind.INVALID_INPUT
    ErrorCategory.RESOURCE_LIMIT -> ShareErrorKind.RESOURCE_LIMIT
}, platformError)

private fun Throwable.toFailure(): ShareFailure = when (this) {
    is ShareException -> error.toFailure()
    is GatewayException -> failure
    is CompletionException -> cause?.toFailure() ?: ShareFailure(ShareErrorKind.UNKNOWN)
    is SecurityException -> ShareFailure(ShareErrorKind.PERMISSION_DENIED)
    is IllegalArgumentException -> ShareFailure(ShareErrorKind.INVALID_INPUT)
    else -> ShareFailure(ShareErrorKind.UNKNOWN)
}
private inline fun <T> sdkCall(block: () -> T): T = try { block() } catch (error: Exception) { throw GatewayException(error.toFailure()) }
private fun DeviceKey.toSdkId() = DeviceId(UUID.fromString(value))
private fun DeviceId.toKey() = DeviceKey(value.toString())
private fun TransferId.toKey() = TransferKey(value.toString())
internal fun DeviceInfo.toSnapshot() = DeviceSnapshot(id.toKey(), name, when (platform) {
    Platform.WINDOWS -> DevicePlatform.WINDOWS
    Platform.ANDROID -> DevicePlatform.ANDROID
    Platform.IOS -> DevicePlatform.IOS
}, protocolVersion.major, protocolVersion.minor, Collections.unmodifiableList(ArrayList(addresses)), port, paired, lastSeenUnixMillis)
internal fun ShareContent.toClipboardContent(): ClipboardContent = when (this) {
    is ShareContent.Text -> ClipboardContent.Text(text)
    is ShareContent.Image -> ClipboardContent.Image(mimeType, bytes.copyOf())
}
private fun DiscoveryEvent.toGatewayEvent(): GatewayEvent = when (this) {
    is DiscoveryEvent.Online -> GatewayEvent.DeviceOnline(device.toSnapshot())
    is DiscoveryEvent.Updated -> GatewayEvent.DeviceUpdated(device.toSnapshot())
    is DiscoveryEvent.Offline -> GatewayEvent.DeviceOffline(device.id.toKey())
    is DiscoveryEvent.Error -> GatewayEvent.DiscoveryFailed(error.toFailure())
    is DiscoveryEvent.State -> GatewayEvent.DiscoveryStateChanged(when (state) {
        DiscoveryState.STARTING -> GatewayDiscoveryState.STARTING
        DiscoveryState.BROWSING -> GatewayDiscoveryState.BROWSING
        DiscoveryState.CHANGED -> GatewayDiscoveryState.CHANGED
        DiscoveryState.STOPPED -> GatewayDiscoveryState.STOPPED
    })
}
private fun ConnectionEvent.toGatewayEvent(): GatewayEvent = when (this) {
    is ConnectionEvent.Connected -> GatewayEvent.ConnectionChanged(deviceId.toKey(), true)
    is ConnectionEvent.Disconnected -> GatewayEvent.ConnectionChanged(deviceId.toKey(), false, error?.toFailure())
    is ConnectionEvent.PairingRequest -> GatewayEvent.PairingRequested(requestId, device.toSnapshot(), verificationCode)
    is ConnectionEvent.PairingResult -> GatewayEvent.PairingCompleted(requestId, deviceId.toKey(), error?.toFailure())
    is ConnectionEvent.IdentityChanged -> GatewayEvent.IdentityChanged(deviceId.toKey())
}
private fun TransferEvent.toGatewayEvent(): GatewayEvent = when (this) {
    is TransferEvent.IncomingOffer -> GatewayEvent.OfferReceived(IncomingOffer(requestId, transferId.toKey(), deviceId.toKey(), when (contentType) {
        ContentType.TEXT -> ContentKind.TEXT
        ContentType.IMAGE -> ContentKind.IMAGE
        ContentType.FILE -> ContentKind.FILE
    }, displayName, mimeType, byteCount))
    is TransferEvent.Progress -> GatewayEvent.ProgressReceived(TransferProgress(transferId.toKey(), transferred, byteCount))
    is TransferEvent.Result -> GatewayEvent.TransferCompleted(TransferTerminal(transferId.toKey(), error?.toFailure()))
}

/** The narrow app-owned seam keeps JVM tests off JNI; the public SDK API is unchanged. */
internal data class SdkSubmission(val id: Long, val result: CompletableFuture<OperationResult>)
internal interface SdkClient : AutoCloseable {
    fun initialize(config: SdkConfig): SdkSubmission
    fun start(): SdkSubmission
    fun stop(): SdkSubmission
    fun setForeground(value: Boolean)
    fun devices(): List<DeviceInfo>
    fun trustedDevices(): List<TrustedDevice>
    fun observeDiscovery(listener: (DiscoveryEvent) -> Unit): AutoCloseable
    fun observeConnections(listener: (ConnectionEvent) -> Unit): AutoCloseable
    fun observeTransfers(listener: (TransferEvent) -> Unit): AutoCloseable
    fun connect(device: DeviceId): SdkSubmission
    fun forgetDevice(device: DeviceId): SdkSubmission
    fun confirmPairing(requestId: Long, matches: Boolean): SdkSubmission
    fun rejectPairing(requestId: Long): SdkSubmission
    fun sendText(device: DeviceId, text: String): SdkSubmission
    fun sendImage(device: DeviceId, source: Uri, mimeType: String, byteCount: Long, displayName: String): SdkSubmission
    fun sendFile(device: DeviceId, source: Uri, displayName: String, byteCount: Long, mimeType: String): SdkSubmission
    fun accept(requestId: Long, destination: Uri): SdkSubmission
    fun reject(requestId: Long): SdkSubmission
    fun cancel(transfer: TransferId): SdkSubmission
    fun readClipboard(): SdkSubmission
}
private class ShareSdkClient(private val sdk: ShareSdk) : SdkClient {
    override fun initialize(config: SdkConfig) = sdk.initialize(config).submission()
    override fun start() = sdk.start().submission()
    override fun stop() = sdk.stop().submission()
    override fun setForeground(value: Boolean) = sdk.setForeground(value)
    override fun devices() = sdk.discovery.devices()
    override fun trustedDevices() = sdk.connections.trustedDevices()
    override fun observeDiscovery(listener: (DiscoveryEvent) -> Unit) = sdk.discovery.observe(listener)
    override fun observeConnections(listener: (ConnectionEvent) -> Unit) = sdk.connections.observe(listener)
    override fun observeTransfers(listener: (TransferEvent) -> Unit) = sdk.transfers.observe(listener)
    override fun connect(device: DeviceId) = sdk.connections.connect(device).submission()
    override fun forgetDevice(device: DeviceId) = sdk.connections.forgetDevice(device).submission()
    override fun confirmPairing(requestId: Long, matches: Boolean) = sdk.connections.confirmPairing(requestId, matches).submission()
    override fun rejectPairing(requestId: Long) = sdk.connections.rejectPairing(requestId).submission()
    override fun sendText(device: DeviceId, text: String) = sdk.transfers.sendText(device, text).submission()
    override fun sendImage(device: DeviceId, source: Uri, mimeType: String, byteCount: Long, displayName: String) = sdk.transfers.sendImage(device, source, mimeType, byteCount, displayName).submission()
    override fun sendFile(device: DeviceId, source: Uri, displayName: String, byteCount: Long, mimeType: String) = sdk.transfers.sendFile(device, source, displayName, byteCount, mimeType).submission()
    override fun accept(requestId: Long, destination: Uri) = sdk.transfers.accept(requestId, destination).submission()
    override fun reject(requestId: Long) = sdk.transfers.reject(requestId).submission()
    override fun cancel(transfer: TransferId) = sdk.transfers.cancel(transfer).submission()
    override fun readClipboard() = sdk.clipboard.readShareableContent().submission()
    override fun close() = sdk.close()
    private fun Operation.submission() = SdkSubmission(id, result)
}
