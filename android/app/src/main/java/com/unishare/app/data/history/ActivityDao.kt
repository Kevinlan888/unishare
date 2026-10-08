package com.unishare.app.data.history

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Query("SELECT DISTINCT deviceId FROM activities ORDER BY deviceId")
    fun observeDeviceIds(): Flow<List<String>>

    @Query("""
        SELECT * FROM activities
        WHERE (:deviceId IS NULL OR deviceId = :deviceId)
          AND (:direction IS NULL OR direction = :direction)
          AND (:kind IS NULL OR kind = :kind)
          AND (:status IS NULL OR status = :status)
        ORDER BY updatedAt DESC, id DESC
    """)
    fun observeFiltered(deviceId: String?, direction: String?, kind: String?, status: String?): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE deviceId = :deviceId ORDER BY updatedAt DESC, id DESC")
    fun observeDevice(deviceId: String): Flow<List<ActivityEntity>>

    @Upsert suspend fun upsert(activity: ActivityEntity)

    @Query("""
        UPDATE activities SET status = 'FAILED', errorKind = 'INTERRUPTED',
            updatedAt = MAX(updatedAt, createdAt, :nowMillis),
            completedAt = MAX(updatedAt, createdAt, :nowMillis), finalContentUri = NULL
        WHERE status IN ('AWAITING_PEER', 'TRANSFERRING')
    """)
    suspend fun markNonTerminalInterrupted(nowMillis: Long)

    @Query("DELETE FROM activities WHERE updatedAt < :cutoffMillis")
    suspend fun deleteExpired(cutoffMillis: Long)

    @Query("""
        DELETE FROM activities WHERE id IN (
            SELECT older.id FROM activities AS older
            WHERE (
                SELECT COUNT(*) FROM activities AS newer
                WHERE newer.deviceId = older.deviceId AND
                    (newer.updatedAt > older.updatedAt OR
                        (newer.updatedAt = older.updatedAt AND newer.id > older.id))
            ) >= :maxPerDevice
        )
    """)
    suspend fun deleteBeyondDeviceLimit(maxPerDevice: Int)
}
