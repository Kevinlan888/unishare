package com.unishare.sdk

import java.util.UUID
import java.util.Collections
import java.util.concurrent.CompletableFuture

data class DeviceId(val value: UUID)
data class TransferId(val value: UUID)
enum class Platform { WINDOWS, ANDROID, IOS }
enum class SdkState { UNINITIALIZED, INITIALIZED, RUNNING, STOPPED, CLOSED }
enum class DiscoveryState { STARTING, BROWSING, CHANGED, STOPPED }
enum class ErrorCategory {
    PERMISSION_DENIED, DISCOVERY_FAILED, VERSION_MISMATCH, PAIRING_REJECTED,
    AUTHENTICATION_FAILED, IDENTITY_CHANGED, TRANSFER_REJECTED, CANCELLED,
    TIMEOUT, NETWORK_LOST, STORAGE_FAILED, INTEGRITY_FAILED, INVALID_INPUT, RESOURCE_LIMIT
}
data class ShareError(val category: ErrorCategory, val platformError: Long? = null)
class ShareException(val error: ShareError) : RuntimeException(error.toString())
data class ProtocolVersion(val major: Int, val minor: Int)
class DeviceInfo internal constructor(
    val id: DeviceId, val name: String, val platform: Platform, val protocolVersion: ProtocolVersion,
    addresses: List<String>, val port: Int, val paired: Boolean, val lastSeenUnixMillis: Long
) {
    val addresses: List<String> = Collections.unmodifiableList(ArrayList(addresses))
}
class TrustedDevice internal constructor(val id: DeviceId, fingerprint: ByteArray) {
    private val fingerprint = fingerprint.copyOf()
    val publicKeyFingerprint: ByteArray get() = fingerprint.copyOf()
}
enum class ContentType { TEXT, IMAGE, FILE }
sealed class ShareContent {
    data class Text(val text: String) : ShareContent()
    class Image(val mimeType: String, bytes: ByteArray) : ShareContent() {
        private val value = bytes.copyOf()
        val bytes: ByteArray get() = value.copyOf()
    }
}
data class OperationResult(val operationId: Long, val error: ShareError? = null,
                           val transferId: TransferId? = null, val content: ShareContent? = null) {
    val isSuccess: Boolean get() = error == null
}
/** IDs are scoped to the owning Kotlin SDK; native rejection completes the future. */
class Operation internal constructor(val id: Long, private val completion: CompletableFuture<OperationResult>) {
    // A dependent future prevents callers from completing/cancelling the SDK's owned future.
    val result: CompletableFuture<OperationResult> get() = completion.thenApply { it }
}
data class SdkConfig(
    val deviceName: String, val maxConnections: Int = 8, val maxPendingRequests: Int = 4,
    val maxBufferBytes: Long = 4L * 1024 * 1024, val maxTextBytes: Long = 8L * 1024 * 1024,
    val maxImageBytes: Long = 100L * 1024 * 1024, val maxFileBytes: Long = 20L * 1024 * 1024 * 1024,
    val controlFrameBytes: Int = 65536, val dataChunkBytes: Int = 262144,
    val requestTimeoutSeconds: Int = 60, val offlineDebounceSeconds: Int = 5
)
