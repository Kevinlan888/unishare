package com.unishare.app.presentation

import com.unishare.app.data.device.*
import com.unishare.app.data.history.*
import com.unishare.app.data.settings.*
import com.unishare.app.domain.*
import kotlinx.coroutines.flow.*

class ManagementDevices(initial: List<DeviceSummary> = emptyList()) : DeviceRepository {
    override val devices = MutableStateFlow(initial)
    override fun find(deviceId: DeviceKey) = devices.value.find { it.id == deviceId }
    override fun isTrusted(deviceId: DeviceKey) = find(deviceId)?.trusted == true
    override fun canSend(deviceId: DeviceKey) = find(deviceId)?.online == true
    override suspend fun connect(deviceId: DeviceKey) { devices.value = devices.value.map { if (it.id == deviceId) it.copy(connected = true) else it } }
    override suspend fun forget(deviceId: DeviceKey) { devices.value = devices.value.filterNot { it.id == deviceId } }
}
class ManagementActivities(initial: List<ShareActivity>) : ActivityRepository {
    val rows = MutableStateFlow(initial)
    override fun observeDeviceIds() = rows.map { items -> items.map { it.deviceId }.distinct() }
    override fun observeRecent(limit: Int) = rows.map { it.take(limit) }
    override fun observeDevice(device: DeviceKey) = rows.map { items -> items.filter { it.deviceId == device } }
    override suspend fun upsert(activity: ShareActivity) { rows.value = rows.value.filterNot { it.id == activity.id } + activity }
    override suspend fun markNonTerminalInterrupted(nowMillis: Long) = Unit
    override suspend fun prune(nowMillis: Long) = Unit
}
class ManagementSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    override val settings = MutableStateFlow(initial)
    override suspend fun setDeviceName(name: String) { settings.value = settings.value.copy(deviceName = name) }
    override suspend fun setReceiveDirectory(treeUri: String?, displayName: String?) { settings.value = settings.value.copy(receiveTreeUri = treeUri, receiveDirectoryDisplayName = displayName) }
    override suspend fun setBackgroundReceiveEnabled(enabled: Boolean) { settings.value = settings.value.copy(backgroundReceiveEnabled = enabled) }
    override suspend fun setNotificationsEnabled(enabled: Boolean) { settings.value = settings.value.copy(notificationsEnabled = enabled) }
    override suspend fun setLanguage(language: AppLanguage) { settings.value = settings.value.copy(language = language) }
    override suspend fun setTheme(theme: AppTheme) { settings.value = settings.value.copy(theme = theme) }
}
fun managementActivity(id: String = "one") = ShareActivity(ActivityKey(id), DeviceKey("pc"), ShareDirection.INCOMING, ContentKind.FILE, ShareOrigin.DIRECT, "report.pdf", "application/pdf", 1024, 100, 200, status = ActivityStatus.COMPLETED, finalContentUri = "content://downloads/1")
