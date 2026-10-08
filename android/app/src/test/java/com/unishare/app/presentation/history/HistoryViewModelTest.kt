package com.unishare.app.presentation.history

import com.unishare.app.data.history.HistoryQuery
import com.unishare.app.domain.*
import com.unishare.app.presentation.*
import com.unishare.app.presentation.receive.FinalContentReader
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    @Test fun query_combines_all_filters_and_searches_only_sanitized_names() = runTest(dispatcher) {
        val wanted = managementActivity().copy(displayName = "C:\\private\\report.pdf\u0000")
        val vm = HistoryViewModel(ManagementActivities(listOf(wanted,
            wanted.copy(id = ActivityKey("other"), deviceId = DeviceKey("phone")),
            wanted.copy(id = ActivityKey("out"), direction = ShareDirection.OUTGOING),
            wanted.copy(id = ActivityKey("text"), kind = ContentKind.TEXT),
            wanted.copy(id = ActivityKey("failed"), status = ActivityStatus.FAILED))), ManagementDevices(), FinalContentReader { true })
        vm.setQuery(HistoryQuery("REPORT", DeviceKey("pc"), ShareDirection.INCOMING, ContentKind.FILE, ActivityStatus.COMPLETED))
        advanceUntilIdle()
        assertEquals(listOf("one"), vm.uiState.value.rows.map { it.activity.id.value })
        assertEquals("report.pdf", vm.uiState.value.rows.single().activity.displayName)
        vm.setQuery(HistoryQuery(search = "private")); advanceUntilIdle()
        assertTrue(vm.uiState.value.rows.isEmpty())
    }
    @Test fun missing_read_permission_disables_open_and_revocation_is_rechecked() = runTest(dispatcher) {
        var readable = true
        val vm = HistoryViewModel(ManagementActivities(listOf(managementActivity())), ManagementDevices(), FinalContentReader { readable })
        advanceUntilIdle()
        assertTrue(vm.uiState.value.rows.single().canOpen)
        readable = false
        assertNull(vm.openFinalContent(ActivityKey("one")))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.rows.single().canOpen)
    }
    @Test fun private_staging_and_nonterminal_uris_never_enable_open() = runTest(dispatcher) {
        val vm = HistoryViewModel(ManagementActivities(listOf(managementActivity().copy(finalContentUri = "content://com.unishare.app.staging/1"), managementActivity("two").copy(status = ActivityStatus.TRANSFERRING))), ManagementDevices(), FinalContentReader { true })
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.rows.size)
        assertTrue(vm.uiState.value.rows.none { it.canOpen })
    }
    @Test fun history_device_choices_survive_forgetting_and_empty_search_results() = runTest(dispatcher) {
        val vm = HistoryViewModel(ManagementActivities(listOf(managementActivity())), ManagementDevices(), FinalContentReader { false })
        advanceUntilIdle()
        assertEquals(listOf("pc"), vm.uiState.value.devices.map { it.id.value })
        vm.setQuery(HistoryQuery(search = "missing")); advanceUntilIdle()
        assertTrue(vm.uiState.value.rows.isEmpty())
        assertEquals(listOf("pc"), vm.uiState.value.devices.map { it.id.value })
    }
}
