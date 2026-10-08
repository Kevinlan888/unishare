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
}
