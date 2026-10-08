package com.unishare.app.service

import com.unishare.app.presentation.pairing.*
import com.unishare.app.domain.DeviceKey
import com.unishare.app.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingNotificationRelayTest {
    @Test fun late_subscriber_posts_background_prompt_and_cancels_on_foreground_decision_or_expiry() = runTest {
        val prompt = PairingPrompt.Verification(1, DeviceKey("pc"), "private", null, "000042")
        val pairing = MutableStateFlow(PairingUiState(prompt))
        val runtime = MutableStateFlow(RuntimeState(backgroundReceiveEnabled = true))
        val settings = MutableStateFlow(AppSettings())
        var visible = false
        PairingNotificationRelay(pairing, runtime, settings, backgroundScope, { visible = true }, { visible = false })
        runCurrent(); assertTrue(visible)
        runtime.value = runtime.value.copy(appVisible = true)
        runCurrent(); assertFalse(visible)
        runtime.value = runtime.value.copy(appVisible = false)
        runCurrent(); assertTrue(visible)
        pairing.value = PairingUiState(prompt, submitting = true)
        runCurrent(); assertFalse(visible)
        pairing.value = PairingUiState()
        runCurrent(); assertFalse(visible)
    }
}
