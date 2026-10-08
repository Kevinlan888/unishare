package com.unishare.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.Manifest
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.core.view.WindowCompat
import com.unishare.app.platform.AndroidNotificationPermissionChecker
import com.unishare.app.data.settings.AppLanguage
import com.unishare.app.data.settings.AppTheme
import com.unishare.app.presentation.devices.DevicesViewModel
import com.unishare.app.presentation.devices.DevicesScreen
import com.unishare.app.presentation.history.HistoryViewModel
import com.unishare.app.presentation.history.HistoryScreen
import com.unishare.app.presentation.settings.SettingsViewModel
import com.unishare.app.presentation.settings.SettingsScreen
import com.unishare.app.presentation.home.HomeViewModel
import com.unishare.app.presentation.navigation.UniShareNavHost
import com.unishare.app.presentation.pairing.PairingDialog
import com.unishare.app.presentation.pairing.PairingViewModel
import com.unishare.app.presentation.receive.FinalContentReader
import com.unishare.app.presentation.receive.ForegroundReceiveQueue
import com.unishare.app.presentation.receive.ReceiveScreen
import com.unishare.app.presentation.receive.ReceiveViewModel
import com.unishare.app.presentation.receive.launchPublishedContent
import com.unishare.app.presentation.session.SessionRoute
import com.unishare.app.presentation.session.SessionViewModel
import com.unishare.app.presentation.theme.UniShareTheme
import com.unishare.app.domain.ActivityKey

class MainActivity : ComponentActivity() {
    private var pendingReceiveActivityId by mutableStateOf<ActivityKey?>(null)
    private lateinit var foregroundReceiveQueue: ForegroundReceiveQueue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        foregroundReceiveQueue = ForegroundReceiveQueue(
            savedInstanceState?.getString(STATE_ACTIVE_RECEIVE_ACTIVITY_ID)
                ?.takeIf(String::isNotBlank)
                ?.let(::ActivityKey),
        )
        pendingReceiveActivityId = intent.consumeReceiveActivityKey()?.let(foregroundReceiveQueue::open)
        enableEdgeToEdge()
        setContent {
            val graph = UniShareApplication.graph(this)
            val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory(graph))
            val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
            val pairingViewModel: PairingViewModel = viewModel(factory = PairingViewModel.Factory(graph))
            val pairingState by pairingViewModel.uiState.collectAsStateWithLifecycle()
            val devicesViewModel: DevicesViewModel = viewModel(factory = viewModelFactory { initializer { DevicesViewModel(graph.deviceRepository) } })
            val devicesState by devicesViewModel.uiState.collectAsStateWithLifecycle()
            val historyViewModel: HistoryViewModel = viewModel(factory = viewModelFactory { initializer { HistoryViewModel(graph.activityRepository, graph.deviceRepository, FinalContentReader(::canReadPublishedContent)) } })
            val historyState by historyViewModel.uiState.collectAsStateWithLifecycle()
            val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory { initializer {
                SettingsViewModel(graph.settingsRepository, graph.runtimeController::setBackgroundReceive) { uri ->
                    contentResolver.persistedUriPermissions.filter { it.uri.toString() == uri }.forEach { grant ->
                        var flags = 0
                        if (grant.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        if (grant.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        contentResolver.releasePersistableUriPermission(grant.uri, flags)
                    }
                }
            } })
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                settingsViewModel.onNotificationPermissionResult(granted, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            }
            LaunchedEffect(settingsState.requestNotificationPermission) {
                if (settingsState.requestNotificationPermission) {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else settingsViewModel.onNotificationPermissionResult(false, false)
                }
            }
            LaunchedEffect(graph) {
                graph.transferCoordinator.pendingConsentActivityIds.collect { pendingActivityIds ->
                    foregroundReceiveQueue.reconcile(pendingActivityIds)?.let { activityId ->
                        pendingReceiveActivityId = activityId
                    }
                }
            }
            val systemConfig = LocalConfiguration.current
            val localizedConfig = remember(systemConfig, settingsState.settings.language) { Configuration(systemConfig).apply {
                when (settingsState.settings.language) {
                    AppLanguage.ENGLISH -> setLocales(LocaleList.forLanguageTags("en"))
                    AppLanguage.SIMPLIFIED_CHINESE -> setLocales(LocaleList.forLanguageTags("zh-CN"))
                    AppLanguage.SYSTEM -> Unit
                }
            } }
            val localizedContext = remember(localizedConfig) { createConfigurationContext(localizedConfig) }
            val dark = when (settingsState.settings.theme) { AppTheme.SYSTEM -> isSystemInDarkTheme(); AppTheme.DARK -> true; AppTheme.LIGHT -> false }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides localizedConfig) {
            UniShareTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    UniShareNavHost(
                        homeUiState = homeState,
                        onRefresh = homeViewModel::refresh,
                        devicesContent = { open -> DevicesScreen(devicesState, devicesViewModel::connect, devicesViewModel::requestForget, devicesViewModel::confirmForget, devicesViewModel::dismissForget, open) },
                        historyContent = { HistoryScreen(historyState, historyViewModel::setQuery) { id ->
                            historyViewModel.openFinalContent(id)?.let { uri ->
                                val mime = historyState.rows.find { it.activity.id == id }?.activity?.mimeType ?: "application/octet-stream"
                                openPublishedContent(uri, mime)
                            }
                        } },
                        settingsContent = { SettingsScreen(settingsState, settingsViewModel::saveDeviceName, { enabled ->
                            settingsViewModel.requestBackgroundReceive(enabled, AndroidNotificationPermissionChecker(this).isGranted(), lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        }, settingsViewModel::setNotifications, settingsViewModel::setLanguage, settingsViewModel::setTheme,
                            onNotificationSettings = {
                                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                            }, onDownloads = settingsViewModel::restoreDownloads) },
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingReceiveActivityId = intent.consumeReceiveActivityKey()?.let(foregroundReceiveQueue::open)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        foregroundReceiveQueue.activeActivityId?.let { activityId ->
            outState.putString(STATE_ACTIVE_RECEIVE_ACTIVITY_ID, activityId.value)
        }
        super.onSaveInstanceState(outState)
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
        private const val STATE_ACTIVE_RECEIVE_ACTIVITY_ID = "active_receive_activity_id"
    }
}

internal fun Intent.consumeReceiveActivityKey(): ActivityKey? {
    val value = getStringExtra(MainActivity.EXTRA_RECEIVE_ACTIVITY_ID)
    removeExtra(MainActivity.EXTRA_RECEIVE_ACTIVITY_ID)
    return value?.takeIf(String::isNotBlank)?.let(::ActivityKey)
}
