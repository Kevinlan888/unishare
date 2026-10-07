package com.unishare.app

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppGraphOwnershipTest {
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
}
