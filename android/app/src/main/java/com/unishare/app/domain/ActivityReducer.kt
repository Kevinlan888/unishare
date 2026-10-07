package com.unishare.app.domain

sealed interface ActivityEvent {
    val atMillis: Long

    data class OperationBound(val operationId: OperationKey, override val atMillis: Long) : ActivityEvent
    data class TransferBound(val transferId: TransferKey, override val atMillis: Long) : ActivityEvent
    data class Progress(val transferredBytes: Long, override val atMillis: Long) : ActivityEvent
    /** Emit only after incoming content has been published or process-only text has been read. */
    data class Completed(override val atMillis: Long, val finalContentUri: String? = null) : ActivityEvent
    data class Rejected(override val atMillis: Long) : ActivityEvent
    data class Cancelled(override val atMillis: Long) : ActivityEvent
    data class Failed(val errorKind: ShareErrorKind, override val atMillis: Long) : ActivityEvent
}

object ActivityReducer {
    fun reduce(current: ShareActivity, event: ActivityEvent): ShareActivity {
        if (current.status.isTerminal) return current

        val updatedAt = maxOf(current.createdAtMillis, current.updatedAtMillis, event.atMillis)
        return when (event) {
            is ActivityEvent.OperationBound -> {
                if (current.operationId != null) current
                else current.copy(operationId = event.operationId, updatedAtMillis = updatedAt)
            }
            is ActivityEvent.TransferBound -> {
                if (current.transferId != null) current
                else current.copy(transferId = event.transferId, updatedAtMillis = updatedAt)
            }
            is ActivityEvent.Progress -> current.copy(
                status = ActivityStatus.TRANSFERRING,
                transferredBytes = maxOf(current.transferredBytes, event.transferredBytes)
                    .coerceIn(0L, current.byteCount.coerceAtLeast(0L)),
                updatedAtMillis = updatedAt,
            )
            is ActivityEvent.Completed -> current.copy(
                status = ActivityStatus.COMPLETED,
                transferredBytes = current.byteCount.coerceAtLeast(0L),
                updatedAtMillis = updatedAt,
                completedAtMillis = updatedAt,
                errorKind = null,
                finalContentUri = event.finalContentUri.takeIf {
                    current.direction == ShareDirection.INCOMING && current.kind != ContentKind.TEXT
                },
            )
            is ActivityEvent.Rejected -> finish(current, ShareErrorKind.TRANSFER_REJECTED, updatedAt)
            is ActivityEvent.Cancelled -> finish(current, ShareErrorKind.CANCELLED, updatedAt)
            is ActivityEvent.Failed -> finish(current, event.errorKind, updatedAt)
        }
    }

    private fun finish(current: ShareActivity, errorKind: ShareErrorKind, atMillis: Long): ShareActivity =
        current.copy(
            status = when (errorKind) {
                ShareErrorKind.TRANSFER_REJECTED -> ActivityStatus.REJECTED
                ShareErrorKind.CANCELLED -> ActivityStatus.CANCELLED
                else -> ActivityStatus.FAILED
            },
            updatedAtMillis = atMillis,
            completedAtMillis = atMillis,
            errorKind = errorKind,
            finalContentUri = null,
        )
}
