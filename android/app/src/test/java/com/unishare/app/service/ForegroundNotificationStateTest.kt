package com.unishare.app.service

import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundNotificationStateTest {
    @Test fun service_tracks_progress_target_and_remaining_work_after_cancellation() = runTest {
        val runtime = MutableStateFlow(RuntimeState(backgroundReceiveEnabled = true))
        val active = MutableStateFlow<List<ShareActivity>>(emptyList())
        val devices = MutableStateFlow(listOf(DeviceSummary(DeviceKey("pc"), "private", null, true, true)))
        val values = mutableListOf<ForegroundNotificationState>()
        backgroundScope.launch { foregroundNotificationStates(runtime, active, devices).collect(values::add) }
        runCurrent()
        assertEquals(1, values.last().onlineDeviceCount)
        val first = ShareActivity(ActivityKey("a"), DeviceKey("pc"), direction = ShareDirection.OUTGOING,
            kind = ContentKind.FILE, origin = ShareOrigin.DIRECT, displayName = "private", mimeType = "application/pdf",
            byteCount = 100, transferredBytes = 42, createdAtMillis = 1, updatedAtMillis = 2, transferId = TransferKey("a"))
        val second = first.copy(id = ActivityKey("b"), transferId = TransferKey("b"), transferredBytes = 7)
        active.value = listOf(first, second)
        runCurrent()
        assertEquals(ServiceReason.ACTIVE_TRANSFER, values.last().reason)
        assertEquals(42, values.last().progress)
        assertEquals(ActivityKey("a"), values.last().transfer?.id)
        active.value = listOf(second)
        runCurrent()
        assertEquals(ServiceReason.ACTIVE_TRANSFER, values.last().reason)
        assertEquals(7, values.last().progress)
        assertEquals(ActivityKey("b"), values.last().transfer?.id)
        active.value = emptyList()
        runCurrent()
        assertEquals(ServiceReason.BACKGROUND_RECEIVE, values.last().reason)
    }
}
