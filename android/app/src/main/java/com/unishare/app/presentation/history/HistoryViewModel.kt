package com.unishare.app.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unishare.app.data.device.*
import com.unishare.app.data.history.*
import com.unishare.app.domain.*
import com.unishare.app.presentation.receive.FinalContentReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HistoryRow(val activity: ShareActivity, val deviceName: String, val canOpen: Boolean)
data class HistoryUiState(val query: HistoryQuery = HistoryQuery(), val rows: List<HistoryRow> = emptyList(), val devices: List<DeviceSummary> = emptyList())
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repository: ActivityRepository,
    private val devices: DeviceRepository,
    private val reader: FinalContentReader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val query = MutableStateFlow(HistoryQuery())
    private val refresh = MutableStateFlow(0L)
    private val cacheLock = Mutex()
    private val permissions = object : LinkedHashMap<Pair<ActivityKey, String>, Boolean>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<ActivityKey, String>, Boolean>) = size > 512
    }
    private data class Input(val activities: List<ShareActivity>, val peers: List<DeviceSummary>, val ids: List<DeviceKey>, val query: HistoryQuery)
    val uiState = combine(query.flatMapLatest(repository::observeHistory), devices.devices, repository.observeDeviceIds(), query, refresh) { activities, peers, ids, filters, _ ->
        Input(activities, peers, ids, filters)
    }.mapLatest { input ->
        val cleanPeers = input.peers.map { it.copy(name = sanitizedDisplayName(it.name)) } +
            input.ids.filter { id -> input.peers.none { it.id == id } }.map { DeviceSummary(it, "", null, online = false, trusted = false) }
        val rows = withContext(ioDispatcher) {
            input.activities.map { activity ->
                HistoryRow(activity, cleanPeers.find { it.id == activity.deviceId }?.name.orEmpty(), canRead(activity, cached = true))
            }
        }
        HistoryUiState(input.query, rows, cleanPeers)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), HistoryUiState())
    fun setQuery(query: HistoryQuery) { this.query.value = query }
    suspend fun openFinalContent(id: ActivityKey): String? {
        val row = uiState.value.rows.find { it.activity.id == id } ?: return null
        val readable = withContext(ioDispatcher) { canRead(row.activity, cached = false) }
        refresh.value++
        return row.activity.finalContentUri.takeIf { readable }
    }
    private suspend fun canRead(activity: ShareActivity, cached: Boolean): Boolean {
        val uri = activity.finalContentUri ?: return false
        if (activity.direction != ShareDirection.INCOMING || activity.status != ActivityStatus.COMPLETED ||
            activity.kind == ContentKind.TEXT || !uri.startsWith("content://") || uri.contains(".staging/")) return false
        return cacheLock.withLock {
            val key = activity.id to uri
            if (cached) permissions[key]?.let { return@withLock it }
            val readable = try { reader.canRead(uri) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            permissions[key] = readable
            readable
        }
    }
}
