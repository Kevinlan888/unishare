package com.unishare.app.platform

import org.junit.Assert.*
import org.junit.Test

class NotificationPermissionTest {
    @Test fun system_disabled_notifications_block_every_supported_api() {
        for (api in listOf(26, 28, 32, 33, 36, 37)) {
            assertFalse("API $api", PermissionPolicy.notificationsAllowed(api, true, false))
            assertEquals(api < 33, PermissionPolicy.notificationsAllowed(api, false, true))
            assertTrue(PermissionPolicy.notificationsAllowed(api, true, true))
        }
    }
}
