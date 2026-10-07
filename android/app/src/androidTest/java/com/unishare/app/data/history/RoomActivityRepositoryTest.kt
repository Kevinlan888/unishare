package com.unishare.app.data.history

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unishare.app.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomActivityRepositoryTest {
    private lateinit var database: ActivityDatabase
    private lateinit var repository: RoomActivityRepository
    private val now = 40L * 24 * 60 * 60 * 1000
    private val day = 24L * 60 * 60 * 1000
    private val device = DeviceKey(UUID.randomUUID().toString())
    private val otherDevice = DeviceKey(UUID.randomUUID().toString())

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ActivityDatabase::class.java,
        ).build()
        repository = RoomActivityRepository(database, clock = { now })
    }

    @After fun tearDown() { database.close() }

    @Test fun summaryRoundTripsEveryField() = runBlocking {
        val activity = activity().copy(
            transferId = TransferKey(UUID.randomUUID().toString()),
            operationId = OperationKey(UUID.randomUUID().toString()),
            retryOf = ActivityKey(UUID.randomUUID().toString()),
            status = ActivityStatus.COMPLETED,
            transferredBytes = 512,
            completedAtMillis = now,
            finalContentUri = "content://received/item/1",
        )
        repository.upsert(activity)
        assertEquals(activity, repository.observeRecent(10).first().single())
    }

    @Test fun allEnumsRoundTripThroughRoomConverters() = runBlocking {
        var timestamp = now
        for (direction in ShareDirection.entries) for (kind in ContentKind.entries)
            for (origin in ShareOrigin.entries) for (status in ActivityStatus.entries) {
                val item = activity().copy(direction = direction, kind = kind, origin = origin, status = status,
                    updatedAtMillis = ++timestamp)
                repository.upsert(item)
                assertEquals(item, repository.observeRecent(1).first().single { it.id == item.id })
            }
        for (error in ShareErrorKind.entries) {
            val item = activity().copy(status = ActivityStatus.FAILED, errorKind = error, updatedAtMillis = ++timestamp)
            repository.upsert(item)
            assertEquals(item, repository.observeRecent(1).first().single())
        }
    }

    @Test fun deviceQueryFiltersAndOrdersMostRecentlyUpdatedFirst() = runBlocking {
        val old = activity().copy(updatedAtMillis = now - 2)
        val newer = activity().copy(updatedAtMillis = now - 1)
        val other = activity().copy(deviceId = otherDevice)
        listOf(newer, other, old).forEach { repository.upsert(it) }
        assertEquals(listOf(newer, old), repository.observeDevice(device).first())
        assertEquals(listOf(other, newer), repository.observeRecent(2).first())
    }

    @Test fun upsertReplacesTheSameIdentityWithoutDuplicatingIt() = runBlocking {
        val original = activity()
        repository.upsert(original)
        val updated = original.copy(status = ActivityStatus.TRANSFERRING, transferredBytes = 256)
        repository.upsert(updated)
        assertEquals(listOf(updated), repository.observeDevice(device).first())
    }

    @Test fun summaryDisplayNameNeverPersistsPathOrControlCharacters() = runBlocking {
        repository.upsert(activity().copy(displayName = "C:\\private\\document.pdf\u0000\n"))
        assertEquals("document.pdf", repository.observeRecent(1).first().single().displayName)
    }

    @Test fun restartFailsOnlyNonterminalRowsAndClearsTheirUri() = runBlocking {
        val rows = ActivityStatus.entries.map { activity().copy(status = it) }
        rows.forEach {
            // Model a stale/corrupt pre-restart URI to verify the UPDATE clears it too.
            database.activityDao().upsert(ActivityEntity.fromDomain(it).copy(
                finalContentUri = if (it.status.isTerminal) null else "content://stale/item/1",
            ))
        }
        repository.markNonTerminalInterrupted(now + 7)
        val actual = repository.observeRecent(20).first().associateBy { it.id }
        for (row in rows) {
            val result = actual.getValue(row.id)
            if (row.status.isTerminal) assertEquals(row, result)
            else {
                assertEquals(ActivityStatus.FAILED, result.status)
                assertEquals(ShareErrorKind.INTERRUPTED, result.errorKind)
                assertEquals(now + 7, result.updatedAtMillis)
                assertEquals(now + 7, result.completedAtMillis)
                assertNull(result.finalContentUri)
            }
        }
    }

    @Test fun uriPersistsOnlyForCompletedIncomingImageOrFile() = runBlocking {
        for (direction in ShareDirection.entries) for (kind in ContentKind.entries)
            for (status in ActivityStatus.entries) {
                val item = activity().copy(direction = direction, kind = kind, status = status,
                    updatedAtMillis = now + 1, finalContentUri = "content://received/item/1")
                repository.upsert(item)
                val persisted = repository.observeDevice(device).first().single { it.id == item.id }
                val allowed = direction == ShareDirection.INCOMING && kind != ContentKind.TEXT && status == ActivityStatus.COMPLETED
                assertEquals(if (allowed) item.finalContentUri else null, persisted.finalContentUri)
            }
    }

    @Test fun pruneKeepsNewest500ForEachDeviceWithDeterministicTies() = runBlocking {
        val rows = (0..502).map { index -> activity().copy(
            id = ActivityKey("00000000-0000-0000-0000-" + index.toString().padStart(12, '0')),
            updatedAtMillis = now,
        ) }
        rows.forEach { database.activityDao().upsert(ActivityEntity.fromDomain(it)) }
        val other = activity().copy(deviceId = otherDevice)
        database.activityDao().upsert(ActivityEntity.fromDomain(other))
        repository.prune(now)
        val retained = repository.observeDevice(device).first()
        assertEquals(500, retained.size)
        assertEquals(rows.drop(3).reversed().map { it.id }, retained.map { it.id })
        assertEquals(listOf(other), repository.observeDevice(otherDevice).first())
    }

    @Test fun pruneDeletesOlderThan30DaysGloballyButKeepsBoundaryAndOtherValidRows() = runBlocking {
        val expired = activity().copy(updatedAtMillis = now - 30 * day - 1)
        val expiredOther = expired.copy(id = ActivityKey(UUID.randomUUID().toString()), deviceId = otherDevice)
        val boundary = activity().copy(updatedAtMillis = now - 30 * day)
        val validOther = activity().copy(deviceId = otherDevice)
        listOf(expired, expiredOther, boundary, validOther).forEach {
            database.activityDao().upsert(ActivityEntity.fromDomain(it))
        }
        repository.prune(now)
        assertEquals(listOf(boundary), repository.observeDevice(device).first())
        assertEquals(listOf(validOther), repository.observeDevice(otherDevice).first())
    }

    @Test fun upsertEnforcesRetentionWithoutASeparateMaintenanceCall() = runBlocking {
        (0..500).forEach { repository.upsert(activity().copy(updatedAtMillis = now + it)) }
        repository.upsert(activity().copy(deviceId = otherDevice, updatedAtMillis = now - 31 * day))
        assertEquals(500, repository.observeDevice(device).first().size)
        assertTrue(repository.observeDevice(otherDevice).first().isEmpty())
    }

    private fun activity() = ShareActivity(
        id = ActivityKey(UUID.randomUUID().toString()), deviceId = device,
        direction = ShareDirection.INCOMING, kind = ContentKind.FILE, origin = ShareOrigin.DIRECT,
        displayName = "document.pdf", mimeType = "application/pdf", byteCount = 512,
        createdAtMillis = now - 10, updatedAtMillis = now,
    )
}
