package com.unishare.app.presentation.receive

import com.unishare.app.domain.ActivityKey

/** Serializes foreground consent navigation while the coordinator remains the state owner. */
internal class ForegroundReceiveQueue(
    initialActivityId: ActivityKey? = null,
) {
    var activeActivityId: ActivityKey? = initialActivityId
        private set

    fun open(activityId: ActivityKey): ActivityKey {
        activeActivityId = activityId
        return activityId
    }

    /** Returns a route only when the previous active consent is no longer unresolved. */
    fun reconcile(pendingActivityIds: List<ActivityKey>): ActivityKey? {
        if (activeActivityId in pendingActivityIds) return null
        return pendingActivityIds.firstOrNull().also { activeActivityId = it }
    }
}
