package com.unishare.app.service

import com.unishare.app.domain.ActivityKey
import com.unishare.app.data.transfer.IncomingConsentEvent
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
        val consentEvents = MutableSharedFlow<IncomingConsentEvent>(extraBufferCapacity = 3)
        val stale = ActivityKey("stale")
        val pending = ActivityKey("pending")
        val notifications = mutableListOf<ActivityKey>()
        val cancellations = mutableListOf<ActivityKey>()

        ReceiveOfferNotificationRelay(
            consentEvents = consentEvents,
            isPending = { it == pending },
            notify = notifications::add,
            cancel = cancellations::add,
            scope = backgroundScope,
        )

        consentEvents.emit(IncomingConsentEvent.Pending(stale))
        consentEvents.emit(IncomingConsentEvent.Pending(pending))
        consentEvents.emit(IncomingConsentEvent.Dismissed(pending))
        runCurrent()

        assertEquals(listOf(pending), notifications)
        assertEquals(listOf(pending), cancellations)
    }

    @Test
    fun decision_during_post_is_cancelled_after_the_post() = runTest {
        val consentEvents = MutableSharedFlow<IncomingConsentEvent>(extraBufferCapacity = 1)
        val activityId = ActivityKey("decision-during-post")
        var pending = true
        val notifications = mutableListOf<ActivityKey>()
        val cancellations = mutableListOf<ActivityKey>()

        ReceiveOfferNotificationRelay(
            consentEvents = consentEvents,
            isPending = { pending },
            notify = {
                notifications += it
                pending = false
            },
            cancel = cancellations::add,
            scope = backgroundScope,
        )

        consentEvents.emit(IncomingConsentEvent.Pending(activityId))
        runCurrent()

        assertEquals(listOf(activityId), notifications)
        assertEquals(listOf(activityId), cancellations)
    }

    @Test
    fun dismissal_followed_by_reprompt_leaves_the_live_notification_visible() = runTest {
        val consentEvents = MutableSharedFlow<IncomingConsentEvent>(extraBufferCapacity = 2)
        val activityId = ActivityKey("failed-reject")
        var notificationVisible = false

        ReceiveOfferNotificationRelay(
            consentEvents = consentEvents,
            isPending = { true },
            notify = { notificationVisible = true },
            cancel = { notificationVisible = false },
            scope = backgroundScope,
        )

        consentEvents.emit(IncomingConsentEvent.Dismissed(activityId))
        consentEvents.emit(IncomingConsentEvent.Pending(activityId))
        runCurrent()

        assertEquals(true, notificationVisible)
    }
}
