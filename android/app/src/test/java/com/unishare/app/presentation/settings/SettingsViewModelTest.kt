package com.unishare.app.presentation.settings

import com.unishare.app.data.settings.*
import com.unishare.app.presentation.ManagementSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    @Test fun defaults_are_safe_and_legacy_tree_is_cleared() = runTest(dispatcher) {
        val repository = ManagementSettings(AppSettings(receiveTreeUri = "content://legacy/tree", receiveDirectoryDisplayName = "Old"))
        var released: String? = null
        val vm = SettingsViewModel(repository, { true }, { released = it })
        advanceUntilIdle()
        assertNull(repository.settings.value.receiveTreeUri)
        assertNull(repository.settings.value.receiveDirectoryDisplayName)
        assertEquals("content://legacy/tree", released)
        assertTrue(vm.uiState.value.directoryCorrected)
        assertFalse(vm.uiState.value.settings.backgroundReceiveEnabled)
        assertEquals(AppLanguage.SYSTEM, vm.uiState.value.settings.language)
        assertEquals(AppTheme.SYSTEM, vm.uiState.value.settings.theme)
    }
    @Test fun denial_rolls_back_without_starting_runtime() = runTest(dispatcher) {
        var starts = 0
        val repository = ManagementSettings()
        val vm = SettingsViewModel(repository, { enabled -> if (enabled) starts++; true })
        vm.requestBackgroundReceive(true, permissionGranted = false, activityVisible = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.requestNotificationPermission)
        vm.onNotificationPermissionResult(false, activityVisible = true); advanceUntilIdle()
        assertFalse(repository.settings.value.backgroundReceiveEnabled)
        assertFalse(vm.uiState.value.requestNotificationPermission)
        assertEquals(SettingsError.NOTIFICATION_DENIED, vm.uiState.value.error)
        assertEquals(0, starts)
    }
    @Test fun runtime_requires_visible_activity_and_granted_permission() = runTest(dispatcher) {
        val repository = ManagementSettings()
        val vm = SettingsViewModel(repository, { enabled -> repository.setBackgroundReceiveEnabled(enabled); true })
        vm.requestBackgroundReceive(true, true, false); advanceUntilIdle()
        assertFalse(repository.settings.value.backgroundReceiveEnabled)
        vm.requestBackgroundReceive(true, false, true)
        vm.onNotificationPermissionResult(true, false); advanceUntilIdle()
        assertFalse(repository.settings.value.backgroundReceiveEnabled)
        vm.requestBackgroundReceive(true, true, true); advanceUntilIdle()
        assertTrue(repository.settings.value.backgroundReceiveEnabled)
    }
    @Test fun malicious_and_empty_device_names_cannot_be_saved() = runTest(dispatcher) {
        val repository = ManagementSettings()
        val vm = SettingsViewModel(repository, { true })
        listOf("", "   ", "PC\u0000", "PC\u202e", "../PC", "a".repeat(65), "\nPC").forEach { vm.saveDeviceName(it); advanceUntilIdle(); assertEquals("UniShare", repository.settings.value.deviceName) }
        vm.saveDeviceName("  Studio PC  "); advanceUntilIdle()
        assertEquals("Studio PC", repository.settings.value.deviceName)
    }
    @Test fun runtime_failure_rolls_back_and_disabling_notifications_stops_background_receive() = runTest(dispatcher) {
        val repository = ManagementSettings(AppSettings(backgroundReceiveEnabled = true))
        val vm = SettingsViewModel(repository, { enabled -> if (!enabled) repository.setBackgroundReceiveEnabled(false); false })
        vm.requestBackgroundReceive(true, true, true); advanceUntilIdle()
        assertFalse(repository.settings.value.backgroundReceiveEnabled)
        repository.setBackgroundReceiveEnabled(true)
        vm.setNotifications(false); advanceUntilIdle()
        assertFalse(repository.settings.value.backgroundReceiveEnabled)
        assertFalse(repository.settings.value.notificationsEnabled)
    }
    @Test fun all_settings_write_failures_are_reported_and_leave_controls_usable() = runTest(dispatcher) {
        listOf("name", "notifications", "language", "theme", "directory").forEach { operation ->
            val backing = ManagementSettings()
            val repository = FailingSettings(backing, operation)
            val vm = SettingsViewModel(repository, { true })
            advanceUntilIdle()
            when (operation) {
                "name" -> vm.saveDeviceName("New PC")
                "notifications" -> vm.setNotifications(false)
                "language" -> vm.setLanguage(AppLanguage.SIMPLIFIED_CHINESE)
                "theme" -> vm.setTheme(AppTheme.DARK)
                else -> vm.restoreDownloads()
            }
            advanceUntilIdle()
            assertNotNull("$operation must surface a recoverable error", vm.uiState.value.error)
            assertFalse(vm.uiState.value.busy)
            assertEquals(AppSettings(), backing.settings.value)
            repository.failingOperation = null
            vm.saveDeviceName("Recovered PC"); advanceUntilIdle()
            assertEquals("Recovered PC", backing.settings.value.deviceName)
        }
    }
    @Test fun denial_and_runtime_rollback_write_failure_does_not_crash_or_leave_busy() = runTest(dispatcher) {
        val backing = ManagementSettings(AppSettings(backgroundReceiveEnabled = true))
        val vm = SettingsViewModel(FailingSettings(backing, "background"), { false })
        advanceUntilIdle()
        vm.requestBackgroundReceive(true, false, true)
        vm.onNotificationPermissionResult(false, true); advanceUntilIdle()
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.requestNotificationPermission)
        assertFalse(vm.uiState.value.busy)
        vm.requestBackgroundReceive(true, true, true); advanceUntilIdle()
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.busy)
    }
    @Test fun failed_legacy_clear_is_never_exposed_as_a_supported_directory_and_can_retry() = runTest(dispatcher) {
        val backing = ManagementSettings(AppSettings(receiveTreeUri = "content://legacy/tree", receiveDirectoryDisplayName = "Legacy"))
        val repository = FailingSettings(backing, "directory")
        val vm = SettingsViewModel(repository, { true })
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.settings.receiveTreeUri)
        assertFalse(vm.uiState.value.busy)
        repository.failingOperation = null
        vm.restoreDownloads(); advanceUntilIdle()
        assertNull(backing.settings.value.receiveTreeUri)
        assertTrue(vm.uiState.value.directoryCorrected)
    }
    @Test fun runtime_cancellation_is_not_converted_to_a_failed_write_or_rollback() = runTest(dispatcher) {
        val backing = ManagementSettings(AppSettings(backgroundReceiveEnabled = true))
        val vm = SettingsViewModel(backing, { throw CancellationException("scope stopped") })
        advanceUntilIdle()
        vm.requestBackgroundReceive(true, true, true); advanceUntilIdle()
        assertTrue(backing.settings.value.backgroundReceiveEnabled)
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.busy)
    }
}

private class FailingSettings(private val backing: ManagementSettings, var failingOperation: String?) : SettingsRepository by backing {
    private fun check(operation: String) { if (failingOperation == operation) throw java.io.IOException("storage unavailable") }
    override suspend fun setDeviceName(name: String) { check("name"); backing.setDeviceName(name) }
    override suspend fun setNotificationsEnabled(enabled: Boolean) { check("notifications"); backing.setNotificationsEnabled(enabled) }
    override suspend fun setLanguage(language: AppLanguage) { check("language"); backing.setLanguage(language) }
    override suspend fun setTheme(theme: AppTheme) { check("theme"); backing.setTheme(theme) }
    override suspend fun setReceiveDirectory(treeUri: String?, displayName: String?) { check("directory"); backing.setReceiveDirectory(treeUri, displayName) }
    override suspend fun setBackgroundReceiveEnabled(enabled: Boolean) { check("background"); backing.setBackgroundReceiveEnabled(enabled) }
}
