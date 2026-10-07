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

/** Notification copy is intentionally generic: payload, peer and path data never enter it. */
class ShareNotificationFactory(context: Context) {
    private val application = context.applicationContext
    private val manager = application.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannels(listOf(
            NotificationChannel(
                RECEIVE_CHANNEL_ID,
                application.getString(R.string.notification_channel_receive),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = application.getString(R.string.notification_channel_receive_description)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
            NotificationChannel(
                TRANSFER_CHANNEL_ID,
                application.getString(R.string.notification_channel_transfer),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = application.getString(R.string.notification_channel_transfer_description)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        ))
    }

    fun ongoingReceive(onlineDeviceCount: Int): Notification =
        baseBuilder(RECEIVE_CHANNEL_ID)
            .setContentTitle(application.getString(R.string.notification_receive_title))
            .setContentText(application.resources.getQuantityString(
                R.plurals.notification_receive_content,
                onlineDeviceCount.coerceAtLeast(0),
                onlineDeviceCount.coerceAtLeast(0),
            ))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(application, R.drawable.ic_launcher_foreground),
                application.getString(R.string.notification_stop_background),
                serviceAction(ShareForegroundService.ACTION_STOP_BACKGROUND_RECEIVE, STOP_REQUEST_CODE),
            ).build())
            .build()

    fun transferProgress(progress: Int, activityId: ActivityKey): Notification =
        baseBuilder(TRANSFER_CHANNEL_ID)
            .setContentTitle(application.getString(R.string.notification_transfer_title))
            .setContentText(application.getString(R.string.notification_transfer_content))
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(application, R.drawable.ic_launcher_foreground),
                application.getString(R.string.notification_cancel_transfer),
                PendingIntent.getForegroundService(
                    application,
                    CANCEL_REQUEST_CODE xor activityId.value.hashCode(),
                    Intent(application, ShareForegroundService::class.java)
                        .setAction(ShareForegroundService.ACTION_CANCEL_TRANSFER)
                        .putExtra(ShareForegroundService.EXTRA_ACTIVITY_ID, activityId.value),
                    immutableUpdateCurrent,
                ),
            ).build())
            .build()

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
    }
}
