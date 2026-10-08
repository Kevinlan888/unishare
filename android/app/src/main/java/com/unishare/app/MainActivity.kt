package com.unishare.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unishare.app.presentation.home.HomeViewModel
import com.unishare.app.presentation.navigation.UniShareNavHost
import com.unishare.app.presentation.pairing.PairingDialog
import com.unishare.app.presentation.pairing.PairingViewModel
import com.unishare.app.presentation.receive.FinalContentReader
import com.unishare.app.presentation.receive.ReceiveScreen
import com.unishare.app.presentation.receive.ReceiveViewModel
import com.unishare.app.presentation.receive.launchPublishedContent
import com.unishare.app.presentation.session.SessionRoute
import com.unishare.app.presentation.session.SessionViewModel
import com.unishare.app.presentation.theme.UniShareTheme
import com.unishare.app.domain.ActivityKey

class MainActivity : ComponentActivity() {
    private var pendingReceiveActivityId by mutableStateOf<ActivityKey?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingReceiveActivityId = intent.consumeReceiveActivityKey()
        enableEdgeToEdge()
        setContent {
            val graph = UniShareApplication.graph(this)
            val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory(graph))
            val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
            val pairingViewModel: PairingViewModel = viewModel(factory = PairingViewModel.Factory(graph))
            val pairingState by pairingViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(graph) {
                if (pendingReceiveActivityId == null) {
                    pendingReceiveActivityId = graph.transferCoordinator.pendingOffers().firstOrNull()?.first
                }
                graph.transferCoordinator.pendingConsentOffers.collect { activityId ->
                    pendingReceiveActivityId = activityId
                }
            }
            UniShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    UniShareNavHost(
                        homeUiState = homeState,
                        onRefresh = homeViewModel::refresh,
                        sessionContent = { deviceId, onBack ->
                            val sessionViewModel: SessionViewModel = viewModel(
                                key = "session-${deviceId.value}",
                                factory = SessionViewModel.Factory(graph, deviceId),
                            )
                            SessionRoute(sessionViewModel, graph.contentUriPermissionRetainer, onBack)
                        },
                        receiveContent = { activityId, onBack ->
                            val receiveViewModel: ReceiveViewModel = viewModel(
                                key = "receive-${activityId.value}",
                                factory = ReceiveViewModel.Factory(
                                    graph = graph,
                                    activityId = activityId,
                                    finalContentReader = FinalContentReader(::canReadPublishedContent),
                                ),
                            )
                            val receiveState by receiveViewModel.uiState.collectAsStateWithLifecycle()
                            ReceiveScreen(
                                state = receiveState,
                                onBack = onBack,
                                onAccept = receiveViewModel::accept,
                                onReject = receiveViewModel::reject,
                                onChangeDestination = receiveViewModel::restoreDownloadsDestination,
                                onRetryPublication = receiveViewModel::retryPublication,
                                onOpen = {
                                    receiveViewModel.openFinalContent()?.let { uri ->
                                        openPublishedContent(uri, receiveState.mimeType)
                                    }
                                },
                            )
                        },
                        pendingReceiveActivityId = pendingReceiveActivityId,
                        onReceiveActivityConsumed = { pendingReceiveActivityId = null },
                    )
                    PairingDialog(
                        state = pairingState,
                        onMatch = pairingViewModel::confirmMatch,
                        onMismatch = pairingViewModel::rejectMismatch,
                        onDismiss = pairingViewModel::dismiss,
                        onForgetAndRepair = pairingViewModel::forgetAndRepair,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingReceiveActivityId = intent.consumeReceiveActivityKey()
        setIntent(intent)
    }

    private fun canReadPublishedContent(rawUri: String): Boolean {
        if (rawUri.contains(".staging/")) return false
        return try {
            contentResolver.openFileDescriptor(Uri.parse(rawUri), "r")?.use { true } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun openPublishedContent(rawUri: String, mimeType: String) {
        if (!canReadPublishedContent(rawUri)) return
        launchPublishedContent(
            rawUri = rawUri,
            mimeType = mimeType,
            launch = ::startActivity,
            onUnavailable = {
                Toast.makeText(this, R.string.receive_open_failed, Toast.LENGTH_SHORT).show()
            },
        )
    }

    companion object {
        const val EXTRA_RECEIVE_ACTIVITY_ID = "com.unishare.app.extra.RECEIVE_ACTIVITY_ID"
    }
}

internal fun Intent.consumeReceiveActivityKey(): ActivityKey? {
    val value = getStringExtra(MainActivity.EXTRA_RECEIVE_ACTIVITY_ID)
    removeExtra(MainActivity.EXTRA_RECEIVE_ACTIVITY_ID)
    return value?.takeIf(String::isNotBlank)?.let(::ActivityKey)
}
