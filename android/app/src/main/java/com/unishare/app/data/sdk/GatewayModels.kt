package com.unishare.app.data.sdk

import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.OperationKey
import com.unishare.app.domain.ShareErrorKind
import com.unishare.app.domain.TransferKey
import kotlinx.coroutines.Deferred

data class GatewayConfig(val deviceName: String)

enum class DevicePlatform { WINDOWS, ANDROID, IOS }
enum class GatewayDiscoveryState { STARTING, BROWSING, CHANGED, STOPPED }

data class DeviceSnapshot(
    val id: DeviceKey,
    val name: String,
    val platform: DevicePlatform,
    val protocolMajor: Int = 1,
    val protocolMinor: Int = 0,
    val addresses: List<String> = emptyList(),
    val port: Int = 0,
    val paired: Boolean = false,
    val lastSeenUnixMillis: Long = 0,
)

data class ShareFailure(val kind: ShareErrorKind, val platformError: Long? = null)

/** Only stable categories leave the SDK boundary; exception messages carry no host data. */
class GatewayException(val failure: ShareFailure) : RuntimeException(failure.kind.name)

data class OutgoingBinding(val operationId: OperationKey, val transferId: TransferKey)
data class TransferTerminal(val transferId: TransferKey?, val failure: ShareFailure? = null)
data class PendingSend(val operationId: OperationKey, val completion: Deferred<TransferTerminal>)
data class IncomingOffer(
    val requestId: Long,
    val transferId: TransferKey,
    val deviceId: DeviceKey,
    val kind: ContentKind,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
)
data class TransferProgress(val transferId: TransferKey, val transferredBytes: Long, val totalBytes: Long)

/** Process-only payloads. Never attach these to history or diagnostics. */
sealed interface ClipboardContent {
    data class Text(val text: String) : ClipboardContent
    class Image(val mimeType: String, bytes: ByteArray) : ClipboardContent {
        private val value = bytes.copyOf()
        val bytes: ByteArray get() = value.copyOf()
    }
}

sealed interface GatewayEvent {
    data class DeviceOnline(val device: DeviceSnapshot) : GatewayEvent
    data class DeviceUpdated(val device: DeviceSnapshot) : GatewayEvent
    data class DeviceOffline(val deviceId: DeviceKey) : GatewayEvent
    data class DiscoveryFailed(val failure: ShareFailure) : GatewayEvent
    data class DiscoveryStateChanged(val state: GatewayDiscoveryState) : GatewayEvent
    data class ConnectionChanged(val deviceId: DeviceKey, val connected: Boolean, val failure: ShareFailure? = null) : GatewayEvent
    data class PairingRequested(val requestId: Long, val device: DeviceSnapshot, val verificationCode: String) : GatewayEvent
    data class PairingCompleted(val requestId: Long, val deviceId: DeviceKey, val failure: ShareFailure? = null) : GatewayEvent
    data class IdentityChanged(val deviceId: DeviceKey) : GatewayEvent
    data class OfferReceived(val offer: IncomingOffer) : GatewayEvent
    data class OutgoingTransferRegistered(val binding: OutgoingBinding) : GatewayEvent
    data class ProgressReceived(val progress: TransferProgress) : GatewayEvent
    data class TransferCompleted(val terminal: TransferTerminal) : GatewayEvent
}
