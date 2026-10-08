package com.unishare.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
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
import com.unishare.app.presentation.session.SessionRoute
import com.unishare.app.presentation.session.SessionViewModel
import com.unishare.app.presentation.theme.UniShareTheme
import com.unishare.app.domain.ActivityKey
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingReceiveActivityId by mutableStateOf<ActivityKey?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingReceiveActivityId = receiveActivityFrom(intent)
        enableEdgeToEdge()
        setContent {
            val graph = UniShareApplication.graph(this)
            val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory(graph))
            val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
            val pairingViewModel: PairingViewModel = viewModel(factory = PairingViewModel.Factory(graph))
            val pairingState by pairingViewModel.uiState.collectAsStateWithLifecycle()
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
                            val directoryLauncher = rememberLauncherForActivityResult(
                                ActivityResultContracts.OpenDocumentTree(),
                            ) { treeUri ->
                                treeUri?.let { persistReceiveDirectory(graph, it) }
                            }
                            ReceiveScreen(
                                state = receiveState,
                                onBack = onBack,
                                onAccept = receiveViewModel::accept,
                                onReject = receiveViewModel::reject,
                                onChangeDestination = { directoryLauncher.launch(null) },
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
        setIntent(intent)
        pendingReceiveActivityId = receiveActivityFrom(intent)
    }

    private fun persistReceiveDirectory(graph: AppGraph, treeUri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            return
        }
        val displayName = try {
            DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':').ifBlank { null }
        } catch (_: Exception) {
            null
        }
        graph.scope.launch {
            graph.settingsRepository.setReceiveDirectory(treeUri.toString(), displayName)
        }
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
        val uri = Uri.parse(rawUri)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }

    private fun receiveActivityFrom(intent: Intent?): ActivityKey? = intent
        ?.getStringExtra(EXTRA_RECEIVE_ACTIVITY_ID)
        ?.takeIf(String::isNotBlank)
        ?.let(::ActivityKey)

    companion object {
        const val EXTRA_RECEIVE_ACTIVITY_ID = "com.unishare.app.extra.RECEIVE_ACTIVITY_ID"
    }
}
