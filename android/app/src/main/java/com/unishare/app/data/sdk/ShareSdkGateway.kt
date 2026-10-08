package com.unishare.app.data.sdk

import android.net.Uri
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.TransferKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class GatewayEventEnvelope(val event: GatewayEvent, val generation: Long)

/** One application session owns this gateway and its observer handle. */
interface ShareSdkGateway : AutoCloseable {
    val events: Flow<GatewayEvent>
    /** Source-stamped event stream used to retire callbacks across stop/start boundaries. */
    val eventEnvelopes: Flow<GatewayEventEnvelope>
        get() = events.map { GatewayEventEnvelope(it, eventGeneration) }
    val eventGeneration: Long
        get() = 0L
    suspend fun initialize(config: GatewayConfig)
    fun subscribe(): AutoCloseable
    suspend fun start()
    suspend fun stop()
    fun setForeground(value: Boolean)
    fun devices(): List<DeviceSnapshot>
    fun trustedDevices(): Set<DeviceKey>
    suspend fun connect(deviceId: DeviceKey)
    suspend fun forgetDevice(deviceId: DeviceKey)
    suspend fun confirmPairing(requestId: Long, codeMatches: Boolean)
    suspend fun rejectPairing(requestId: Long)
    fun beginSendText(deviceId: DeviceKey, text: String): PendingSend
    fun beginSendImage(deviceId: DeviceKey, source: Uri, mimeType: String, byteCount: Long, displayName: String = "image"): PendingSend
    fun beginSendFile(deviceId: DeviceKey, source: Uri, displayName: String, byteCount: Long, mimeType: String = "application/octet-stream"): PendingSend
    /** Destination is a private staging URI; completion precedes publication. */
    suspend fun accept(requestId: Long, destination: Uri): TransferTerminal
    suspend fun reject(requestId: Long)
    suspend fun cancel(transferId: TransferKey)
    /** Explicit focused foreground user action only. */
    suspend fun readClipboard(): ClipboardContent?
}
