package com.unishare.sdk

import android.net.Uri

class TransferService internal constructor(private val sdk: ShareSdk) {
    fun sendText(deviceId: DeviceId, text: String): Operation = sdk.command(7, deviceId.value, text = text)
    fun sendImage(deviceId: DeviceId, source: Uri, mimeType: String, byteCount: Long, displayName: String = "image"): Operation =
        sdk.streamCommand(8, deviceId.value, source, false, displayName, mimeType, byteCount)
    fun sendFile(deviceId: DeviceId, source: Uri, displayName: String, byteCount: Long, mimeType: String = "application/octet-stream"): Operation =
        sdk.streamCommand(9, deviceId.value, source, false, displayName, mimeType, byteCount)
    /** Destination must be a deletable staging content URI, never a final user file. */
    fun accept(requestId: Long, destination: Uri): Operation = sdk.streamCommand(10, null, destination, true, request = requestId)
    fun reject(requestId: Long): Operation = sdk.command(11, request = requestId)
    fun cancel(transferId: TransferId): Operation = sdk.command(12, transferId.value)
    fun observe(listener: (TransferEvent) -> Unit): AutoCloseable = sdk.observe(TransferEvent::class.java, listener)
}
