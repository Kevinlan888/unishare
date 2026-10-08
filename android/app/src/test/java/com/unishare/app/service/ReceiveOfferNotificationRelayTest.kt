package com.unishare.app.service

import com.unishare.app.domain.ActivityKey
import com.unishare.app.data.transfer.IncomingConsentEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.unishare.app.presentation.ManagementSettings
import com.unishare.app.presentation.settings.SettingsViewModel
import com.unishare.app.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveOfferNotificationRelayTest {
    @Test fun disabling_notifications_via_settings_cancels_offers_before_runtime_shutdown_and_suppresses_new_offers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val settings = ManagementSettings()
            val events = MutableSharedFlow<IncomingConsentEvent>(extraBufferCapacity = 4)
            val visible = mutableSetOf<ActivityKey>()
            val shutdown = CompletableDeferred<Boolean>()
            val vm = SettingsViewModel(settings, { shutdown.await() })
            ReceiveOfferNotificationRelay(events, { true }, { visible += it }, { visible -= it }, backgroundScope, settings.settings)
            runCurrent()
            events.emit(IncomingConsentEvent.Pending(ActivityKey("old"))); runCurrent()
            assertEquals(setOf(ActivityKey("old")), visible)
            vm.setNotifications(false); runCurrent()
            assertEquals(false, settings.settings.value.notificationsEnabled)
            assertEquals(emptySet<ActivityKey>(), visible)
            events.emit(IncomingConsentEvent.Pending(ActivityKey("new"))); runCurrent()
            assertEquals(emptySet<ActivityKey>(), visible)
            shutdown.complete(true); runCurrent()
        } finally { Dispatchers.resetMain() }
    }
    @Test fun notification_disable_racing_with_a_synchronous_post_cannot_resurrect_an_offer() = runTest {
        val settings = ManagementSettings()
        val events = MutableSharedFlow<IncomingConsentEvent>(extraBufferCapacity = 2)
        val visible = mutableSetOf<ActivityKey>()
        ReceiveOfferNotificationRelay(events, { true }, {
            visible += it
            settings.settings.value = settings.settings.value.copy(notificationsEnabled = false)
        }, { visible -= it }, backgroundScope, settings.settings)
        runCurrent()
        events.emit(IncomingConsentEvent.Pending(ActivityKey("race"))); runCurrent()
        assertEquals(emptySet<ActivityKey>(), visible)
    }
    @Test fun disabled_initial_policy_does_not_post_and_reenable_only_allows_new_pending_events() = runTest {
        val settings = ManagementSettings(AppSettings(notificationsEnabled = false))
        val events = MutableSharedFlow<IncomingConsentEvent>(extraBufferCapacity = 2)
        val posted = mutableListOf<ActivityKey>()
        ReceiveOfferNotificationRelay(events, { true }, posted::add, {}, backgroundScope, settings.settings)
        runCurrent()
        events.emit(IncomingConsentEvent.Pending(ActivityKey("disabled"))); runCurrent()
        assertEquals(emptyList<ActivityKey>(), posted)
        settings.setNotificationsEnabled(true); runCurrent()
        assertEquals(emptyList<ActivityKey>(), posted)
        events.emit(IncomingConsentEvent.Pending(ActivityKey("enabled"))); runCurrent()
        assertEquals(listOf(ActivityKey("enabled")), posted)
    }
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
