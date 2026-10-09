package com.unishare.app.service

import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.domain.ShareActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class ForegroundNotificationState(
    val reason: ServiceReason = ServiceReason.BACKGROUND_RECEIVE,
    val transfer: ShareActivity? = null,
    val progress: Int = 0,
    val onlineDeviceCount: Int = 0,
)

fun foregroundNotificationStates(
    runtime: Flow<RuntimeState>,
    active: Flow<List<ShareActivity>>,
    devices: Flow<List<DeviceSummary>>,
): Flow<ForegroundNotificationState> = combine(runtime, active, devices) { state, transfers, peers ->
    val target = transfers.firstOrNull { !it.status.isTerminal }
    ForegroundNotificationState(
        reason = if (target != null || state.activeTransferCount > 0) ServiceReason.ACTIVE_TRANSFER else ServiceReason.BACKGROUND_RECEIVE,
        transfer = target,
        progress = if (target == null || target.byteCount <= 0) 0 else
            (100.0 * target.transferredBytes / target.byteCount).toInt().coerceIn(0, 100),
        onlineDeviceCount = peers.count { it.online },
    )
}
