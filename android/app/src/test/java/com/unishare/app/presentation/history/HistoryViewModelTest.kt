package com.unishare.app.presentation.history

import com.unishare.app.data.history.HistoryQuery
import com.unishare.app.domain.*
import com.unishare.app.presentation.*
import com.unishare.app.presentation.receive.FinalContentReader
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.*
import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.data.history.ActivityRepository
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
            wanted.copy(id = ActivityKey("failed"), status = ActivityStatus.FAILED))), ManagementDevices(), FinalContentReader { true }, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        vm.setQuery(HistoryQuery("REPORT", DeviceKey("pc"), ShareDirection.INCOMING, ContentKind.FILE, ActivityStatus.COMPLETED))
        advanceUntilIdle()
        assertEquals(listOf("one"), vm.uiState.value.rows.map { it.activity.id.value })
        assertEquals("report.pdf", vm.uiState.value.rows.single().activity.displayName)
        vm.setQuery(HistoryQuery(search = "private")); advanceUntilIdle()
        assertTrue(vm.uiState.value.rows.isEmpty())
    }
    @Test fun missing_read_permission_disables_open_and_revocation_is_rechecked() = runTest(dispatcher) {
        var readable = true
        val vm = HistoryViewModel(ManagementActivities(listOf(managementActivity())), ManagementDevices(), FinalContentReader { readable }, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.rows.single().canOpen)
        readable = false
        assertNull(vm.openFinalContent(ActivityKey("one")))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.rows.single().canOpen)
    }
    @Test fun private_staging_and_nonterminal_uris_never_enable_open() = runTest(dispatcher) {
        val vm = HistoryViewModel(ManagementActivities(listOf(managementActivity().copy(finalContentUri = "content://com.unishare.app.staging/1"), managementActivity("two").copy(status = ActivityStatus.TRANSFERRING))), ManagementDevices(), FinalContentReader { true }, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.rows.size)
        assertTrue(vm.uiState.value.rows.none { it.canOpen })
    }
    @Test fun history_device_choices_survive_forgetting_and_empty_search_results() = runTest(dispatcher) {
        val vm = HistoryViewModel(ManagementActivities(listOf(managementActivity())), ManagementDevices(), FinalContentReader { false }, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        advanceUntilIdle()
        assertEquals(listOf("pc"), vm.uiState.value.devices.map { it.id.value })
        vm.setQuery(HistoryQuery(search = "missing")); advanceUntilIdle()
        assertTrue(vm.uiState.value.rows.isEmpty())
        assertEquals(listOf("pc"), vm.uiState.value.devices.map { it.id.value })
    }
    @Test fun unopened_history_does_not_probe_any_content() = runTest(dispatcher) {
        var probes = 0
        HistoryViewModel(ManagementActivities(listOf(managementActivity())), ManagementDevices(), FinalContentReader { probes++; true })
        advanceUntilIdle()
        assertEquals(0, probes)
    }
    @Test fun read_probes_run_off_main_and_metadata_updates_reuse_cached_permission() = runTest(dispatcher) {
        val mainThread = Thread.currentThread()
        val threads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val repository = ManagementActivities(listOf(managementActivity()))
        val peers = ManagementDevices()
        val vm = HistoryViewModel(repository, peers, FinalContentReader { threads += Thread.currentThread(); true })
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        vm.uiState.first { it.rows.isNotEmpty() }
        assertTrue(threads.all { it !== mainThread })
        peers.devices.value = listOf(DeviceSummary(DeviceKey("pc"), "Renamed PC", null, true, true))
        vm.uiState.first { it.rows.singleOrNull()?.deviceName == "Renamed PC" }
        repository.upsert(managementActivity().copy(byteCount = 2048))
        vm.uiState.first { it.rows.singleOrNull()?.activity?.byteCount == 2048L }
        assertEquals(1, threads.size)
        collection.cancel(); runCurrent()
        repository.upsert(managementActivity("new").copy(finalContentUri = "content://downloads/2"))
        advanceUntilIdle()
        assertEquals(1, threads.size)
    }
    @Test fun structured_query_does_not_subscribe_to_a_second_unfiltered_activity_list() = runTest(dispatcher) {
        val delegate = ManagementActivities(listOf(managementActivity()))
        var wholeListSubscriptions = 0
        val repository = object : ActivityRepository by delegate {
            override fun observeHistory(query: HistoryQuery): Flow<List<ShareActivity>> {
                if (query == HistoryQuery()) wholeListSubscriptions++
                return delegate.observeHistory(query)
            }
        }
        val vm = HistoryViewModel(repository, ManagementDevices(), FinalContentReader { false }, dispatcher)
        vm.setQuery(HistoryQuery(device = DeviceKey("pc")))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        advanceUntilIdle()
        assertEquals(0, wholeListSubscriptions)
        assertEquals(listOf("pc"), vm.uiState.value.devices.map { it.id.value })
    }
}
