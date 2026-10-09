package com.unishare.app.data.sdk

import android.net.Uri
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.TransferKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SessionState { UNINITIALIZED, INITIALIZING, READY, RUNNING, STOPPING, STOPPED, FAULTED, CLOSED }

/** A unique token per transition also detects stop/start when StateFlow conflates both states. */
internal class SessionSnapshotToken(val state: SessionState)
/** Application-scoped owner. UI, repositories and services share this instance. */
class ShareSessionManager(private val gateway: ShareSdkGateway, scope: CoroutineScope) {
    private val lifecycle = Mutex()
    private val eventGate = Any()
    private val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(SessionState.UNINITIALIZED)
    @Volatile internal var snapshotToken = SessionSnapshotToken(SessionState.UNINITIALIZED)
        private set
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private val delivered = MutableSharedFlow<GatewayEventEnvelope>()
    internal val sequencedEvents = delivered.asSharedFlow()
    val events: Flow<GatewayEvent> = delivered.map { it.event }
    private var acceptedEventGeneration = gateway.eventGeneration
    private var observations: AutoCloseable? = null
    private var delivery: Job? = null
    @Volatile private var initialized = false
    @Volatile private var closed = false
    @Volatile private var closing = false
    private var running = false

    suspend fun initialize(config: GatewayConfig) = withContext(NonCancellable) {
        lifecycle.withLock {
            ensureOpen()
            if (initialized) return@withLock
            publishState(SessionState.INITIALIZING)
            try {
                gateway.initialize(config)
                // Start collection before opening native observers, so the first callback is kept.
                delivery = sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    gateway.eventEnvelopes.collect { if (!closed) delivered.emit(it) }
                }
                try { observations = gateway.subscribe() } catch (failure: Exception) {
                    delivery?.cancel(); delivery = null
                    throw failure
                }
                initialized = true
                publishState(SessionState.READY)
            } catch (failure: Exception) {
                publishState(SessionState.FAULTED)
                throw failure
            }
        }
    }

    suspend fun start() = withContext(NonCancellable) {
        lifecycle.withLock {
            ensureOpen()
            check(initialized) { "Initialize must complete successfully first" }
            if (running) return@withLock
            try {
                gateway.setForeground(true)
                gateway.start()
                running = true
                publishState(SessionState.RUNNING)
            } catch (failure: Exception) {
                try { gateway.setForeground(false) } catch (_: Exception) { }
                publishState(SessionState.FAULTED)
                throw failure
            }
        }
    }

    suspend fun stop() = withContext(NonCancellable) {
        lifecycle.withLock {
            if (closed || !running) return@withLock
            stopCore()
        }
    }

    private suspend fun stopCore() {
        publishState(SessionState.STOPPING)
        try {
            gateway.stop()
            synchronized(eventGate) { acceptedEventGeneration = gateway.eventGeneration }
            running = false
            // The completed stop operation drains SDK work before foreground is withdrawn.
            gateway.setForeground(false)
            publishState(SessionState.STOPPED)
        } catch (failure: Exception) {
            publishState(SessionState.FAULTED)
            throw failure
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        lifecycle.withLock {
            if (closed) return@withLock
            closing = true
            var failure: Exception? = null
            if (running) try { stopCore() } catch (error: Exception) { failure = error }
            closed = true
            synchronized(eventGate) { acceptedEventGeneration = Long.MIN_VALUE }
            delivery?.cancel(); sessionScope.cancel()
            try { observations?.close() } catch (error: Exception) { failure = failure ?: error }
            observations = null
            try { gateway.close() } catch (error: Exception) { failure = failure ?: error }
            publishState(SessionState.CLOSED)
            failure?.let { throw it }
        }
    }

    fun requireReady() {
        ensureOpen()
        check(initialized && snapshotToken.state in setOf(SessionState.READY, SessionState.RUNNING, SessionState.STOPPED)) { "Session is not ready" }
    }
    private fun publishState(value: SessionState) {
        snapshotToken = SessionSnapshotToken(value)
        mutableState.value = value
    }
    private fun ensureOpen() { check(!closed && !closing) { "Session is closed" } }

    internal fun applyIfCurrentEvent(delivery: GatewayEventEnvelope, action: () -> Unit): Boolean = synchronized(eventGate) {
        if (delivery.generation != acceptedEventGeneration) false else { action(); true }
    }

    fun devices(): List<DeviceSnapshot> { requireReady(); return gateway.devices() }
    fun trustedDevices(): Set<DeviceKey> { requireReady(); return gateway.trustedDevices() }
    suspend fun connect(deviceId: DeviceKey) { requireReady(); gateway.connect(deviceId) }
    suspend fun forgetDevice(deviceId: DeviceKey) { requireReady(); gateway.forgetDevice(deviceId) }
    suspend fun confirmPairing(requestId: Long, codeMatches: Boolean) { requireReady(); gateway.confirmPairing(requestId, codeMatches) }
    suspend fun rejectPairing(requestId: Long) { requireReady(); gateway.rejectPairing(requestId) }
    fun beginSendText(deviceId: DeviceKey, text: String): PendingSend { requireReady(); return gateway.beginSendText(deviceId, text) }
    fun beginSendImage(deviceId: DeviceKey, source: Uri, mimeType: String, byteCount: Long, displayName: String = "image"): PendingSend {
        requireReady(); return gateway.beginSendImage(deviceId, source, mimeType, byteCount, displayName)
    }
    fun beginSendFile(deviceId: DeviceKey, source: Uri, displayName: String, byteCount: Long, mimeType: String = "application/octet-stream"): PendingSend {
        requireReady(); return gateway.beginSendFile(deviceId, source, displayName, byteCount, mimeType)
    }
    suspend fun accept(requestId: Long, destination: Uri): TransferTerminal { requireReady(); return gateway.accept(requestId, destination) }
    suspend fun reject(requestId: Long) { requireReady(); gateway.reject(requestId) }
    suspend fun cancel(transferId: TransferKey) { requireReady(); gateway.cancel(transferId) }
    suspend fun readClipboard(): ClipboardContent? { requireReady(); return gateway.readClipboard() }
}
