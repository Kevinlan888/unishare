package com.unishare.app.data.device

import com.unishare.app.data.sdk.*
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareErrorKind
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDeviceRepositoryTest {
    @Test fun simultaneous_connects_share_authenticated_result_and_refresh_trust() = runTest {
        val key = DeviceKey("a")
        val gateway = FakeShareSdkGateway().apply { snapshots = listOf(peer("a", "peer")); connectCompletion = CompletableDeferred() }
        val session = ShareSessionManager(gateway, backgroundScope)
        val repository = DefaultDeviceRepository(session, backgroundScope)
        session.initialize(GatewayConfig("local")); session.start(); runCurrent()
        val first = launch { repository.connect(key) }
        val second = launch { repository.connect(key) }
        runCurrent()
        assertEquals(1, gateway.calls.count { it == "connect:a" })
        gateway.trusted = setOf(key)
        gateway.connectCompletion!!.complete(Unit)
        first.join(); second.join()
        assertTrue(repository.find(key)!!.connected)
        assertTrue(repository.isTrusted(key))
        // StateFlow may conflate STOPPED -> RUNNING before the repository collector runs.
        session.stop(); session.start()
        repository.connect(key)
        assertEquals(2, gateway.calls.count { it == "connect:a" })
    }
    @Test fun snapshots_merge_online_updates_and_keep_trusted_offline_without_duplicates() = runTest {
        val gateway = FakeShareSdkGateway().apply { snapshots = listOf(peer("a", "old")); trusted = setOf(DeviceKey("a"), DeviceKey("offline")) }
        val session = ShareSessionManager(gateway, backgroundScope)
        val repository = DefaultDeviceRepository(session, backgroundScope)
        session.initialize(GatewayConfig("local")); runCurrent()
        assertEquals(setOf(DeviceKey("a"), DeviceKey("offline")), repository.devices.value.map { it.id }.toSet())
        assertTrue(repository.isTrusted(DeviceKey("offline")))
        gateway.events.emit(GatewayEvent.DeviceUpdated(peer("a", "new"))); runCurrent()
        assertEquals("new", repository.devices.value.single { it.id == DeviceKey("a") }.name)
        gateway.events.emit(GatewayEvent.DeviceOffline(DeviceKey("a"))); runCurrent()
        assertFalse(repository.devices.value.single { it.id == DeviceKey("a") }.online)
        gateway.events.emit(GatewayEvent.DeviceOnline(peer("nearby", "nearby"))); runCurrent()
        gateway.events.emit(GatewayEvent.DeviceOffline(DeviceKey("nearby"))); runCurrent()
        assertEquals(2, repository.devices.value.size)
        session.close()
    }

    @Test fun identity_changed_blocks_commands_until_successful_forget() = runTest {
        val gateway = FakeShareSdkGateway().apply { snapshots = listOf(peer("a", "peer")); trusted = setOf(DeviceKey("a")) }
        val session = ShareSessionManager(gateway, backgroundScope)
        val repository = DefaultDeviceRepository(session, backgroundScope)
        try { repository.connect(DeviceKey("a")); fail("Not ready") } catch (_: IllegalStateException) { }
        assertTrue(gateway.calls.isEmpty())
        session.initialize(GatewayConfig("local")); runCurrent()
        gateway.events.emit(GatewayEvent.IdentityChanged(DeviceKey("a"))); runCurrent()
        assertFalse(repository.canSend(DeviceKey("a")))
        try { repository.connect(DeviceKey("a")); fail("Identity must block") } catch (error: GatewayException) { assertEquals(ShareErrorKind.IDENTITY_CHANGED, error.failure.kind) }
        assertFalse(gateway.calls.any { it.startsWith("connect:") })
        repository.forget(DeviceKey("a"))
        assertFalse(repository.isTrusted(DeviceKey("a")))
        assertFalse(repository.devices.value.single().identityChanged)
        repository.connect(DeviceKey("a"))
        assertTrue(gateway.calls.contains("connect:a"))
        session.close()
    }

    @Test fun pairing_success_while_stopping_does_not_retire_the_device_event_collector() = runTest {
        val gateway = FakeShareSdkGateway().apply { snapshots = listOf(peer("a", "before")); trusted = setOf(DeviceKey("a")) }
        val session = ShareSessionManager(gateway, backgroundScope)
        val repository = DefaultDeviceRepository(session, backgroundScope)
        session.initialize(GatewayConfig("local")); session.start(); runCurrent()
        gateway.stopCompletion = CompletableDeferred()
        val stopping = launch { session.stop() }
        runCurrent()
        gateway.events.emit(GatewayEvent.PairingCompleted(42, DeviceKey("a"))); runCurrent()
        gateway.stopCompletion!!.complete(Unit)
        stopping.join(); session.start(); runCurrent()
        gateway.events.emit(GatewayEvent.DeviceUpdated(peer("a", "after"))); runCurrent()
        assertEquals("after", repository.devices.value.single().name)
        session.close()
    }

    @Test fun newer_identity_event_processed_before_old_forget_continuation_stays_blocked() = runTest {
        val gateway = FakeShareSdkGateway().apply { snapshots = listOf(peer("a", "peer")); trusted = setOf(DeviceKey("a")) }
        val session = ShareSessionManager(gateway, backgroundScope)
        val repository = DefaultDeviceRepository(session, backgroundScope)
        session.initialize(GatewayConfig("local")); runCurrent()
        gateway.events.emit(GatewayEvent.IdentityChanged(DeviceKey("a"))); runCurrent()
        val continuationDispatcher = ManualDispatcher()
        gateway.forgetCompletion = CompletableDeferred()
        val forgetting = backgroundScope.launch(continuationDispatcher) { repository.forget(DeviceKey("a")) }
        continuationDispatcher.runAll()
        gateway.forgetCompletion!!.complete(Unit)
        // Deliver a newer identity event while the completed forget's app continuation is queued.
        gateway.events.emit(GatewayEvent.IdentityChanged(DeviceKey("a"))); runCurrent()
        assertTrue(repository.find(DeviceKey("a"))!!.identityChanged)
        continuationDispatcher.runAll(); forgetting.join()
        assertTrue(repository.find(DeviceKey("a"))!!.identityChanged)
        assertFalse(repository.canSend(DeviceKey("a")))
        assertFalse(repository.isTrusted(DeviceKey("a")))
        try { repository.connect(DeviceKey("a")); fail("New identity event must remain blocked") }
        catch (error: GatewayException) { assertEquals(ShareErrorKind.IDENTITY_CHANGED, error.failure.kind) }
        session.close()
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val queued = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun runAll() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    @Test fun stale_snapshot_cannot_revive_peer_after_newer_offline_event() = runTest {
        assertFalse(pausedSnapshotAfter(SnapshotBoundary.OFFLINE).online)
    }

    @Test fun stale_snapshot_cannot_restore_trust_after_successful_forget() = runTest {
        assertFalse(pausedSnapshotAfter(SnapshotBoundary.FORGET).trusted)
    }

    @Test fun stale_snapshot_cannot_revive_peer_after_session_stopped() = runTest {
        assertFalse(pausedSnapshotAfter(SnapshotBoundary.STOPPED).online)
    }

    @Test fun stale_snapshot_cannot_revive_peer_after_session_closed() = runTest {
        assertFalse(pausedSnapshotAfter(SnapshotBoundary.CLOSED).online)
    }

    private enum class SnapshotBoundary { OFFLINE, FORGET, STOPPED, CLOSED }

    private suspend fun TestScope.pausedSnapshotAfter(boundary: SnapshotBoundary): DeviceSummary {
        val key = DeviceKey("a")
        val gateway = FakeShareSdkGateway().apply { snapshots = listOf(peer("a", "peer")); trusted = setOf(key) }
        val session = ShareSessionManager(gateway, backgroundScope)
        val repository = DefaultDeviceRepository(session, backgroundScope)
        session.initialize(GatewayConfig("local")); session.start(); runCurrent()
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "refresh-test").apply { isDaemon = true } }
        gateway.trustedSnapshotProvider = {
            val old = gateway.trusted.toSet()
            captured.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Snapshot release timed out" }
            old
        }
        try {
            val refreshing = executor.submit { repository.refresh() }
            assertTrue("Old snapshot must be captured", captured.await(5, TimeUnit.SECONDS))
            when (boundary) {
                SnapshotBoundary.OFFLINE -> { gateway.events.emit(GatewayEvent.DeviceOffline(key)); runCurrent(); assertFalse(repository.find(key)!!.online) }
                SnapshotBoundary.FORGET -> { repository.forget(key); assertFalse(repository.find(key)!!.trusted) }
                SnapshotBoundary.STOPPED -> { session.stop(); runCurrent(); assertFalse(repository.find(key)!!.online) }
                SnapshotBoundary.CLOSED -> { session.close(); runCurrent(); assertFalse(repository.find(key)!!.online) }
            }
            release.countDown()
            refreshing.get(5, TimeUnit.SECONDS)
            return repository.find(key)!!
        } finally {
            release.countDown(); gateway.trustedSnapshotProvider = null
            executor.shutdownNow(); session.close(); runCurrent()
        }
    }

    private fun peer(id: String, name: String) = DeviceSnapshot(DeviceKey(id), name, DevicePlatform.ANDROID, paired = true)
}
