package com.unishare.app.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePresenceTest {
    @Test fun connected_takes_priority_over_discovery_presence() {
        assertEquals(DevicePresence.CONNECTED, devicePresence(online = true, connected = true))
        assertEquals(DevicePresence.CONNECTED, devicePresence(online = false, connected = true))
        assertEquals(DevicePresence.ONLINE, devicePresence(online = true, connected = false))
        assertEquals(DevicePresence.OFFLINE, devicePresence(online = false, connected = false))
    }
}
