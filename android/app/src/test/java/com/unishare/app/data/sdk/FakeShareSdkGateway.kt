package com.unishare.app.data.sdk

import android.net.Uri
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.TransferKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeShareSdkGateway : ShareSdkGateway {
    override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 20)
    override var eventGeneration = 0L
        private set
    val calls = mutableListOf<String>()
    var initializeFailure: Exception? = null
    var stopCompletion: CompletableDeferred<Unit>? = null
    var forgetCompletion: CompletableDeferred<Unit>? = null
    var connectCompletion: CompletableDeferred<Unit>? = null
    var startFailure: Exception? = null
    var snapshots = emptyList<DeviceSnapshot>()
    var trusted = emptySet<DeviceKey>()
    var trustedSnapshotProvider: (() -> Set<DeviceKey>)? = null
    var onSubscribe: (() -> Unit)? = null
    override suspend fun initialize(config: GatewayConfig) { calls += "initialize"; initializeFailure?.let { throw it } }
    override fun subscribe(): AutoCloseable {
        calls += "subscribe"
        onSubscribe?.invoke()
        return AutoCloseable { calls += "unsubscribe" }
    }
    override suspend fun start() { calls += "start"; startFailure?.let { throw it } }
    override suspend fun stop() { calls += "stop"; stopCompletion?.await(); eventGeneration++; calls += "stopped" }
    override fun setForeground(value: Boolean) { calls += "foreground:$value" }
    override fun close() { calls += "close" }
    override fun devices() = snapshots
    override fun trustedDevices() = trustedSnapshotProvider?.invoke() ?: trusted
    override suspend fun connect(deviceId: DeviceKey) { calls += "connect:${deviceId.value}"; connectCompletion?.await() }
    override suspend fun forgetDevice(deviceId: DeviceKey) { calls += "forget:${deviceId.value}"; forgetCompletion?.await(); trusted = trusted - deviceId }
    override suspend fun confirmPairing(requestId: Long, codeMatches: Boolean) = Unit
    override suspend fun rejectPairing(requestId: Long) = Unit
    override fun beginSendText(deviceId: DeviceKey, text: String): PendingSend = error("unused")
    override fun beginSendImage(deviceId: DeviceKey, source: Uri, mimeType: String, byteCount: Long, displayName: String): PendingSend = error("unused")
    override fun beginSendFile(deviceId: DeviceKey, source: Uri, displayName: String, byteCount: Long, mimeType: String): PendingSend = error("unused")
    override suspend fun accept(requestId: Long, destination: Uri): TransferTerminal = error("unused")
    override suspend fun reject(requestId: Long) = Unit
    override suspend fun cancel(transferId: TransferKey) = Unit
    override suspend fun readClipboard(): ClipboardContent? = null
}
