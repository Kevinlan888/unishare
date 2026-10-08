package com.unishare.app.service

import com.unishare.app.domain.ActivityKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveOfferNotificationRelayTest {
    @Test
    fun only_still_pending_consent_events_reach_the_notification_sink() = runTest {
        val events = MutableSharedFlow<ActivityKey>(extraBufferCapacity = 2)
        val dismissals = MutableSharedFlow<ActivityKey>(extraBufferCapacity = 1)
        val stale = ActivityKey("stale")
        val pending = ActivityKey("pending")
        val notifications = mutableListOf<ActivityKey>()
        val cancellations = mutableListOf<ActivityKey>()

        ReceiveOfferNotificationRelay(
            pendingActivityIds = events,
            dismissedActivityIds = dismissals,
            isPending = { it == pending },
            notify = notifications::add,
            cancel = cancellations::add,
            scope = backgroundScope,
        )

        events.emit(stale)
        events.emit(pending)
        dismissals.emit(pending)
        runCurrent()

        assertEquals(listOf(pending), notifications)
        assertEquals(listOf(pending), cancellations)
    }
}
