package com.unishare.app.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unishare.app.data.device.*
import com.unishare.app.data.history.*
import com.unishare.app.domain.*
import com.unishare.app.presentation.receive.FinalContentReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class HistoryRow(val activity: ShareActivity, val deviceName: String, val canOpen: Boolean)
data class HistoryUiState(val query: HistoryQuery = HistoryQuery(), val rows: List<HistoryRow> = emptyList(), val devices: List<DeviceSummary> = emptyList())
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val repository: ActivityRepository, private val devices: DeviceRepository, private val reader: FinalContentReader) : ViewModel() {
    private val query = MutableStateFlow(HistoryQuery())
    private val inaccessible = MutableStateFlow<Set<ActivityKey>>(emptySet())
    val uiState = combine(query.flatMapLatest(repository::observeHistory), devices.devices, repository.observeHistory(HistoryQuery()), query, inaccessible) { activities, peers, allActivities, filters, missing ->
        val retainedIds = allActivities.map { it.deviceId }.distinct().filter { id -> peers.none { it.id == id } }
        val cleanPeers = peers.map { it.copy(name = sanitizedDisplayName(it.name)) } + retainedIds.map { DeviceSummary(it, "", null, online = false, trusted = false) }
        HistoryUiState(filters, activities.map { activity ->
            HistoryRow(activity, cleanPeers.find { it.id == activity.deviceId }?.name.orEmpty(), activity.id !in missing && canRead(activity))
        }, cleanPeers)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())
    fun setQuery(query: HistoryQuery) { this.query.value = query }
    fun openFinalContent(id: ActivityKey): String? {
        val row = uiState.value.rows.find { it.activity.id == id } ?: return null
        return if (canRead(row.activity)) row.activity.finalContentUri else {
            inaccessible.value += id
            null
        }
    }
    private fun canRead(activity: ShareActivity): Boolean {
        val uri = activity.finalContentUri ?: return false
        return activity.direction == ShareDirection.INCOMING && activity.status == ActivityStatus.COMPLETED &&
            activity.kind != ContentKind.TEXT && uri.startsWith("content://") && !uri.contains(".staging/") &&
            runCatching { reader.canRead(uri) }.getOrDefault(false)
    }
}
