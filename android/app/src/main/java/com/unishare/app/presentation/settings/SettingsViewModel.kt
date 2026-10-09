package com.unishare.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unishare.app.data.settings.*
import com.unishare.app.domain.isValidDeviceName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SettingsError { INVALID_NAME, NOTIFICATION_DENIED, ACTIVITY_NOT_VISIBLE, RUNTIME_FAILED, WRITE_FAILED }
data class SettingsUiState(val settings: AppSettings = AppSettings(), val requestNotificationPermission: Boolean = false, val directoryCorrected: Boolean = false, val error: SettingsError? = null, val busy: Boolean = false)
class SettingsViewModel(private val repository: SettingsRepository, private val runtime: suspend (Boolean) -> Boolean, private val releaseLegacyGrant: (String) -> Unit = {}) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    private val writes = Mutex()
    val uiState = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                val legacy = settings.receiveTreeUri != null || settings.receiveDirectoryDisplayName != null
                // Never advertise an unsafe directory, even when correction cannot be persisted.
                mutableState.update { it.copy(settings = settings.copy(receiveTreeUri = null, receiveDirectoryDisplayName = null)) }
                if (legacy) performWrite {
                    repository.setReceiveDirectory(null, null)
                    settings.receiveTreeUri?.let { uri ->
                        try { releaseLegacyGrant(uri) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Best-effort release after safe state is persisted. */ }
                    }
                    mutableState.update { it.copy(directoryCorrected = true) }
                }
            }
        }
    }
    private suspend fun performWrite(block: suspend () -> Unit) = writes.withLock {
        mutableState.update { it.copy(busy = true, error = null) }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(error = SettingsError.WRITE_FAILED) } }
        finally { mutableState.update { it.copy(busy = false) } }
    }
    private fun write(block: suspend () -> Unit) { viewModelScope.launch { performWrite(block) } }
    fun saveDeviceName(name: String) {
        if (!isValidDeviceName(name)) { mutableState.update { it.copy(error = SettingsError.INVALID_NAME) }; return }
        write { repository.setDeviceName(name.trim()) }
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
        mutableState.update { it.copy(requestNotificationPermission = false) }
        write {
            repository.setBackgroundReceiveEnabled(false)
            mutableState.update { it.copy(error = SettingsError.NOTIFICATION_DENIED) }
        }
    }
    private suspend fun setRuntime(enabled: Boolean): Boolean =
        try { runtime(enabled) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    private fun updateRuntime(enabled: Boolean) {
        write {
            if (!setRuntime(enabled)) {
                repository.setBackgroundReceiveEnabled(false)
                mutableState.update { it.copy(error = SettingsError.RUNTIME_FAILED) }
            }
        }
    }
    fun setNotifications(enabled: Boolean) {
        write {
            // Persist the optional-alert policy before potentially slow runtime shutdown.
            repository.setNotificationsEnabled(enabled)
            if (!enabled) {
                val stopped = setRuntime(false)
                repository.setBackgroundReceiveEnabled(false)
                if (!stopped) mutableState.update { it.copy(error = SettingsError.RUNTIME_FAILED) }
            }
        }
    }
    fun restoreDownloads() { write { repository.setReceiveDirectory(null, null); mutableState.update { it.copy(directoryCorrected = true) } } }
    fun setLanguage(language: AppLanguage) { write { repository.setLanguage(language) } }
    fun setTheme(theme: AppTheme) { write { repository.setTheme(theme) } }
}
