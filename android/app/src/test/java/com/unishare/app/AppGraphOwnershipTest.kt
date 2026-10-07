package com.unishare.app

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppGraphOwnershipTest {
    private class FakeGateway
    private data class FakeSession(val gateway: FakeGateway)

    @Test
    fun process_graph_owner_constructs_one_graph_for_concurrent_application_consumers() {
        val creations = AtomicInteger()
        val expected = Any()
        val owner = ProcessGraphOwner {
            creations.incrementAndGet()
            expected
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val observed = (1..32).map { executor.submit<Any> { owner.value } }.map { it.get() }

            observed.forEach { assertSame(expected, it) }
            assertEquals(1, creations.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun app_session_composition_constructs_one_session_with_the_exact_single_gateway() {
        val gatewayCreations = AtomicInteger()
        val sessionCreations = AtomicInteger()
        val composition = AppSessionComposition(
            gatewayFactory = {
                gatewayCreations.incrementAndGet()
                FakeGateway()
            },
            sessionFactory = { gateway ->
                sessionCreations.incrementAndGet()
                FakeSession(gateway)
            },
        )
        val executor = Executors.newFixedThreadPool(8)
        try {
            val sessions = (1..32).map {
                executor.submit<FakeSession> { composition.session }
            }.map { it.get() }
            val gateways = (1..32).map {
                executor.submit<FakeGateway> { composition.gateway }
            }.map { it.get() }

            sessions.forEach { assertSame(sessions.first(), it) }
            gateways.forEach { assertSame(gateways.first(), it) }
            assertSame(gateways.first(), sessions.first().gateway)
            assertEquals(1, gatewayCreations.get())
            assertEquals(1, sessionCreations.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
