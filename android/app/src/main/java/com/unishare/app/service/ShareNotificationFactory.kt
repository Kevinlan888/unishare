package com.unishare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.unishare.app.MainActivity
import com.unishare.app.R
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.DeviceKey
import com.unishare.app.UniShareApplication
import com.unishare.app.platform.AppLocaleResources

/** Notification copy is intentionally generic: payload, peer and path data never enter it. */
class ShareNotificationFactory(context: Context, private val locale: AppLocaleResources = UniShareApplication.graph(context).localeResources) {
    private val application = context.applicationContext
    private val manager = application.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannels(listOf(
            NotificationChannel(
                RECEIVE_CHANNEL_ID,
                if (locale.isReady) locale.string(R.string.notification_channel_receive) else locale.string(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = if (locale.isReady) locale.string(R.string.notification_channel_receive_description) else null
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
            NotificationChannel(
                TRANSFER_CHANNEL_ID,
                if (locale.isReady) locale.string(R.string.notification_channel_transfer) else locale.string(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = if (locale.isReady) locale.string(R.string.notification_channel_transfer_description) else null
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        ))
    }

    fun ongoingReceive(onlineDeviceCount: Int): Notification =
        if (!locale.isReady) initialForeground(RECEIVE_CHANNEL_ID) else
        baseBuilder(RECEIVE_CHANNEL_ID)
            .setContentTitle(locale.string(R.string.notification_receive_title))
            .setContentText(locale.resources.getQuantityString(
                R.plurals.notification_receive_content,
                onlineDeviceCount.coerceAtLeast(0),
                onlineDeviceCount.coerceAtLeast(0),
            ))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(application, R.drawable.ic_launcher_foreground),
                locale.string(R.string.notification_stop_background),
                serviceAction(ShareForegroundService.ACTION_STOP_BACKGROUND_RECEIVE, STOP_REQUEST_CODE),
            ).build())
            .build()

    fun transferProgress(progress: Int, activityId: ActivityKey? = null, deviceId: DeviceKey? = null, canCancel: Boolean = true): Notification =
        if (!locale.isReady) initialForeground(TRANSFER_CHANNEL_ID) else
        baseBuilder(TRANSFER_CHANNEL_ID)
            .setContentTitle(locale.string(R.string.notification_transfer_title))
            .setContentText(locale.string(R.string.notification_transfer_content))
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .apply {
                if (deviceId != null) setContentIntent(PendingIntent.getActivity(application, CONTENT_REQUEST_CODE,
                    Intent(application, MainActivity::class.java)
                        .setData(android.net.Uri.Builder().scheme("unishare").authority("session").appendPath(deviceId.value).build())
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_SESSION_DEVICE_ID, deviceId.value), immutableUpdateCurrent))
                if (canCancel) addAction(Notification.Action.Builder(
                Icon.createWithResource(application, R.drawable.ic_launcher_foreground),
                locale.string(R.string.notification_cancel_transfer),
                PendingIntent.getForegroundService(
                    application,
                    CANCEL_REQUEST_CODE,
                    Intent(application, ShareForegroundService::class.java)
                        .setAction(ShareForegroundService.ACTION_CANCEL_TRANSFER)
                        .setData(activityId?.let { android.net.Uri.Builder().scheme("unishare").authority("cancel").appendPath(it.value).build() })
                        .putExtra(ShareForegroundService.EXTRA_ACTIVITY_ID, activityId?.value),
                    immutableUpdateCurrent,
                ),
            ).build()) }
            .build()

    /** A consent-only notification; accepting or rejecting always happens in the app. */
    fun incomingOffer(activityId: ActivityKey): Notification =
        baseBuilder(TRANSFER_CHANNEL_ID)
            .setContentTitle(locale.string(R.string.notification_receive_request_title))
            .setContentText(locale.string(R.string.notification_receive_request_content))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(
                application,
                activityId.value.hashCode(),
                incomingOfferIntent(activityId),
                immutableUpdateCurrent,
            ))
            .build()

    /** The destination resolves [activityId] against current pending offers before rendering. */
    fun incomingOfferIntent(activityId: ActivityKey): Intent =
        Intent(application, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_RECEIVE_ACTIVITY_ID, activityId.value)

    fun postIncomingOffer(activityId: ActivityKey) {
        try {
            manager.notify(incomingNotificationId(activityId), incomingOffer(activityId))
        } catch (_: SecurityException) {
            // Foreground UI observes the same coordinator event when notification permission is denied.
        }
    }

    fun cancelIncomingOffer(activityId: ActivityKey) {
        manager.cancel(incomingNotificationId(activityId))
    }

    fun postPairingRequest() {
        try {
            manager.notify(PAIRING_NOTIFICATION_ID, baseBuilder(TRANSFER_CHANNEL_ID)
                .setContentTitle(locale.string(R.string.notification_pairing_title))
                .setContentText(locale.string(R.string.notification_pairing_content))
                .setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE).build())
        } catch (_: SecurityException) { /* The application queue still replays when UI opens. */ }
    }

    fun cancelPairingRequest() = manager.cancel(PAIRING_NOTIFICATION_ID)

    private fun incomingNotificationId(activityId: ActivityKey) =
        INCOMING_REQUEST_CODE_BASE xor activityId.value.hashCode()

    private fun baseBuilder(channel: String): Notification.Builder =
        Notification.Builder(application, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(
                application,
                CONTENT_REQUEST_CODE,
                Intent(application, MainActivity::class.java),
                immutableUpdateCurrent,
            ))

    private fun initialForeground(channel: String): Notification =
        baseBuilder(channel).setContentTitle(locale.string(R.string.app_name)).setOngoing(true).build()

    private fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getForegroundService(
        application,
        requestCode,
        Intent(application, ShareForegroundService::class.java).setAction(action),
        immutableUpdateCurrent,
    )

    private val immutableUpdateCurrent = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val RECEIVE_CHANNEL_ID = "unishare_background_receive"
        const val TRANSFER_CHANNEL_ID = "unishare_transfer_progress"
        private const val CONTENT_REQUEST_CODE = 1100
        private const val STOP_REQUEST_CODE = 1101
        private const val CANCEL_REQUEST_CODE = 1102
        private const val INCOMING_REQUEST_CODE_BASE = 0x55000000
        private const val PAIRING_NOTIFICATION_ID = 1002
    }
}
