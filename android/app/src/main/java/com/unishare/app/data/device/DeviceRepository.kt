package com.unishare.app.data.device

import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.domain.DeviceKey
import kotlinx.coroutines.flow.StateFlow

data class DeviceSummary(
    val id: DeviceKey,
    val name: String,
    val platform: DevicePlatform?,
    val online: Boolean,
    val trusted: Boolean,
    val connected: Boolean = false,
    val identityChanged: Boolean = false,
    val lastSeenUnixMillis: Long = 0,
)

interface DeviceRepository {
    val devices: StateFlow<List<DeviceSummary>>
    fun find(deviceId: DeviceKey): DeviceSummary?
    fun isTrusted(deviceId: DeviceKey): Boolean
    fun canSend(deviceId: DeviceKey): Boolean
    suspend fun connect(deviceId: DeviceKey)
    suspend fun forget(deviceId: DeviceKey)
}
