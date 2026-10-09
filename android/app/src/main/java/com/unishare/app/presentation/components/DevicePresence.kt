package com.unishare.app.presentation.components

enum class DevicePresence { CONNECTED, ONLINE, OFFLINE }

fun devicePresence(online: Boolean, connected: Boolean): DevicePresence = when {
    connected -> DevicePresence.CONNECTED
    online -> DevicePresence.ONLINE
    else -> DevicePresence.OFFLINE
}
