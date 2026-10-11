package com.unishare.app.domain

@JvmInline
value class DeviceKey(val value: String)

@JvmInline
value class ActivityKey(val value: String)

@JvmInline
value class TransferKey(val value: String)

@JvmInline
value class OperationKey(val value: String)

enum class ContentKind { TEXT, IMAGE, FILE }

enum class ShareDirection { INCOMING, OUTGOING }

enum class ShareOrigin { DIRECT, CLIPBOARD }

enum class ActivityStatus {
    AWAITING_PEER, TRANSFERRING, COMPLETED, REJECTED, CANCELLED, FAILED;

    val isTerminal: Boolean
        get() = when (this) {
            COMPLETED, REJECTED, CANCELLED, FAILED -> true
            AWAITING_PEER, TRANSFERRING -> false
        }
}

enum class ShareErrorKind {
    UNKNOWN,
    PERMISSION_DENIED,
    DISCOVERY_FAILED,
    VERSION_MISMATCH,
    PAIRING_REJECTED,
    AUTHENTICATION_FAILED,
    IDENTITY_CHANGED,
    TRANSFER_REJECTED,
    CANCELLED,
    TIMEOUT,
    NETWORK_LOST,
    STORAGE_FAILED,
    INTEGRITY_FAILED,
    INVALID_INPUT,
    RESOURCE_LIMIT,
    INTERRUPTED,
}

enum class ReceiveDecision { AUTO_ACCEPT, PROMPT }

/** Summary metadata only: never attach payloads, previews, or a sending source URI. */
data class ShareActivity(
    val id: ActivityKey,
    val deviceId: DeviceKey,
    val direction: ShareDirection,
    val kind: ContentKind,
    val origin: ShareOrigin,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val transferId: TransferKey? = null,
    val operationId: OperationKey? = null,
    val retryOf: ActivityKey? = null,
    val status: ActivityStatus = ActivityStatus.AWAITING_PEER,
    val transferredBytes: Long = 0,
    val completedAtMillis: Long? = null,
    val errorKind: ShareErrorKind? = null,
    /** A published incoming image/file URI, never a private staging or sending URI. */
    val finalContentUri: String? = null,
)

/** Preview content lives outside activity summaries; direct content is persisted with its activity. */
sealed interface ActivityPreview {
    data class Text(val text: String) : ActivityPreview {
        override fun toString(): String = "Text(length=${text.length})"
    }

    data class Image(val reference: String, val encodedBytes: ByteArray? = null) : ActivityPreview {
        override fun toString(): String = "Image(hasEncodedBytes=${encodedBytes != null})"
    }
}
