package com.unishare.app.presentation.pairing

import com.unishare.app.data.device.DeviceRepository
import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.data.sdk.DeviceSnapshot
import com.unishare.app.data.sdk.GatewayEvent
import com.unishare.app.data.sdk.ShareFailure
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareErrorKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    @Test
    fun simultaneous_requests_are_serialized_and_a_request_is_decided_once() = runTest {
        val pairing = FakePairingOperations()
        val viewModel = PairingViewModel(pairing, FakePairingDevices(), immediateBackgroundScope())

        pairing.events.emit(pairingRequest(11, "000042", "First PC"))
        pairing.events.emit(pairingRequest(12, "654321", "Second PC"))
        advanceUntilIdle()

        val first = viewModel.uiState.value.prompt as PairingPrompt.Verification
        assertEquals("000042", first.code)
        assertEquals("First PC", first.deviceName)

        viewModel.confirmMatch()
        viewModel.confirmMatch()
        viewModel.rejectMismatch()
        advanceUntilIdle()

        assertEquals(listOf(PairingCall.Confirm(11, true)), pairing.calls)
        assertTrue(viewModel.uiState.value.submitting)

        pairing.events.emit(GatewayEvent.PairingCompleted(11, DeviceKey("device-11")))
        advanceUntilIdle()

        val second = viewModel.uiState.value.prompt as PairingPrompt.Verification
        assertEquals("654321", second.code)
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun mismatch_and_explicit_dismiss_use_distinct_single_rejection_paths() = runTest {
        val pairing = FakePairingOperations()
        val viewModel = PairingViewModel(pairing, FakePairingDevices(), immediateBackgroundScope())

        pairing.events.emit(pairingRequest(21, "123456"))
        advanceUntilIdle()
        viewModel.rejectMismatch()
        viewModel.rejectMismatch()
        advanceUntilIdle()
        assertEquals(listOf(PairingCall.Confirm(21, false)), pairing.calls)

        pairing.events.emit(GatewayEvent.PairingCompleted(21, DeviceKey("device-21")))
        pairing.events.emit(pairingRequest(22, "234567"))
        advanceUntilIdle()
        viewModel.dismiss()
        viewModel.dismiss()
        advanceUntilIdle()
        assertEquals(
            listOf(PairingCall.Confirm(21, false), PairingCall.Reject(22)),
            pairing.calls,
        )
    }

    @Test
    fun malformed_code_is_never_shown_and_is_rejected() = runTest {
        val pairing = FakePairingOperations()
        val viewModel = PairingViewModel(pairing, FakePairingDevices(), immediateBackgroundScope())

        pairing.events.emit(pairingRequest(31, "12345x"))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.prompt)
        assertEquals(listOf(PairingCall.Reject(31)), pairing.calls)
    }

    @Test
    fun timeout_or_command_error_dismisses_the_current_request() = runTest {
        val pairing = FakePairingOperations()
        val viewModel = PairingViewModel(pairing, FakePairingDevices(), immediateBackgroundScope())

        pairing.events.emit(pairingRequest(41, "012345"))
        advanceUntilIdle()
        pairing.events.emit(
            GatewayEvent.PairingCompleted(
                41,
                DeviceKey("device-41"),
                ShareFailure(ShareErrorKind.TIMEOUT),
            ),
        )
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.prompt)

        pairing.failCommands = true
        pairing.events.emit(pairingRequest(42, "123456"))
        advanceUntilIdle()
        viewModel.confirmMatch()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.prompt)
    }

    @Test
    fun identity_change_requires_forget_then_repair_instead_of_confirming() = runTest {
        val deviceId = DeviceKey("changed-device")
        val pairing = FakePairingOperations()
        val devices = FakePairingDevices(
            DeviceSummary(
                id = deviceId,
                name = "Changed PC",
                platform = DevicePlatform.WINDOWS,
                online = true,
                trusted = true,
                identityChanged = true,
            ),
        )
        val viewModel = PairingViewModel(pairing, devices, immediateBackgroundScope())

        pairing.events.emit(GatewayEvent.IdentityChanged(deviceId))
        advanceUntilIdle()

        val prompt = viewModel.uiState.value.prompt as PairingPrompt.IdentityChanged
        assertEquals("Changed PC", prompt.deviceName)
        viewModel.forgetAndRepair()
        viewModel.forgetAndRepair()
        advanceUntilIdle()

        assertEquals(listOf("forget:changed-device", "connect:changed-device"), devices.calls)
        assertTrue(pairing.calls.isEmpty())
        assertNull(viewModel.uiState.value.prompt)
    }

    @Test
    fun identity_change_can_be_dismissed_without_confirming_or_forgetting() = runTest {
        val deviceId = DeviceKey("changed-device")
        val pairing = FakePairingOperations()
        val devices = FakePairingDevices()
        val viewModel = PairingViewModel(pairing, devices, immediateBackgroundScope())

        pairing.events.emit(GatewayEvent.IdentityChanged(deviceId))
        advanceUntilIdle()
        viewModel.dismiss()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.prompt)
        assertTrue(pairing.calls.isEmpty())
        assertTrue(devices.calls.isEmpty())
    }

    private fun pairingRequest(
        requestId: Long,
        code: String,
        name: String = "Test PC",
    ) = GatewayEvent.PairingRequested(
        requestId = requestId,
        device = DeviceSnapshot(
            id = DeviceKey("device-$requestId"),
            name = name,
            platform = DevicePlatform.WINDOWS,
        ),
        verificationCode = code,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.immediateBackgroundScope(): CoroutineScope =
    CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

private sealed interface PairingCall {
    data class Confirm(val requestId: Long, val matches: Boolean) : PairingCall
    data class Reject(val requestId: Long) : PairingCall
}

private class FakePairingOperations : PairingOperations {
    override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
    val calls = mutableListOf<PairingCall>()
    var failCommands = false

    override suspend fun confirmPairing(requestId: Long, codeMatches: Boolean) {
        if (failCommands) error("command failed")
        calls += PairingCall.Confirm(requestId, codeMatches)
    }

    override suspend fun rejectPairing(requestId: Long) {
        if (failCommands) error("command failed")
        calls += PairingCall.Reject(requestId)
    }
}

private class FakePairingDevices(vararg summaries: DeviceSummary) : DeviceRepository {
    override val devices = MutableStateFlow(summaries.toList())
    val calls = mutableListOf<String>()
    override fun find(deviceId: DeviceKey) = devices.value.firstOrNull { it.id == deviceId }
    override fun isTrusted(deviceId: DeviceKey) = find(deviceId)?.trusted == true
    override fun canSend(deviceId: DeviceKey) = find(deviceId)?.let { it.online && it.trusted && !it.identityChanged } == true
    override suspend fun connect(deviceId: DeviceKey) { calls += "connect:${deviceId.value}" }
    override suspend fun forget(deviceId: DeviceKey) { calls += "forget:${deviceId.value}" }
}
