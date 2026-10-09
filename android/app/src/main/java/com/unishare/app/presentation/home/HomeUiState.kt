package com.unishare.app.presentation.home

import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ActivityStatus
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareErrorKind

enum class ThisDeviceStatus { STARTING, ONLINE, OFFLINE, UNAVAILABLE }

data class ThisDeviceUi(
    val name: String,
    val status: ThisDeviceStatus,
)

data class RecentActivityUi(
    val activityId: ActivityKey,
    val deviceId: DeviceKey,
    val deviceName: String,
    val platform: DevicePlatform?,
    val displayName: String,
    val status: ActivityStatus,
    val progressPercent: Int?,
    val updatedAtMillis: Long,
    val kind: com.unishare.app.domain.ContentKind = com.unishare.app.domain.ContentKind.FILE,
)

data class NearbyDeviceUi(
    val id: DeviceKey,
    val name: String,
    val platform: DevicePlatform?,
    val online: Boolean,
    val trusted: Boolean,
    val connected: Boolean = false,
    val identityChanged: Boolean = false,
)

data class HomeUiState(
    val isLoading: Boolean = false,
    val thisDevice: ThisDeviceUi = ThisDeviceUi("UniShare", ThisDeviceStatus.STARTING),
    val recent: List<RecentActivityUi> = emptyList(),
    val nearby: List<NearbyDeviceUi> = emptyList(),
    val discoveryError: ShareErrorKind? = null,
)
