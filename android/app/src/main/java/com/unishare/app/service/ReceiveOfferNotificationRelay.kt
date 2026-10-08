package com.unishare.app.service

import com.unishare.app.domain.ActivityKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Bridges coordinator consent events to Android notifications without observing the SDK twice. */
internal class ReceiveOfferNotificationRelay(
    pendingActivityIds: Flow<ActivityKey>,
    dismissedActivityIds: Flow<ActivityKey>,
    isPending: (ActivityKey) -> Boolean,
    notify: (ActivityKey) -> Unit,
    cancel: (ActivityKey) -> Unit,
    scope: CoroutineScope,
) {
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pendingActivityIds.collect { activityId ->
                if (isPending(activityId)) {
                    notify(activityId)
                    // A decision may race between the first check and the synchronous post.
                    if (!isPending(activityId)) cancel(activityId)
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            dismissedActivityIds.collect(cancel)
        }
    }
}
