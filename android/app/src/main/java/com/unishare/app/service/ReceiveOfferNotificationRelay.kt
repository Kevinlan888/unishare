package com.unishare.app.service

import com.unishare.app.domain.ActivityKey
import com.unishare.app.data.transfer.IncomingConsentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import com.unishare.app.data.settings.AppSettings
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.channels.Channel

/** Bridges coordinator consent events to Android notifications without observing the SDK twice. */
internal class ReceiveOfferNotificationRelay(
    consentEvents: Flow<IncomingConsentEvent>,
    isPending: (ActivityKey) -> Boolean,
    notify: (ActivityKey) -> Unit,
    cancel: (ActivityKey) -> Unit,
    scope: CoroutineScope,
    settings: Flow<AppSettings> = flowOf(AppSettings()),
) {
    private enum class PolicyState { UNINITIALIZED, ENABLED, DISABLED }
    private sealed interface Message {
        data class Policy(val settings: AppSettings) : Message
        data class Consent(val event: IncomingConsentEvent) : Message
    }
    init {
        val messages = Channel<Message>(Channel.UNLIMITED)
        // Subscribe immediately; consent events are not replayed. One actor serializes
        // post/cancel operations so disabling cannot be followed by an obsolete post.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settings.collect { messages.send(Message.Policy(it)) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            consentEvents.collect { messages.send(Message.Consent(it)) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var policy = PolicyState.UNINITIALIZED
            var language: com.unishare.app.data.settings.AppLanguage? = null
            val posted = mutableSetOf<ActivityKey>()
            val awaitingPolicy = linkedSetOf<ActivityKey>()
            fun postIfPending(id: ActivityKey) {
                if (!isPending(id) || !posted.add(id)) return
                notify(id)
                // A decision may race between the first check and the synchronous post.
                if (!isPending(id)) {
                    cancel(id)
                    posted -= id
                }
            }
            for (message in messages) when (message) {
                is Message.Policy -> {
                    policy = if (message.settings.notificationsEnabled) PolicyState.ENABLED else PolicyState.DISABLED
                    if (policy == PolicyState.DISABLED) {
                        (posted + awaitingPolicy).forEach(cancel)
                        posted.clear()
                        awaitingPolicy.clear()
                    } else if (language != null && language != message.settings.language) {
                        // Rebuild existing generic offers using the new app language, without
                        // alerting again (the Android builder sets onlyAlertOnce).
                        posted.toList().forEach { id ->
                            if (isPending(id)) notify(id) else { cancel(id); posted -= id }
                        }
                    }
                    language = message.settings.language
                    if (policy == PolicyState.ENABLED) {
                        // Consent can precede the first persisted policy. Preserve arrival order,
                        // deduplicate, and revalidate against the coordinator only when ready.
                        val buffered = awaitingPolicy.toList()
                        awaitingPolicy.clear()
                        buffered.forEach(::postIfPending)
                    }
                }
                is Message.Consent -> when (val event = message.event) {
                    is IncomingConsentEvent.Pending -> when (policy) {
                        PolicyState.UNINITIALIZED -> awaitingPolicy += event.activityId
                        PolicyState.ENABLED -> postIfPending(event.activityId)
                        PolicyState.DISABLED -> Unit
                    }
                    is IncomingConsentEvent.Dismissed -> {
                        cancel(event.activityId)
                        posted -= event.activityId
                        awaitingPolicy -= event.activityId
                    }
                }
            }
        }
    }
}
