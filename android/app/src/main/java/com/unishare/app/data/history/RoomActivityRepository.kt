package com.unishare.app.data.history

import androidx.room.withTransaction
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomActivityRepository(
    private val database: ActivityDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ActivityRepository {
    private val dao = database.activityDao()

    override fun observeRecent(limit: Int): Flow<List<ShareActivity>> {
        require(limit > 0) { "History limit must be positive" }
        return dao.observeRecent(limit).map { rows -> rows.map(ActivityEntity::toDomain) }
    }

    override fun observeDevice(device: DeviceKey): Flow<List<ShareActivity>> =
        dao.observeDevice(device.value).map { rows -> rows.map(ActivityEntity::toDomain) }

    override suspend fun upsert(activity: ShareActivity) {
        database.withTransaction {
            dao.upsert(ActivityEntity.fromDomain(activity))
            pruneRows(clock())
        }
    }

    override suspend fun markNonTerminalInterrupted(nowMillis: Long) {
        database.withTransaction {
            // Expire first so restart cannot rejuvenate records older than retention.
            pruneRows(nowMillis)
            dao.markNonTerminalInterrupted(nowMillis)
        }
    }

    override suspend fun prune(nowMillis: Long) {
        database.withTransaction { pruneRows(nowMillis) }
    }

    private suspend fun pruneRows(nowMillis: Long) {
        dao.deleteExpired(nowMillis - 30L * 24 * 60 * 60 * 1000)
        dao.deleteBeyondDeviceLimit(500)
    }
}
