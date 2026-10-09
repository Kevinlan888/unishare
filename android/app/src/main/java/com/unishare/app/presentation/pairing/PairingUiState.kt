package com.unishare.app.presentation.pairing

import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.domain.DeviceKey

sealed interface PairingPrompt {
    val deviceId: DeviceKey
    val deviceName: String
    val platform: DevicePlatform?

    data class Verification(
        val requestId: Long,
        override val deviceId: DeviceKey,
        override val deviceName: String,
        override val platform: DevicePlatform?,
        val code: String,
    ) : PairingPrompt

    data class IdentityChanged(
        override val deviceId: DeviceKey,
        override val deviceName: String,
        override val platform: DevicePlatform?,
    ) : PairingPrompt
}

data class PairingUiState(
    val prompt: PairingPrompt? = null,
    val submitting: Boolean = false,
)
