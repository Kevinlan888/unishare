package com.unishare.app.data.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.unishare.app.domain.*

/** Summary metadata only; message bodies and image thumbnails live in ActivityPreviewEntity. */
@Entity(tableName = "activities", indices = [
    Index(value = ["deviceId", "updatedAt"]),
    Index(value = ["status", "updatedAt"]),
])
data class ActivityEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val direction: ShareDirection,
    val kind: ContentKind,
    val origin: ShareOrigin,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val transferId: String?,
    val operationId: String?,
    val retryOf: String?,
    val status: ActivityStatus,
    val transferredBytes: Long,
    val completedAt: Long?,
    val errorKind: ShareErrorKind?,
    val finalContentUri: String?,
) {
    fun toDomain() = ShareActivity(
        id = ActivityKey(id), deviceId = DeviceKey(deviceId), direction = direction,
        kind = kind, origin = origin, displayName = displayName, mimeType = mimeType,
        byteCount = byteCount, createdAtMillis = createdAt, updatedAtMillis = updatedAt,
        transferId = transferId?.let(::TransferKey), operationId = operationId?.let(::OperationKey),
        retryOf = retryOf?.let(::ActivityKey), status = status, transferredBytes = transferredBytes,
        completedAtMillis = completedAt, errorKind = errorKind, finalContentUri = finalContentUri,
    )

    companion object {
        fun fromDomain(activity: ShareActivity) = ActivityEntity(
            id = activity.id.value, deviceId = activity.deviceId.value, direction = activity.direction,
            kind = activity.kind, origin = activity.origin,
            displayName = activity.displayName.replace('\\', '/').substringAfterLast('/')
                .filterNot { it.isISOControl() }.trim(),
            mimeType = activity.mimeType, byteCount = activity.byteCount,
            createdAt = activity.createdAtMillis, updatedAt = activity.updatedAtMillis,
            transferId = activity.transferId?.value, operationId = activity.operationId?.value,
            retryOf = activity.retryOf?.value, status = activity.status,
            transferredBytes = activity.transferredBytes, completedAt = activity.completedAtMillis,
            errorKind = activity.errorKind,
            finalContentUri = activity.finalContentUri.takeIf {
                activity.status == ActivityStatus.COMPLETED &&
                    activity.direction == ShareDirection.INCOMING &&
                    (activity.kind == ContentKind.IMAGE || activity.kind == ContentKind.FILE)
            },
        )
    }
}

/** Stable names instead of enum ordinals; malformed storage never appears in diagnostics. */
class ActivityConverters {
    @TypeConverter fun directionToString(value: ShareDirection): String = when (value) {
        ShareDirection.INCOMING -> "INCOMING"
        ShareDirection.OUTGOING -> "OUTGOING"
    }
    @TypeConverter fun stringToDirection(value: String): ShareDirection = when (value) {
        "INCOMING" -> ShareDirection.INCOMING
        "OUTGOING" -> ShareDirection.OUTGOING
        else -> error("Invalid stored direction")
    }
    @TypeConverter fun kindToString(value: ContentKind): String = when (value) {
        ContentKind.TEXT -> "TEXT"
        ContentKind.IMAGE -> "IMAGE"
        ContentKind.FILE -> "FILE"
    }
    @TypeConverter fun stringToKind(value: String): ContentKind = when (value) {
        "TEXT" -> ContentKind.TEXT
        "IMAGE" -> ContentKind.IMAGE
        "FILE" -> ContentKind.FILE
        else -> error("Invalid stored content kind")
    }
    @TypeConverter fun originToString(value: ShareOrigin): String = when (value) {
        ShareOrigin.DIRECT -> "DIRECT"
        ShareOrigin.CLIPBOARD -> "CLIPBOARD"
    }
    @TypeConverter fun stringToOrigin(value: String): ShareOrigin = when (value) {
        "DIRECT" -> ShareOrigin.DIRECT
        "CLIPBOARD" -> ShareOrigin.CLIPBOARD
        else -> error("Invalid stored origin")
    }
    @TypeConverter fun statusToString(value: ActivityStatus): String = when (value) {
        ActivityStatus.AWAITING_PEER -> "AWAITING_PEER"
        ActivityStatus.TRANSFERRING -> "TRANSFERRING"
        ActivityStatus.COMPLETED -> "COMPLETED"
        ActivityStatus.REJECTED -> "REJECTED"
        ActivityStatus.CANCELLED -> "CANCELLED"
        ActivityStatus.FAILED -> "FAILED"
    }
    @TypeConverter fun stringToStatus(value: String): ActivityStatus = when (value) {
        "AWAITING_PEER" -> ActivityStatus.AWAITING_PEER
        "TRANSFERRING" -> ActivityStatus.TRANSFERRING
        "COMPLETED" -> ActivityStatus.COMPLETED
        "REJECTED" -> ActivityStatus.REJECTED
        "CANCELLED" -> ActivityStatus.CANCELLED
        "FAILED" -> ActivityStatus.FAILED
        else -> error("Invalid stored status")
    }
    @TypeConverter fun errorToString(value: ShareErrorKind?): String? = when (value) {
        null -> null
        ShareErrorKind.UNKNOWN -> "UNKNOWN"
        ShareErrorKind.PERMISSION_DENIED -> "PERMISSION_DENIED"
        ShareErrorKind.DISCOVERY_FAILED -> "DISCOVERY_FAILED"
        ShareErrorKind.VERSION_MISMATCH -> "VERSION_MISMATCH"
        ShareErrorKind.PAIRING_REJECTED -> "PAIRING_REJECTED"
        ShareErrorKind.AUTHENTICATION_FAILED -> "AUTHENTICATION_FAILED"
        ShareErrorKind.IDENTITY_CHANGED -> "IDENTITY_CHANGED"
        ShareErrorKind.TRANSFER_REJECTED -> "TRANSFER_REJECTED"
        ShareErrorKind.CANCELLED -> "CANCELLED"
        ShareErrorKind.TIMEOUT -> "TIMEOUT"
        ShareErrorKind.NETWORK_LOST -> "NETWORK_LOST"
        ShareErrorKind.STORAGE_FAILED -> "STORAGE_FAILED"
        ShareErrorKind.INTEGRITY_FAILED -> "INTEGRITY_FAILED"
        ShareErrorKind.INVALID_INPUT -> "INVALID_INPUT"
        ShareErrorKind.RESOURCE_LIMIT -> "RESOURCE_LIMIT"
        ShareErrorKind.INTERRUPTED -> "INTERRUPTED"
    }
    @TypeConverter fun stringToError(value: String?): ShareErrorKind? = when (value) {
        null -> null
        "UNKNOWN" -> ShareErrorKind.UNKNOWN
        "PERMISSION_DENIED" -> ShareErrorKind.PERMISSION_DENIED
        "DISCOVERY_FAILED" -> ShareErrorKind.DISCOVERY_FAILED
        "VERSION_MISMATCH" -> ShareErrorKind.VERSION_MISMATCH
        "PAIRING_REJECTED" -> ShareErrorKind.PAIRING_REJECTED
        "AUTHENTICATION_FAILED" -> ShareErrorKind.AUTHENTICATION_FAILED
        "IDENTITY_CHANGED" -> ShareErrorKind.IDENTITY_CHANGED
        "TRANSFER_REJECTED" -> ShareErrorKind.TRANSFER_REJECTED
        "CANCELLED" -> ShareErrorKind.CANCELLED
        "TIMEOUT" -> ShareErrorKind.TIMEOUT
        "NETWORK_LOST" -> ShareErrorKind.NETWORK_LOST
        "STORAGE_FAILED" -> ShareErrorKind.STORAGE_FAILED
        "INTEGRITY_FAILED" -> ShareErrorKind.INTEGRITY_FAILED
        "INVALID_INPUT" -> ShareErrorKind.INVALID_INPUT
        "RESOURCE_LIMIT" -> ShareErrorKind.RESOURCE_LIMIT
        "INTERRUPTED" -> ShareErrorKind.INTERRUPTED
        else -> error("Invalid stored error kind")
    }
}
