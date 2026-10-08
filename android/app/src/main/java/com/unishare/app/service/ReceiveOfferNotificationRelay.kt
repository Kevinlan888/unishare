package com.unishare.app.service

import com.unishare.app.domain.ActivityKey
import com.unishare.app.data.transfer.IncomingConsentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Bridges coordinator consent events to Android notifications without observing the SDK twice. */
internal class ReceiveOfferNotificationRelay(
    consentEvents: Flow<IncomingConsentEvent>,
    isPending: (ActivityKey) -> Boolean,
    notify: (ActivityKey) -> Unit,
    cancel: (ActivityKey) -> Unit,
    scope: CoroutineScope,
) {
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            consentEvents.collect { event ->
                when (event) {
                    is IncomingConsentEvent.Pending -> if (isPending(event.activityId)) {
                        notify(event.activityId)
                        // A decision may race between the first check and the synchronous post.
                        if (!isPending(event.activityId)) cancel(event.activityId)
                    }
                    is IncomingConsentEvent.Dismissed -> cancel(event.activityId)
                }
            }
        }
    }
}
