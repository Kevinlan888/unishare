package com.unishare.app.service

import com.unishare.app.data.sdk.GatewayConfig
import com.unishare.app.data.sdk.ShareSessionManager
import com.unishare.app.data.settings.AppSettings
import com.unishare.app.data.settings.SettingsRepository
import com.unishare.app.platform.NotificationPermissionChecker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RuntimePhase { STOPPED, FOREGROUND, BACKGROUND_RECEIVE, ACTIVE_TRANSFER }
enum class RuntimeFailure {
    NOTIFICATION_PERMISSION_DENIED,
    SERVICE_START_NOT_ALLOWED,
    SERVICE_START_REJECTED,
    SESSION_START_FAILED,
    SESSION_STOP_FAILED,
}

data class RuntimeState(
    val phase: RuntimePhase = RuntimePhase.STOPPED,
    val appVisible: Boolean = false,
    val backgroundReceiveEnabled: Boolean = false,
    val activeTransferCount: Int = 0,
    val foregroundServiceRunning: Boolean = false,
    val stoppingAfterTransfers: Boolean = false,
    val failure: RuntimeFailure? = null,
)

interface RuntimeSession {
    /** ShareSessionManager sets SDK foreground before its start command. */
    suspend fun start(deviceName: String)
    /** ShareSessionManager drains stop before withdrawing SDK foreground. */
    suspend fun stop()
}

class ShareSessionRuntime(private val session: ShareSessionManager) : RuntimeSession {
    override suspend fun start(deviceName: String) {
        session.initialize(GatewayConfig(deviceName))
        session.start()
    }

    override suspend fun stop() = session.stop()
}

class RuntimeController(
    private val settingsRepository: SettingsRepository,
    private val session: RuntimeSession,
    private val foregroundService: ForegroundServiceGateway,
    activeTransferCount: StateFlow<Int>,
    private val notificationPermission: NotificationPermissionChecker,
    scope: CoroutineScope,
) {
    private enum class StartAuthority { NONE, VISIBLE_ACTIVITY, SYSTEM_RESTART }

    private val lifecycle = Mutex()
    private val settingsReady = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    private var settings = AppSettings()
    private var appVisible = false
    private var activeTransfers = 0
    private var sessionRunning = false
    private var serviceRunning = false
    private var failure: RuntimeFailure? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settingsRepository.settings.collect { updated ->
                lifecycle.withLock {
                    settings = updated
                    settingsReady.complete(Unit)
                    reconcileLocked(StartAuthority.NONE)
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            activeTransferCount.collect { count ->
                lifecycle.withLock {
                    activeTransfers = count.coerceAtLeast(0)
                    reconcileLocked(if (appVisible) StartAuthority.VISIBLE_ACTIVITY else StartAuthority.NONE)
                }
            }
        }
    }

    suspend fun setAppVisible(visible: Boolean) {
        settingsReady.await()
        lifecycle.withLock {
            appVisible = visible
            reconcileLocked(if (visible) StartAuthority.VISIBLE_ACTIVITY else StartAuthority.NONE)
        }
    }

    /** Returns false when permission or platform service startup prevents enabling the setting. */
    suspend fun setBackgroundReceive(enabled: Boolean): Boolean {
        settingsReady.await()
        return lifecycle.withLock {
            if (!enabled) {
                settingsRepository.setBackgroundReceiveEnabled(false)
                settings = settings.copy(backgroundReceiveEnabled = false)
                reconcileLocked(StartAuthority.NONE)
                return@withLock true
            }
            failure = null
            if (!appVisible) {
                settingsRepository.setBackgroundReceiveEnabled(false)
                settings = settings.copy(backgroundReceiveEnabled = false)
                failure = RuntimeFailure.SERVICE_START_NOT_ALLOWED
                publishLocked()
                return@withLock false
            }
            if (!settings.notificationsEnabled || !notificationPermission.isGranted()) {
                settingsRepository.setBackgroundReceiveEnabled(false)
                settings = settings.copy(backgroundReceiveEnabled = false)
                failure = RuntimeFailure.NOTIFICATION_PERMISSION_DENIED
                publishLocked()
                return@withLock false
            }
            settingsRepository.setBackgroundReceiveEnabled(true)
            settings = settings.copy(backgroundReceiveEnabled = true)
            reconcileLocked(StartAuthority.VISIBLE_ACTIVITY)
            settings.backgroundReceiveEnabled && serviceRunning
        }
    }

    /** Called only by a service instance recreated by Android, never by arbitrary background work. */
    suspend fun resumeFromSystemRestart() {
        val restored = settingsRepository.settings.first()
        lifecycle.withLock {
            settings = restored
            serviceRunning = true
            reconcileLocked(StartAuthority.SYSTEM_RESTART)
        }
    }

    private suspend fun reconcileLocked(authority: StartAuthority) {
        var wantsService = settings.backgroundReceiveEnabled || activeTransfers > 0
        var wantsSession = appVisible || wantsService
        if (wantsService && !serviceRunning && authority == StartAuthority.NONE) {
            failure = RuntimeFailure.SERVICE_START_NOT_ALLOWED
            if (!appVisible) {
                publishLocked()
                return
            }
        }

        if (wantsSession && !sessionRunning) {
            try {
                session.start(settings.deviceName)
                sessionRunning = true
                if (failure == RuntimeFailure.SESSION_START_FAILED) failure = null
            } catch (_: Exception) {
                failure = RuntimeFailure.SESSION_START_FAILED
                publishLocked()
                return
            }
        }

        if (wantsService && !serviceRunning && authority != StartAuthority.NONE) {
            val reason = if (settings.backgroundReceiveEnabled) {
                ServiceReason.BACKGROUND_RECEIVE
            } else {
                ServiceReason.ACTIVE_TRANSFER
            }
            try {
                foregroundService.start(reason)
                serviceRunning = true
                if (failure == RuntimeFailure.SERVICE_START_REJECTED ||
                    failure == RuntimeFailure.SERVICE_START_NOT_ALLOWED
                ) failure = null
            } catch (_: Exception) {
                failure = RuntimeFailure.SERVICE_START_REJECTED
                if (settings.backgroundReceiveEnabled) {
                    settingsRepository.setBackgroundReceiveEnabled(false)
                    settings = settings.copy(backgroundReceiveEnabled = false)
                    wantsService = activeTransfers > 0
                    wantsSession = appVisible || wantsService
                }
            }
        }

        if (!wantsSession && sessionRunning) {
            try {
                session.stop()
                sessionRunning = false
            } catch (_: Exception) {
                failure = RuntimeFailure.SESSION_STOP_FAILED
                publishLocked()
                return
            }
        }
        if (!wantsService && serviceRunning) {
            foregroundService.stopWhenIdle()
            serviceRunning = false
        }
        publishLocked()
    }

    private fun publishLocked() {
        val phase = when {
            settings.backgroundReceiveEnabled && serviceRunning -> RuntimePhase.BACKGROUND_RECEIVE
            activeTransfers > 0 -> RuntimePhase.ACTIVE_TRANSFER
            sessionRunning && appVisible -> RuntimePhase.FOREGROUND
            else -> RuntimePhase.STOPPED
        }
        mutableState.value = RuntimeState(
            phase = phase,
            appVisible = appVisible,
            backgroundReceiveEnabled = settings.backgroundReceiveEnabled,
            activeTransferCount = activeTransfers,
            foregroundServiceRunning = serviceRunning,
            stoppingAfterTransfers = !settings.backgroundReceiveEnabled && activeTransfers > 0 && serviceRunning,
            failure = failure,
        )
    }
}
