package com.unishare.app.presentation.pairing

import com.unishare.app.data.device.DeviceRepository
import com.unishare.app.data.sdk.GatewayEvent
import com.unishare.app.data.sdk.ShareSessionManager
import com.unishare.app.domain.DeviceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

interface PairingOperations {
    val events: Flow<GatewayEvent>
    suspend fun confirmPairing(requestId: Long, codeMatches: Boolean)
    suspend fun rejectPairing(requestId: Long)
}

class SessionPairingOperations(
    private val session: ShareSessionManager,
) : PairingOperations {
    override val events: Flow<GatewayEvent> = session.events
    override suspend fun confirmPairing(requestId: Long, codeMatches: Boolean) {
        session.confirmPairing(requestId, codeMatches)
    }
    override suspend fun rejectPairing(requestId: Long) {
        session.rejectPairing(requestId)
    }
}

class PairingCoordinator(
    private val pairing: PairingOperations,
    private val devices: DeviceRepository,
    private val workerScope: CoroutineScope,
) {
    private val gate = Any()
    private val queued = ArrayDeque<PairingPrompt>()
    private val knownRequestIds = mutableSetOf<Long>()
    private val decidedRequestIds = mutableSetOf<Long>()
    private val expirations = mutableMapOf<Long, Job>()
    private val matchingDevices = mutableMapOf<Long, DeviceKey>()
    private var current: PairingPrompt? = null
    private var submitting = false

    private val mutableUiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = mutableUiState.asStateFlow()

    init {
        workerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pairing.events.collect(::handleEvent)
        }
    }

    fun confirmMatch() = decideVerification(codeMatches = true)

    fun rejectMismatch() = decideVerification(codeMatches = false)

    fun dismiss() {
        val prompt = claimCurrent<PairingPrompt>() ?: return
        workerScope.launch {
            if (prompt is PairingPrompt.Verification) {
                try {
                    pairing.rejectPairing(prompt.requestId)
                } catch (_: Exception) {
                    // A local dismissal is final even if the peer has already timed out.
                }
            }
            finish(prompt)
        }
    }

    fun forgetAndRepair() {
        val prompt = claimCurrent<PairingPrompt.IdentityChanged>() ?: return
        workerScope.launch {
            try {
                devices.forget(prompt.deviceId)
                // A new SAS must be promotable while connect awaits its decision.
                finish(prompt)
                devices.connect(prompt.deviceId)
            } catch (_: Exception) {
                // Identity changes never regain trust by falling through to confirmation.
            }
            finish(prompt)
        }
    }

    private fun decideVerification(codeMatches: Boolean) {
        val prompt = claimCurrent<PairingPrompt.Verification>() ?: return
        workerScope.launch {
            try {
                pairing.confirmPairing(prompt.requestId, codeMatches)
                if (codeMatches) waitForPeer(prompt) else finish(prompt)
            } catch (_: Exception) {
                finish(prompt)
            }
        }
    }

    private suspend fun handleEvent(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.PairingRequested -> enqueueVerification(event)
            is GatewayEvent.PairingCompleted -> finishRequest(event.requestId)
            is GatewayEvent.IdentityChanged -> enqueueIdentityChange(event)
            else -> Unit
        }
    }

    private suspend fun enqueueVerification(event: GatewayEvent.PairingRequested) {
        if (!synchronized(gate) { knownRequestIds.add(event.requestId) }) return
        val code = event.verificationCode.normalizedPairingCode()
        if (code == null) {
            synchronized(gate) { decidedRequestIds.add(event.requestId) }
            try {
                pairing.rejectPairing(event.requestId)
            } catch (_: Exception) {
                // Invalid security material is never rendered or persisted.
            }
            return
        }
        synchronized(gate) {
            queued += PairingPrompt.Verification(
                requestId = event.requestId,
                deviceId = event.device.id,
                deviceName = event.device.name,
                platform = event.device.platform,
                code = code,
            )
            promoteLocked()
            expirations[event.requestId] = workerScope.launch {
                delay(120_000)
                val reject = synchronized(gate) {
                    expirations.remove(event.requestId)
                    val exists = (current as? PairingPrompt.Verification)?.requestId == event.requestId ||
                        queued.any { it is PairingPrompt.Verification && it.requestId == event.requestId }
                    exists && decidedRequestIds.add(event.requestId)
                }
                finishRequest(event.requestId)
                if (reject) try { pairing.rejectPairing(event.requestId) } catch (_: Exception) { }
            }
        }
    }

    private fun enqueueIdentityChange(event: GatewayEvent.IdentityChanged) = synchronized(gate) {
        val device = devices.find(event.deviceId)
        val duplicate = current?.let { it is PairingPrompt.IdentityChanged && it.deviceId == event.deviceId } == true ||
            queued.any { it is PairingPrompt.IdentityChanged && it.deviceId == event.deviceId }
        if (duplicate) return@synchronized
        queued += PairingPrompt.IdentityChanged(
            deviceId = event.deviceId,
            deviceName = device?.name ?: event.deviceId.value,
            platform = device?.platform,
        )
        promoteLocked()
    }

    private fun finishRequest(requestId: Long) = synchronized(gate) {
        knownRequestIds.add(requestId)
        expirations.remove(requestId)?.cancel()
        matchingDevices.remove(requestId)
        val prompt = current
        if (prompt is PairingPrompt.Verification && prompt.requestId == requestId) {
            finishLocked(prompt)
        } else {
            queued.removeAll { it is PairingPrompt.Verification && it.requestId == requestId }
            promoteLocked()
        }
    }

    private fun waitForPeer(prompt: PairingPrompt.Verification) = synchronized(gate) {
        if (current != prompt) return@synchronized
        matchingDevices[prompt.requestId] = prompt.deviceId
        current = null
        submitting = false
        promoteLocked()
    }

    private fun finish(prompt: PairingPrompt) = synchronized(gate) {
        if (current == prompt) finishLocked(prompt)
    }

    private fun finishLocked(prompt: PairingPrompt) {
        if (prompt is PairingPrompt.Verification) expirations.remove(prompt.requestId)?.cancel()
        current = null
        submitting = false
        promoteLocked()
    }

    private inline fun <reified T : PairingPrompt> claimCurrent(): T? = synchronized(gate) {
        val prompt = current as? T ?: return@synchronized null
        if (submitting) return@synchronized null
        if (prompt is PairingPrompt.Verification && !decidedRequestIds.add(prompt.requestId)) return@synchronized null
        submitting = true
        publishLocked()
        prompt
    }

    private fun promoteLocked() {
        if (current == null && matchingDevices.isEmpty()) current = queued.removeFirstOrNull()
        publishLocked()
    }

    private fun publishLocked() {
        mutableUiState.value = PairingUiState(
            prompt = current,
            submitting = submitting,
            matchingDeviceIds = matchingDevices.values.toSet(),
        )
    }

}

private fun String.normalizedPairingCode(): String? {
    if (isEmpty() || length > 6 || any { it !in '0'..'9' }) return null
    return padStart(6, '0')
}
