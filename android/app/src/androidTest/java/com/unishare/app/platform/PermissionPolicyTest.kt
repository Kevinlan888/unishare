package com.unishare.app.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun api32DoesNotNeedNotificationRuntimeConsent() {
        assertFalse(PermissionPolicy.requirementsFor(api = 32, target = 36).postNotifications)
    }

    @Test
    fun api33Target36NeedsNotificationButNotLocalNetworkRuntimeConsent() {
        val requirements = PermissionPolicy.requirementsFor(api = 33, target = 36)
        assertTrue(requirements.postNotifications)
        assertFalse(requirements.localNetwork)
    }

    @Test
    fun api37CompatibilityPathStillDoesNotRequestLocalNetworkForTarget36() {
        val requirements = PermissionPolicy.requirementsFor(api = 37, target = 36)
        assertTrue(requirements.postNotifications)
        assertFalse(requirements.localNetwork)
    }
}
