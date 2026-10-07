package com.unishare.app.data.history

import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareActivity
import kotlinx.coroutines.flow.Flow

interface ActivityRepository {
    fun observeRecent(limit: Int): Flow<List<ShareActivity>>
    fun observeDevice(device: DeviceKey): Flow<List<ShareActivity>>
    suspend fun upsert(activity: ShareActivity)
    suspend fun markNonTerminalInterrupted(nowMillis: Long)
    suspend fun prune(nowMillis: Long)
}
