package com.unishare.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.unishare.app.UniShareApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

class ShareServiceActionHandler(
    private val setBackgroundReceive: suspend (Boolean) -> Unit,
    private val cancelCurrentTransfer: suspend () -> Unit,
) {
    suspend fun handle(intent: Intent?) {
        when (intent?.action) {
            ShareForegroundService.ACTION_STOP_BACKGROUND_RECEIVE -> setBackgroundReceive(false)
            ShareForegroundService.ACTION_CANCEL_TRANSFER -> cancelCurrentTransfer()
        }
    }
}

class ShareForegroundService : Service() {
    private lateinit var notifications: ShareNotificationFactory
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentReason: ServiceReason? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        notifications = ShareNotificationFactory(this)
        notifications.createChannels()
        serviceScope.launch {
            (application as UniShareApplication).graph.localeResources.language.collect {
                notifications.createChannels()
                if (foregroundStarted) showForegroundNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentReason = intent?.getStringExtra(EXTRA_REASON)
            ?.let { value -> ServiceReason.entries.firstOrNull { it.name == value } }
        foregroundStarted = true
        showForegroundNotification()

        val graph = (application as UniShareApplication).graph
        val handler = ShareServiceActionHandler(
            setBackgroundReceive = { enabled ->
                graph.runtimeController.setBackgroundReceive(enabled)
                Unit
            },
            cancelCurrentTransfer = {
                graph.transferCoordinator.cancelOldestActive()
                Unit
            },
        )
        graph.scope.launch {
            if (intent == null || intent.action == ACTION_STOP_BACKGROUND_RECEIVE ||
                intent.action == ACTION_CANCEL_TRANSFER
            ) {
                graph.runtimeController.resumeFromSystemRestart()
            }
            handler.handle(intent)
        }
        return RESTART_MODE
    }

    private fun showForegroundNotification() {
        val notification = if (currentReason == ServiceReason.ACTIVE_TRANSFER) {
            notifications.transferProgress(progress = 0)
        } else {
            notifications.ongoingReceive(onlineDeviceCount = 0)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.unishare.app.action.START_SHARING"
        const val ACTION_STOP_BACKGROUND_RECEIVE = "com.unishare.app.action.STOP_BACKGROUND_RECEIVE"
        const val ACTION_CANCEL_TRANSFER = "com.unishare.app.action.CANCEL_TRANSFER"
        const val EXTRA_REASON = "com.unishare.app.extra.SERVICE_REASON"
        const val RESTART_MODE = START_STICKY
        private const val NOTIFICATION_ID = 1001
    }
}
