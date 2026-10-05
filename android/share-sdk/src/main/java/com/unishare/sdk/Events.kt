package com.unishare.sdk

sealed class DiscoveryEvent {
    data class Online(val device: DeviceInfo) : DiscoveryEvent()
    data class Updated(val device: DeviceInfo) : DiscoveryEvent()
    data class Offline(val device: DeviceInfo) : DiscoveryEvent()
    data class Error(val error: ShareError) : DiscoveryEvent()
    data class State(val state: DiscoveryState) : DiscoveryEvent()
}
sealed class ConnectionEvent {
    data class Connected(val deviceId: DeviceId) : ConnectionEvent()
    data class Disconnected(val deviceId: DeviceId, val error: ShareError?) : ConnectionEvent()
    data class PairingRequest(val requestId: Long, val device: DeviceInfo, val verificationCode: String) : ConnectionEvent()
    data class PairingResult(val requestId: Long, val deviceId: DeviceId, val error: ShareError?) : ConnectionEvent()
    data class IdentityChanged(val deviceId: DeviceId) : ConnectionEvent()
}
sealed class TransferEvent {
    data class IncomingOffer(val requestId: Long, val transferId: TransferId, val deviceId: DeviceId,
                             val contentType: ContentType, val displayName: String,
                             val mimeType: String, val byteCount: Long) : TransferEvent()
    data class Progress(val transferId: TransferId, val transferred: Long, val byteCount: Long) : TransferEvent()
    data class Result(val transferId: TransferId, val error: ShareError?) : TransferEvent()
}
