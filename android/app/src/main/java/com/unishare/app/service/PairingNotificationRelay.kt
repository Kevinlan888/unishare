package com.unishare.app.service

import com.unishare.app.data.settings.AppSettings
import com.unishare.app.presentation.pairing.PairingUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Latest replayed pairing and policy are reconciled by one application collector. */
class PairingNotificationRelay(
    pairing: Flow<PairingUiState>,
    runtime: Flow<RuntimeState>,
    settings: Flow<AppSettings>,
    scope: CoroutineScope,
    notify: () -> Unit,
    cancel: () -> Unit,
) {
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(pairing, runtime, settings) { prompt, state, policy ->
                Triple(prompt, state, policy)
            }.collect { (prompt, state, policy) ->
                if (prompt.prompt != null && !prompt.submitting && !state.appVisible &&
                    state.backgroundReceiveEnabled && policy.notificationsEnabled) notify()
                else cancel()
            }
        }
    }
}
