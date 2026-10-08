package com.unishare.app.presentation.devices

import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.domain.DeviceKey
import com.unishare.app.presentation.ManagementDevices
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    @Test fun sections_are_exclusive_and_forget_requires_confirmation() = runTest(dispatcher) {
        val repository = ManagementDevices(listOf(
            DeviceSummary(DeviceKey("a"), "PC", null, true, true),
            DeviceSummary(DeviceKey("b"), "Phone", null, true, false),
            DeviceSummary(DeviceKey("c"), "Laptop", null, false, true)))
        val vm = DevicesViewModel(repository)
        advanceUntilIdle()
        assertEquals(listOf("a"), vm.uiState.value.trusted.map { it.id.value })
        assertEquals(listOf("b"), vm.uiState.value.nearby.map { it.id.value })
        assertEquals(listOf("c"), vm.uiState.value.offline.map { it.id.value })
        vm.requestForget(DeviceKey("a")); advanceUntilIdle()
        assertNotNull(repository.find(DeviceKey("a")))
        vm.dismissForget(); vm.confirmForget(); advanceUntilIdle()
        assertNotNull(repository.find(DeviceKey("a")))
        vm.requestForget(DeviceKey("a")); vm.confirmForget(); advanceUntilIdle()
        assertNull(repository.find(DeviceKey("a")))
    }
    @Test fun malicious_device_labels_are_sanitized_before_display() = runTest(dispatcher) {
        val vm = DevicesViewModel(ManagementDevices(listOf(DeviceSummary(DeviceKey("a"), "C:\\private\\PC\u0000\u202e", null, true, true))))
        advanceUntilIdle()
        assertEquals("PC", vm.uiState.value.trusted.single().name)
    }
}
