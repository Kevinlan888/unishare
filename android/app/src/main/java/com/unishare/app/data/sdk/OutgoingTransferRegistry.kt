package com.unishare.app.data.sdk

import com.unishare.app.domain.OperationKey
import com.unishare.app.domain.TransferKey
import java.util.ArrayDeque

/** Called under the same gateway lock as SDK submissions and copied callbacks. */
class OutgoingTransferRegistry {
    private val pending = ArrayDeque<OperationKey>()
    private val known = HashSet<TransferKey>()

    fun register(operationId: OperationKey) {
        require(operationId.value.isNotBlank())
        pending.addLast(operationId)
    }

    fun observeIncoming(transferId: TransferKey) { known.add(transferId) }

    fun observeProgress(transferId: TransferKey): OutgoingBinding? {
        if (!known.add(transferId)) return null
        val operation = pending.pollFirst() ?: return null
        return OutgoingBinding(operation, transferId)
    }

    fun complete(operationId: OperationKey, transferId: TransferKey?) {
        pending.remove(operationId)
        // Keep terminal IDs through Stop, so late progress cannot consume another operation.
        transferId?.let(known::add)
    }

    fun clear() { pending.clear(); known.clear() }
}
