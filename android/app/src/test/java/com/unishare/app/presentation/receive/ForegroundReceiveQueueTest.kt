package com.unishare.app.presentation.receive

import com.unishare.app.domain.ActivityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundReceiveQueueTest {
    @Test
    fun reconcile_drains_all_pending_offers_without_reopening_the_active_offer() {
        val first = ActivityKey("first")
        val second = ActivityKey("second")
        val third = ActivityKey("third")
        val queue = ForegroundReceiveQueue()

        assertEquals(first, queue.reconcile(listOf(first, second)))
        assertNull(queue.reconcile(listOf(first, second)))
        assertNull("a later offer must not replace the active consent", queue.reconcile(listOf(first, second, third)))

        assertEquals(second, queue.reconcile(listOf(second, third)))
        assertNull(queue.reconcile(listOf(second, third)))
        assertEquals(third, queue.reconcile(listOf(third)))
        assertNull(queue.reconcile(emptyList()))
    }

    @Test
    fun explicit_notification_open_becomes_active_and_reconciliation_resumes_after_it_is_decided() {
        val first = ActivityKey("first")
        val second = ActivityKey("second")
        val queue = ForegroundReceiveQueue()

        assertEquals(second, queue.open(second))
        assertNull(queue.reconcile(listOf(first, second)))
        assertEquals(first, queue.reconcile(listOf(first)))
    }
}
