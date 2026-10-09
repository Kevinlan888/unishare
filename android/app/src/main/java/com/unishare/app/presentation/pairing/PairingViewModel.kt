package com.unishare.app.presentation.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.unishare.app.AppGraph

/** Activity is a replaying consumer; the application owns requests and decisions. */
class PairingViewModel(private val pairing: PairingCoordinator) : ViewModel() {
    val uiState = pairing.uiState
    fun confirmMatch() = pairing.confirmMatch()
    fun rejectMismatch() = pairing.rejectMismatch()
    fun dismiss() = pairing.dismiss()
    fun forgetAndRepair() = pairing.forgetAndRepair()

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PairingViewModel(graph.pairingCoordinator) as T
    }
}
