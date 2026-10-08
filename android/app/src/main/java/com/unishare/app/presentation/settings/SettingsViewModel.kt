package com.unishare.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unishare.app.data.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.unishare.app.domain.isValidDeviceName

enum class SettingsError { INVALID_NAME, NOTIFICATION_DENIED, ACTIVITY_NOT_VISIBLE, RUNTIME_FAILED }
data class SettingsUiState(val settings: AppSettings = AppSettings(), val requestNotificationPermission: Boolean = false, val directoryCorrected: Boolean = false, val error: SettingsError? = null, val busy: Boolean = false)
class SettingsViewModel(private val repository: SettingsRepository, private val runtime: suspend (Boolean) -> Boolean, private val releaseLegacyGrant: (String) -> Unit = {}) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val uiState = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                if (settings.receiveTreeUri != null || settings.receiveDirectoryDisplayName != null) {
                    repository.setReceiveDirectory(null, null)
                    settings.receiveTreeUri?.let { uri -> runCatching { releaseLegacyGrant(uri) } }
                    mutableState.update { it.copy(settings = settings.copy(receiveTreeUri = null, receiveDirectoryDisplayName = null), directoryCorrected = true) }
                } else mutableState.update { it.copy(settings = settings) }
            }
        }
    }
    fun saveDeviceName(name: String) {
        if (!isValidDeviceName(name)) { mutableState.update { it.copy(error = SettingsError.INVALID_NAME) }; return }
        viewModelScope.launch { repository.setDeviceName(name.trim()); mutableState.update { it.copy(error = null) } }
    }
    fun requestBackgroundReceive(enabled: Boolean, permissionGranted: Boolean, activityVisible: Boolean) {
        if (mutableState.value.busy) return
        if (enabled && !activityVisible) { mutableState.update { it.copy(error = SettingsError.ACTIVITY_NOT_VISIBLE) }; return }
        if (enabled && !mutableState.value.settings.notificationsEnabled) { denyNotification(); return }
        if (enabled && !permissionGranted) {
            mutableState.update { it.copy(requestNotificationPermission = true, error = null) }; return
        }
        mutableState.update { it.copy(requestNotificationPermission = false) }
        updateRuntime(enabled)
    }
    fun onNotificationPermissionResult(granted: Boolean, activityVisible: Boolean) {
        if (!mutableState.value.requestNotificationPermission) return
        mutableState.update { it.copy(requestNotificationPermission = false) }
        if (!granted) denyNotification()
        else requestBackgroundReceive(true, true, activityVisible)
    }
    private fun denyNotification() {
        mutableState.update { it.copy(requestNotificationPermission = false, error = SettingsError.NOTIFICATION_DENIED) }
        viewModelScope.launch { repository.setBackgroundReceiveEnabled(false) }
    }
    private fun updateRuntime(enabled: Boolean) {
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val success = runCatching { runtime(enabled) }.getOrDefault(false)
            if (!success) repository.setBackgroundReceiveEnabled(false)
            mutableState.update { it.copy(busy = false, error = if (success) null else SettingsError.RUNTIME_FAILED) }
        }
    }
    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) { runtime(false); repository.setBackgroundReceiveEnabled(false) }
            repository.setNotificationsEnabled(enabled)
        }
    }
    fun restoreDownloads() { viewModelScope.launch { repository.setReceiveDirectory(null, null); mutableState.update { it.copy(directoryCorrected = true) } } }
    fun setLanguage(language: AppLanguage) { viewModelScope.launch { repository.setLanguage(language) } }
    fun setTheme(theme: AppTheme) { viewModelScope.launch { repository.setTheme(theme) } }
}
