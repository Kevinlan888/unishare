package com.unishare.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.unishare.app.UniShareApplication
import com.unishare.app.domain.ActivityKey
import kotlinx.coroutines.launch

class ShareServiceActionHandler(
    private val setBackgroundReceive: suspend (Boolean) -> Unit,
    private val cancelTransfer: suspend (ActivityKey) -> Unit,
) {
    suspend fun handle(intent: Intent?) {
        when (intent?.action) {
            ShareForegroundService.ACTION_STOP_BACKGROUND_RECEIVE -> setBackgroundReceive(false)
            ShareForegroundService.ACTION_CANCEL_TRANSFER -> intent
                .getStringExtra(ShareForegroundService.EXTRA_ACTIVITY_ID)
                ?.takeIf(String::isNotBlank)
                ?.let(::ActivityKey)
                ?.let { cancelTransfer(it) }
        }
    }
}

class ShareForegroundService : Service() {
    private lateinit var notifications: ShareNotificationFactory

    override fun onCreate() {
        super.onCreate()
        notifications = ShareNotificationFactory(this)
        notifications.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON)
            ?.let { value -> ServiceReason.entries.firstOrNull { it.name == value } }
        val notification = if (reason == ServiceReason.ACTIVE_TRANSFER) {
            notifications.transferProgress(
                progress = 0,
                activityId = ActivityKey(intent?.getStringExtra(EXTRA_ACTIVITY_ID) ?: "active-transfer"),
            )
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

        val graph = (application as UniShareApplication).graph
        val handler = ShareServiceActionHandler(
            setBackgroundReceive = { enabled ->
                graph.runtimeController.setBackgroundReceive(enabled)
                Unit
            },
            cancelTransfer = { activityId ->
                graph.transferCoordinator.cancel(activityId)
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.unishare.app.action.START_SHARING"
        const val ACTION_STOP_BACKGROUND_RECEIVE = "com.unishare.app.action.STOP_BACKGROUND_RECEIVE"
        const val ACTION_CANCEL_TRANSFER = "com.unishare.app.action.CANCEL_TRANSFER"
        const val EXTRA_REASON = "com.unishare.app.extra.SERVICE_REASON"
        const val EXTRA_ACTIVITY_ID = "com.unishare.app.extra.ACTIVITY_ID"
        const val RESTART_MODE = START_STICKY
        private const val NOTIFICATION_ID = 1001
    }
}
