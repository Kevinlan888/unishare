package com.unishare.app.presentation.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unishare.app.AppGraph
import com.unishare.app.data.device.DeviceRepository
import com.unishare.app.data.sdk.GatewayEvent
import com.unishare.app.data.sdk.ShareSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface PairingOperations {
    val events: Flow<GatewayEvent>
    suspend fun confirmPairing(requestId: Long, codeMatches: Boolean)
    suspend fun rejectPairing(requestId: Long)
}

private class SessionPairingOperations(
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

class PairingViewModel internal constructor(
    private val pairing: PairingOperations,
    private val devices: DeviceRepository,
    providedScope: CoroutineScope? = null,
) : ViewModel() {
    private val workerScope = providedScope ?: viewModelScope
    private val gate = Any()
    private val queued = ArrayDeque<PairingPrompt>()
    private val knownRequestIds = mutableSetOf<Long>()
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
                if (!codeMatches) finish(prompt)
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
        val code = event.verificationCode.normalizedPairingCode()
        if (code == null) {
            try {
                pairing.rejectPairing(event.requestId)
            } catch (_: Exception) {
                // Invalid security material is never rendered or persisted.
            }
            return
        }
        synchronized(gate) {
            if (!knownRequestIds.add(event.requestId)) return
            queued += PairingPrompt.Verification(
                requestId = event.requestId,
                deviceId = event.device.id,
                deviceName = event.device.name,
                platform = event.device.platform,
                code = code,
            )
            promoteLocked()
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
        val prompt = current
        if (prompt is PairingPrompt.Verification && prompt.requestId == requestId) {
            finishLocked(prompt)
        } else {
            queued.removeAll { it is PairingPrompt.Verification && it.requestId == requestId }
            knownRequestIds -= requestId
        }
    }

    private fun finish(prompt: PairingPrompt) = synchronized(gate) {
        if (current == prompt) finishLocked(prompt)
    }

    private fun finishLocked(prompt: PairingPrompt) {
        if (prompt is PairingPrompt.Verification) knownRequestIds -= prompt.requestId
        current = null
        submitting = false
        promoteLocked()
    }

    private inline fun <reified T : PairingPrompt> claimCurrent(): T? = synchronized(gate) {
        val prompt = current as? T ?: return@synchronized null
        if (submitting) return@synchronized null
        submitting = true
        publishLocked()
        prompt
    }

    private fun promoteLocked() {
        if (current == null) current = queued.removeFirstOrNull()
        publishLocked()
    }

    private fun publishLocked() {
        mutableUiState.value = PairingUiState(current, submitting)
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PairingViewModel(
            pairing = SessionPairingOperations(graph.sessionManager),
            devices = graph.deviceRepository,
        ) as T
    }
}

private fun String.normalizedPairingCode(): String? {
    if (isEmpty() || length > 6 || any { !it.isDigit() }) return null
    return padStart(6, '0')
}
