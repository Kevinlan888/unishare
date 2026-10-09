package com.unishare.app.data.history

import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ActivityRepository {
    fun observeDeviceIds(): Flow<List<DeviceKey>> =
        observeRecent(Int.MAX_VALUE).map { rows -> rows.map { it.deviceId }.distinct() }
    fun observeHistory(query: HistoryQuery): Flow<List<ShareActivity>> =
        observeRecent(Int.MAX_VALUE).map(query::filter)
    fun observeRecentByDevice(limit: Int): Flow<List<ShareActivity>> {
        require(limit > 0) { "History limit must be positive" }
        return observeRecent(Int.MAX_VALUE).map { rows ->
            rows.distinctBy { it.deviceId }.take(limit)
        }
    }
    fun observeRecent(limit: Int): Flow<List<ShareActivity>>
    fun observeDevice(device: DeviceKey): Flow<List<ShareActivity>>
    suspend fun upsert(activity: ShareActivity)
    suspend fun markNonTerminalInterrupted(nowMillis: Long)
    suspend fun prune(nowMillis: Long)
}
