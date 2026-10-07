package com.unishare.app.presentation.navigation

import com.unishare.app.domain.DeviceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDestinationTest {
    @Test
    fun session_route_round_trip_preserves_opaque_device_id() {
        val destination = AppDestination.Session(DeviceKey("peer/北 1"))

        assertEquals(destination, AppDestination.fromRoute(destination.route))
    }

    @Test
    fun receive_route_round_trip_preserves_request_id() {
        val destination = AppDestination.Receive(9_223_372_036_854_775_000L)

        assertEquals(destination, AppDestination.fromRoute(destination.route))
    }

    @Test
    fun malformed_dynamic_routes_are_rejected() {
        assertNull(AppDestination.fromRoute("session/not-base64***"))
        assertNull(AppDestination.fromRoute("receive/not-a-number"))
    }

    @Test
    fun all_top_level_destinations_are_distinct_and_recognized() {
        val destinations = listOf(
            AppDestination.Home,
            AppDestination.Devices,
            AppDestination.History,
            AppDestination.Settings,
        )

        assertEquals(4, destinations.map { it.route }.distinct().size)
        destinations.forEach { destination ->
            assertEquals(destination, AppDestination.fromRoute(destination.route))
            assertEquals(true, destination.isTopLevel)
        }
        assertEquals(false, AppDestination.Session(DeviceKey("peer")).isTopLevel)
        assertEquals(false, AppDestination.Receive(1).isTopLevel)
    }
}
