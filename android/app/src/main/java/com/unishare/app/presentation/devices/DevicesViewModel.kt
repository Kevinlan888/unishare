package com.unishare.app.presentation.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unishare.app.data.device.*
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.sanitizedDisplayName
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DevicesUiState(val trusted: List<DeviceSummary> = emptyList(), val nearby: List<DeviceSummary> = emptyList(), val offline: List<DeviceSummary> = emptyList(), val pendingForget: DeviceSummary? = null, val actionFailed: Boolean = false)
class DevicesViewModel(private val repository: DeviceRepository) : ViewModel() {
    private val pending = MutableStateFlow<DeviceKey?>(null)
    private val failed = MutableStateFlow(false)
    val uiState = combine(repository.devices, pending, failed) { devices, id, error ->
        val clean = devices.map { it.copy(name = sanitizedDisplayName(it.name)) }.sortedBy { it.name.lowercase() }
        DevicesUiState(clean.filter { it.trusted && it.online }, clean.filter { !it.trusted && it.online },
            clean.filter { !it.online }, clean.find { it.id == id && it.trusted }, error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DevicesUiState())
    fun requestForget(id: DeviceKey) { if (repository.isTrusted(id)) pending.value = id }
    fun dismissForget() { pending.value = null }
    fun confirmForget() {
        val id = pending.value ?: return
        pending.value = null
        viewModelScope.launch { try { repository.forget(id); failed.value = false } catch (_: Exception) { failed.value = true } }
    }
    fun connect(id: DeviceKey) {
        if (repository.find(id)?.let { it.online && !it.identityChanged } != true) return
        viewModelScope.launch { try { repository.connect(id); failed.value = false } catch (_: Exception) { failed.value = true } }
    }
}
