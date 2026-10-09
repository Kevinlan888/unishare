package com.unishare.app.data.sdk

import com.unishare.app.domain.DeviceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareSessionManagerTest {
    @Test fun concurrent_initialize_registers_once_after_success_and_start_requires_ready() = runTest {
        val gateway = FakeShareSdkGateway()
        val session = ShareSessionManager(gateway, backgroundScope)
        assertFails { session.start() }
        assertTrue(gateway.calls.isEmpty())
        repeat(3) { launch { session.initialize(GatewayConfig("local")) } }
        runCurrent()
        session.start(); session.start()
        assertEquals(listOf("initialize", "subscribe", "foreground:true", "start"), gateway.calls)
        assertEquals(SessionState.RUNNING, session.state.value)
        session.close()
    }

    @Test fun initialization_failure_can_retry_without_registering_early() = runTest {
        val gateway = FakeShareSdkGateway().apply { initializeFailure = GatewayException(ShareFailure(com.unishare.app.domain.ShareErrorKind.PERMISSION_DENIED)) }
        val session = ShareSessionManager(gateway, backgroundScope)
        assertFails { session.initialize(GatewayConfig("local")) }
        assertEquals(SessionState.FAULTED, session.state.value)
        assertEquals(listOf("initialize"), gateway.calls)
        gateway.initializeFailure = null
        session.initialize(GatewayConfig("local"))
        assertEquals(listOf("initialize", "initialize", "subscribe"), gateway.calls)
        assertEquals(SessionState.READY, session.state.value)
        session.close()
    }

    @Test fun stop_finishes_before_background_and_final_close_is_idempotent() = runTest {
        val gateway = FakeShareSdkGateway()
        val session = ShareSessionManager(gateway, backgroundScope)
        session.initialize(GatewayConfig("local")); session.start()
        gateway.stopCompletion = CompletableDeferred()
        val stop = launch { session.stop() }
        runCurrent()
        assertEquals(SessionState.STOPPING, session.state.value)
        assertFalse(gateway.calls.contains("foreground:false"))
        gateway.stopCompletion!!.complete(Unit)
        stop.join(); session.stop(); session.close(); session.close()
        assertEquals(listOf("initialize", "subscribe", "foreground:true", "start", "stop", "stopped", "foreground:false", "unsubscribe", "close"), gateway.calls)
        assertEquals(SessionState.CLOSED, session.state.value)
        assertFails { session.initialize(GatewayConfig("local")) }
        assertFails { session.start() }
    }

    @Test fun observer_is_listening_when_subscriptions_open_and_close_prevents_future_delivery() = runTest {
        val gateway = FakeShareSdkGateway()
        val session = ShareSessionManager(gateway, backgroundScope)
        val received = mutableListOf<GatewayEvent>()
        backgroundScope.launch { session.events.collect { received += it } }
        runCurrent()
        gateway.onSubscribe = { gateway.events.tryEmit(GatewayEvent.IdentityChanged(DeviceKey("one"))) }
        session.initialize(GatewayConfig("local")); runCurrent()
        assertEquals(listOf(GatewayEvent.IdentityChanged(DeviceKey("one"))), received)
        session.close()
        gateway.events.emit(GatewayEvent.IdentityChanged(DeviceKey("late"))); runCurrent()
        assertEquals(1, received.size)
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block(); fail("Expected failure") } catch (_: IllegalStateException) { } catch (_: GatewayException) { }
    }
}
