package com.unishare.app.data.sdk

import com.unishare.app.domain.OperationKey
import com.unishare.app.domain.TransferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutgoingTransferRegistryTest {
    @Test fun unknown_progress_binds_fifo_once_even_when_devices_are_interleaved() {
        val registry = OutgoingTransferRegistry()
        registry.register(OperationKey("device-a-1"))
        registry.register(OperationKey("device-b-1"))
        registry.register(OperationKey("device-a-2"))
        assertEquals(OutgoingBinding(OperationKey("device-a-1"), TransferKey("one")), registry.observeProgress(TransferKey("one")))
        assertNull(registry.observeProgress(TransferKey("one")))
        assertEquals(OutgoingBinding(OperationKey("device-b-1"), TransferKey("two")), registry.observeProgress(TransferKey("two")))
        assertEquals(OutgoingBinding(OperationKey("device-a-2"), TransferKey("three")), registry.observeProgress(TransferKey("three")))
        assertNull(registry.observeProgress(TransferKey("four")))
    }

    @Test fun incoming_and_its_late_terminal_progress_never_consume_a_send() {
        val registry = OutgoingTransferRegistry()
        registry.register(OperationKey("send"))
        registry.observeIncoming(TransferKey("incoming"))
        assertNull(registry.observeProgress(TransferKey("incoming")))
        assertEquals(OutgoingBinding(OperationKey("send"), TransferKey("outgoing")), registry.observeProgress(TransferKey("outgoing")))
    }

    @Test fun completion_before_progress_removes_only_that_operation_and_retires_its_id() {
        val registry = OutgoingTransferRegistry()
        registry.register(OperationKey("first"))
        registry.register(OperationKey("second"))
        registry.complete(OperationKey("first"), TransferKey("terminal"))
        assertNull(registry.observeProgress(TransferKey("terminal")))
        assertEquals(OutgoingBinding(OperationKey("second"), TransferKey("next")), registry.observeProgress(TransferKey("next")))
        registry.register(OperationKey("third"))
        registry.complete(OperationKey("second"), TransferKey("next"))
        assertNull(registry.observeProgress(TransferKey("next")))
        assertEquals(OutgoingBinding(OperationKey("third"), TransferKey("third-transfer")), registry.observeProgress(TransferKey("third-transfer")))
    }

    @Test fun failure_without_transfer_and_clear_retire_pending_state() {
        val registry = OutgoingTransferRegistry()
        registry.register(OperationKey("failed"))
        registry.register(OperationKey("keep"))
        registry.complete(OperationKey("failed"), null)
        assertEquals(OutgoingBinding(OperationKey("keep"), TransferKey("known")), registry.observeProgress(TransferKey("known")))
        registry.register(OperationKey("discard"))
        registry.clear()
        assertNull(registry.observeProgress(TransferKey("empty")))
        registry.register(OperationKey("new"))
        assertEquals(OutgoingBinding(OperationKey("new"), TransferKey("known")), registry.observeProgress(TransferKey("known")))
    }
}
