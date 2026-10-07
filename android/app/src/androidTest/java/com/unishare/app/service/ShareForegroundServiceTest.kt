package com.unishare.app.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.unishare.app.domain.ActivityKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareForegroundServiceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manifestDeclaresConnectedDeviceForegroundServiceWithoutBootReceiver() {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(
                    (PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES).toLong(),
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
            )
        }
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE in permissions)
        assertTrue(Manifest.permission.CHANGE_NETWORK_STATE in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        val bootIntent = Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName)
        val bootReceivers = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryBroadcastReceivers(
                bootIntent,
                PackageManager.ResolveInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryBroadcastReceivers(bootIntent, 0)
        }
        assertTrue(bootReceivers.isEmpty())

        val service = requireNotNull(packageInfo.services?.singleOrNull {
            it.name == ShareForegroundService::class.java.name
        })
        assertFalse(service.exported)
        if (Build.VERSION.SDK_INT >= 29) {
            assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0)
        }
    }

    @Test
    fun notificationFactoryCreatesPrivateChannelsAndGenericActions() {
        val factory = ShareNotificationFactory(context)
        factory.createChannels()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel(ShareNotificationFactory.RECEIVE_CHANNEL_ID))
        assertNotNull(manager.getNotificationChannel(ShareNotificationFactory.TRANSFER_CHANNEL_ID))

        val ongoing = factory.ongoingReceive(onlineDeviceCount = 2)
        assertEquals(android.app.Notification.VISIBILITY_SECRET, ongoing.visibility)
        assertEquals(1, ongoing.actions.size)
        assertEquals(ShareNotificationFactory.RECEIVE_CHANNEL_ID, ongoing.channelId)

        val transfer = factory.transferProgress(progress = 42, activityId = ActivityKey("activity"))
        assertEquals(android.app.Notification.VISIBILITY_SECRET, transfer.visibility)
        assertEquals(1, transfer.actions.size)
        assertEquals(ShareNotificationFactory.TRANSFER_CHANNEL_ID, transfer.channelId)
    }

    @Test
    fun serviceIsStickyAndStopBackgroundActionNeverCancelsTransfer() = runTest {
        var backgroundEnabled: Boolean? = null
        var cancelled: ActivityKey? = null
        val handler = ShareServiceActionHandler(
            setBackgroundReceive = { backgroundEnabled = it },
            cancelTransfer = { cancelled = it },
        )

        handler.handle(Intent(ShareForegroundService.ACTION_STOP_BACKGROUND_RECEIVE))

        assertEquals(Service.START_STICKY, ShareForegroundService.RESTART_MODE)
        assertEquals(false, backgroundEnabled)
        assertEquals(null, cancelled)
    }
}
