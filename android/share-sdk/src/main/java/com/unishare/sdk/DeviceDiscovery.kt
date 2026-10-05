package com.unishare.sdk

class DeviceDiscovery internal constructor(private val sdk: ShareSdk) {
    fun devices(): List<DeviceInfo> = sdk.devices()
    fun observe(listener: (DiscoveryEvent) -> Unit): AutoCloseable = sdk.observe(DiscoveryEvent::class.java, listener)
}
