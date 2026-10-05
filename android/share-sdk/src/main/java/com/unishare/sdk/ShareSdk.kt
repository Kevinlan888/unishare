package com.unishare.sdk

import android.content.Context
import android.net.Uri
import com.unishare.sdk.internal.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Owns native services. The host explicitly marks foreground lifecycle and closes the SDK. */
class ShareSdk internal constructor(private val backend: ApiBackend, executor: Executor?) : AutoCloseable {
    @JvmOverloads constructor(context: Context, storageName: String = "unishare", callbackExecutor: Executor? = null) :
        this(JniBackend(context.applicationContext, storageName), callbackExecutor)

    private val gate = Any()
    private val callbackGate = Any()
    private val queueGate = Any()
    private val commands = Executors.newSingleThreadExecutor { Thread(it, "unishare-submit").apply { isDaemon = true } }
    private val streamAcquisition = Executors.newSingleThreadExecutor { Thread(it, "unishare-uri").apply { isDaemon = true } }
    private val dispatch = Executors.newSingleThreadExecutor { Thread(it, "unishare-callback").apply { isDaemon = true } }
    private val callbackExecutor = executor ?: Executor { it.run() }
    private val pending = LinkedHashMap<Long, CompletableFuture<OperationResult>>()
    private val preparing = HashSet<Long>()
    private val observers = LinkedHashMap<Long, (Any) -> Unit>()
    private var nextId = 1L
    @Volatile private var closed = false
    private var initializing = false
    private var ready = false
    private var initializeId = 0L
    private var preparationGeneration = 0L
    private val deliveries = java.util.ArrayDeque<() -> Unit>()
    private var delivering = false

    val discovery = DeviceDiscovery(this)
    val connections = ConnectionManager(this)
    val transfers = TransferService(this)
    val clipboard = ClipboardService(this)
    val state: SdkState get() = synchronized(gate) {
        if (closed) SdkState.CLOSED else try { SdkState.entries[backend.state()] } catch (e: NativePortException) { throw ShareException(mapped(e)) }
    }

    private val receiver = object : SdkNative.Receiver {
        override fun completion(key: Long, copiedResult: ByteArray) {
            val bytes = copiedResult.copyOf()
            enqueue {
                val result = ApiPackets.result(key, bytes)
                finish(key, result)
            }
        }
        override fun event(copiedEvent: ByteArray) {
            val bytes = copiedEvent.copyOf()
            enqueue {
                val event = ApiPackets.event(bytes)
                for ((id, listener) in synchronized(gate) { observers.toList() }) {
                    if (closed) break
                    if (synchronized(gate) { observers.containsKey(id) }) try { listener(event) } catch (_: Exception) { /* one host observer cannot retire the queue */ }
                }
            }
        }
    }
    fun initialize(config: SdkConfig): Operation = submit(requireReady = false) { key, _ ->
        if (initializing) throw ShareException(ShareError(ErrorCategory.INVALID_INPUT))
        if (config.maxConnections !in 1..8 || config.maxPendingRequests !in 1..4 || config.maxBufferBytes !in 1..4194304L ||
            config.maxTextBytes < 0 || config.maxImageBytes < 0 || config.maxFileBytes < 0 ||
            config.controlFrameBytes !in 1..65536 || config.dataChunkBytes !in 1..262144 ||
            config.requestTimeoutSeconds <= 0 || config.offlineDebounceSeconds < 0)
            throw ShareException(ShareError(ErrorCategory.INVALID_INPUT))
        initializing = true
        initializeId = key
        backend.create(ApiPackets.config(config), key, receiver)
    }
    fun start(): Operation = command(1)
    fun stop(): Operation = synchronized(gate) { retirePreparation(); command(2) }
    fun setForeground(value: Boolean) = synchronized(gate) {
        check(!closed) { "SDK closed" }; if (!value) retirePreparation(); backend.foreground(value)
    }
    private fun retirePreparation() {
        preparationGeneration++
        for (id in preparing.toList()) enqueue { finish(id, OperationResult(id, ShareError(ErrorCategory.CANCELLED))) }
    }

    private fun submit(requireReady: Boolean = true, prepare: (() -> Long)? = null,
                       cleanup: ((Long, Boolean) -> Unit)? = null, work: (Long, Long) -> Unit): Operation = synchronized(gate) {
        check(!closed) { "SDK closed" }
        val id = nextId++; val future = CompletableFuture<OperationResult>(); pending[id] = future
        val generation = preparationGeneration
        if (prepare != null) preparing.add(id)
        (if (prepare == null) commands else streamAcquisition).execute {
            var resource = 0L
            var submitted = false
            try {
                synchronized(gate) {
                    if (closed) return@execute
                    if (requireReady && !ready) throw ShareException(ShareError(ErrorCategory.INVALID_INPUT))
                    if (prepare != null && generation != preparationGeneration) throw ShareException(ShareError(ErrorCategory.CANCELLED))
                }
                // A provider can block. Close retires the operation while acquisition is in flight;
                // the worker cleans up its late stream without touching the destroyed SDK.
                resource = prepare?.invoke() ?: 0
                synchronized(gate) {
                    if (!closed) {
                        if (prepare != null && generation != preparationGeneration) throw ShareException(ShareError(ErrorCategory.CANCELLED))
                        work(id, resource); submitted = true
                    }
                }
            } catch (failure: Exception) {
                val error = when (failure) {
                    is NativePortException -> mapped(failure)
                    is ShareException -> failure.error
                    is SecurityException -> ShareError(ErrorCategory.PERMISSION_DENIED)
                    else -> ShareError(ErrorCategory.INVALID_INPUT)
                }
                enqueue { finish(id, OperationResult(id, error)) }
            } finally {
                try { if (resource != 0L) cleanup?.invoke(resource, submitted) }
                finally { synchronized(gate) { preparing.remove(id) } }
            }
        }
        Operation(id, future)
    }
    internal fun command(command: Int, uuid: UUID? = null, request: Long = 0, stream: Long = 0,
                         number: Long = 0, text: String = "", name: String = "", mime: String = "",
                         bytes: ByteArray = byteArrayOf(), size: Long = 0): Operation {
        val copiedBytes = bytes.copyOf()
        return submit { key, _ -> backend.submit(command, ApiPackets.uuid(uuid), request, stream, number,
            text.toByteArray(Charsets.UTF_8), name.toByteArray(Charsets.UTF_8), mime.toByteArray(Charsets.UTF_8), copiedBytes, size, key) }
    }
    internal fun streamCommand(command: Int, uuid: UUID?, uri: Uri, destination: Boolean, name: String = "", mime: String = "",
                               size: Long = 0, request: Long = 0): Operation = submit(
        prepare = {
            if (uri.scheme != "content" || size < 0) throw ShareException(ShareError(ErrorCategory.INVALID_INPUT))
            backend.open(uri.toString(), destination)
        }, cleanup = { stream, submitted ->
            try { if (destination && !submitted) backend.abort(stream) } finally { backend.release(stream) }
        }) { key, stream ->
        backend.submit(command, ApiPackets.uuid(uuid), request, stream, 0, byteArrayOf(), name.toByteArray(Charsets.UTF_8),
            mime.toByteArray(Charsets.UTF_8), byteArrayOf(), size, key)
    }
    internal fun devices(): List<DeviceInfo> = synchronized(gate) { ensureReady(); native { ApiPackets.devices(backend.snapshot(false)) } }
    internal fun trustedDevices(): List<TrustedDevice> = synchronized(gate) { ensureReady(); native { ApiPackets.trusted(backend.snapshot(true)) } }
    private fun ensureReady() { check(!closed) { "SDK closed" }; check(ready) { "Initialize must complete successfully first" } }
    private fun <T> native(work: () -> T): T = try { work() } catch (e: NativePortException) { throw ShareException(mapped(e)) }
    private fun mapped(e: NativePortException) = ShareError(ErrorCategory.entries[e.code - 1], e.platformError)

    internal fun <T : Any> observe(type: Class<T>, listener: (T) -> Unit): AutoCloseable = synchronized(gate) {
        check(!closed) { "SDK closed" }; val id = nextId++
        observers[id] = { if (type.isInstance(it)) listener(type.cast(it)!!) }
        AutoCloseable { synchronized(gate) { observers.remove(id) } }
    }
    private fun finish(id: Long, result: OperationResult) {
        val future = synchronized(gate) {
            val future = pending.remove(id) ?: return
            if (id == initializeId && result.isSuccess) ready = true
            future
        }
        future.complete(result)
    }
    // Only a trampoline thread submits to the host Executor. Even Runnable::run is never inline
    // on the submitting JNI stack. One delivery at a time preserves device/event order.
    private fun enqueue(work: () -> Unit) = synchronized(queueGate) {
        if (!closed) { deliveries.add(work); if (!delivering) { delivering = true; pump() } }
    }
    private fun pump() {
        dispatch.execute {
            val task = Runnable {
                synchronized(callbackGate) {
                    if (!closed) {
                        val work = synchronized(queueGate) { deliveries.poll() }
                        try { work?.invoke() } finally {
                            synchronized(queueGate) {
                                if (!closed && deliveries.isNotEmpty()) pump() else delivering = false
                            }
                        }
                    }
                }
            }
            try { callbackExecutor.execute(task) } catch (_: RejectedExecutionException) { task.run() }
        }
    }
    override fun close(): Unit = synchronized(callbackGate) {
        var failure: Exception? = null
        val cancelled = synchronized(gate) {
            if (closed) return
            closed = true
            // Native destroy drains native callbacks. They only enqueue, never invoke host code.
            try { backend.close() } catch (error: Exception) { failure = error }
            observers.clear(); synchronized(queueGate) { deliveries.clear() }
            val cancelled = pending.toList(); pending.clear(); preparing.clear()
            commands.shutdown(); streamAcquisition.shutdown(); dispatch.shutdown()
            cancelled
        }
        // Host continuations may read state on another thread. Never hold the command gate.
        for ((id, future) in cancelled) future.complete(OperationResult(id, ShareError(ErrorCategory.CANCELLED)))
        failure?.let { throw it }
        Unit
    }
}
