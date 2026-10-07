package com.unishare.app.data.device

import com.unishare.app.data.sdk.*
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareErrorKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDeviceRepositoryTest {
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

    private fun peer(id: String, name: String) = DeviceSnapshot(DeviceKey(id), name, DevicePlatform.ANDROID, paired = true)
}
