package com.unishare.app.presentation.receive

import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.DeviceKey

enum class ReceivePhase {
    PENDING,
    RECEIVING,
    REJECTED,
    COMPLETED,
    FAILED,
    DIRECTORY_ERROR,
    EXPIRED,
}

data class ReceiveSourceUi(
    val id: DeviceKey = DeviceKey(""),
    val name: String = "",
    val platform: DevicePlatform? = null,
    val online: Boolean = false,
    val connected: Boolean = false,
)

data class ReceiveUiState(
    val activityId: ActivityKey,
    val kind: ContentKind = ContentKind.FILE,
    val displayName: String = "",
    val mimeType: String = "application/octet-stream",
    val byteCount: Long = 0,
    val source: ReceiveSourceUi = ReceiveSourceUi(),
    val destinationDisplayName: String? = null,
    val phase: ReceivePhase = ReceivePhase.EXPIRED,
    val finalContentUri: String? = null,
    val receivedText: String? = null,
    val canOpen: Boolean = false,
    val showUnsupportedDestinationNotice: Boolean = false,
) {
    val actionsEnabled: Boolean get() = phase == ReceivePhase.PENDING
    val showDestinationRecovery: Boolean get() = phase == ReceivePhase.DIRECTORY_ERROR
}
