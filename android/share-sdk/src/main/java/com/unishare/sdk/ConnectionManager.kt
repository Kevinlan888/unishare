package com.unishare.sdk

class ConnectionManager internal constructor(private val sdk: ShareSdk) {
    fun connect(deviceId: DeviceId): Operation = sdk.command(3, deviceId.value)
    fun confirmPairing(requestId: Long, codeMatches: Boolean): Operation = sdk.command(4, request = requestId, number = if (codeMatches) 1 else 0)
    fun rejectPairing(requestId: Long): Operation = sdk.command(5, request = requestId)
    fun trustedDevices(): List<TrustedDevice> = sdk.trustedDevices()
    fun forgetDevice(deviceId: DeviceId): Operation = sdk.command(6, deviceId.value)
    fun observe(listener: (ConnectionEvent) -> Unit): AutoCloseable = sdk.observe(ConnectionEvent::class.java, listener)
}
