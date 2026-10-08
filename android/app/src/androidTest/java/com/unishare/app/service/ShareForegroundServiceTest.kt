package com.unishare.app.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import android.content.res.Configuration
import android.os.LocaleList
import android.app.Notification
import com.unishare.app.UniShareApplication
import com.unishare.app.data.settings.AppLanguage
import com.unishare.app.domain.ActivityKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import com.unishare.app.platform.AppLocaleResources
import com.unishare.app.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import com.unishare.app.presentation.settings.SettingsViewModel
import com.unishare.app.data.transfer.IncomingConsentEvent

class ShareForegroundServiceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun real_settings_and_offer_relay_cancel_optional_alerts_without_removing_ongoing_transfer_notification() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = UniShareApplication.graph(context)
        val original = graph.settingsRepository.settings.first()
        val permissionWasGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = context.getSystemService(NotificationManager::class.java)
        val offer = ActivityKey("task11-real-notification")
        val offerId = 0x55000000 xor offer.value.hashCode()
        val transferId = 9911
        val factory = ShareNotificationFactory(context)
        val viewModels = androidx.lifecycle.ViewModelStore()
        suspend fun awaitCondition(condition: () -> Boolean) { withTimeout(5_000) { while (!condition()) delay(10) } }
        try {
            if (!permissionWasGranted) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            graph.settingsRepository.setNotificationsEnabled(true)
            graph.settingsRepository.setLanguage(AppLanguage.ENGLISH)
            graph.localeResources.language.first { it == AppLanguage.ENGLISH }
            graph.localeResources.settings.first { it.notificationsEnabled }
            factory.createChannels()
            manager.notify(transferId, factory.transferProgress(30))
            val events = MutableSharedFlow<IncomingConsentEvent>(extraBufferCapacity = 2)
            ReceiveOfferNotificationRelay(events, { true }, factory::postIncomingOffer, factory::cancelIncomingOffer, scope, graph.localeResources.settings)
            events.emit(IncomingConsentEvent.Pending(offer))
            awaitCondition { manager.activeNotifications.any { it.id == offerId } }
            graph.settingsRepository.setLanguage(AppLanguage.SIMPLIFIED_CHINESE)
            awaitCondition { manager.activeNotifications.any { it.id == offerId && it.notification.extras.getString(Notification.EXTRA_TITLE) == "接收请求待确认" } }
            lateinit var vm: SettingsViewModel
            instrumentation.runOnMainSync {
                vm = SettingsViewModel(graph.settingsRepository, graph.runtimeController::setBackgroundReceive)
                viewModels.put("notification-settings", vm)
                vm.setNotifications(false)
            }
            awaitCondition { manager.activeNotifications.none { it.id == offerId } }
            assertTrue(manager.activeNotifications.any { it.id == transferId && it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 })
            events.emit(IncomingConsentEvent.Pending(offer))
            graph.localeResources.settings.first { !it.notificationsEnabled }
            delay(50)
            assertFalse(manager.activeNotifications.any { it.id == offerId })
        } finally {
            scope.cancel()
            instrumentation.runOnMainSync { viewModels.clear() }
            factory.cancelIncomingOffer(offer)
            manager.cancel(transferId)
            graph.settingsRepository.setNotificationsEnabled(original.notificationsEnabled)
            graph.settingsRepository.setLanguage(original.language)
            graph.settingsRepository.setBackgroundReceiveEnabled(original.backgroundReceiveEnabled)
            // Revoking an app runtime permission kills its instrumentation process. UTP
            // uninstalls this test-owned APK after the run, which also removes the grant.
        }
    }
    @Test fun cold_notification_factory_uses_brand_only_until_persisted_language_is_loaded() {
        val pending = MutableSharedFlow<AppSettings>(replay = 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val locale = AppLocaleResources(context, pending, scope)
            val factory = ShareNotificationFactory(context, locale)
            assertEquals("UniShare", factory.transferProgress(0).extras.getString(Notification.EXTRA_TITLE))
            assertEquals(null, factory.transferProgress(0).extras.getString(Notification.EXTRA_TEXT))
            pending.tryEmit(AppSettings(language = AppLanguage.SIMPLIFIED_CHINESE))
            assertEquals("UniShare 传输", factory.transferProgress(0).extras.getString(Notification.EXTRA_TITLE))
        } finally { scope.cancel() }
    }
    @Test fun app_chinese_policy_localizes_every_notification_despite_english_system_resources() {
        val graph = UniShareApplication.graph(context)
        val original = runBlocking { graph.settingsRepository.settings.first() }
        val systemLocales = context.resources.configuration.locales.toLanguageTags()
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("en-US")) })
        try {
            runBlocking {
                graph.settingsRepository.setLanguage(AppLanguage.SIMPLIFIED_CHINESE)
                graph.localeResources.language.first { it == AppLanguage.SIMPLIFIED_CHINESE }
            }
            val factory = ShareNotificationFactory(english)
            factory.createChannels()
            assertEquals("UniShare 已准备接收", factory.ongoingReceive(2).extras.getString(Notification.EXTRA_TITLE))
            assertEquals("停止后台接收", factory.ongoingReceive(2).actions.single().title.toString())
            assertEquals("UniShare 传输", factory.transferProgress(40).extras.getString(Notification.EXTRA_TITLE))
            assertEquals("取消传输", factory.transferProgress(40).actions.single().title.toString())
            assertEquals("接收请求待确认", factory.incomingOffer(ActivityKey("locale-offer")).extras.getString(Notification.EXTRA_TITLE))
            assertEquals("后台接收", context.getSystemService(NotificationManager::class.java).getNotificationChannel(ShareNotificationFactory.RECEIVE_CHANNEL_ID).name.toString())
            assertEquals("没有可打开此文件的应用。", graph.localeResources.string(com.unishare.app.R.string.receive_open_failed))
            assertEquals(systemLocales, context.resources.configuration.locales.toLanguageTags())
        } finally { runBlocking { graph.settingsRepository.setLanguage(original.language) } }
    }

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

        val transfer = factory.transferProgress(progress = 42)
        assertEquals(android.app.Notification.VISIBILITY_SECRET, transfer.visibility)
        assertEquals(1, transfer.actions.size)
        assertEquals(ShareNotificationFactory.TRANSFER_CHANNEL_ID, transfer.channelId)
    }

    @Test
    fun serviceIsStickyAndStopBackgroundActionNeverCancelsTransfer() = runTest {
        var backgroundEnabled: Boolean? = null
        var cancelCount = 0
        val handler = ShareServiceActionHandler(
            setBackgroundReceive = { backgroundEnabled = it },
            cancelCurrentTransfer = { cancelCount++ },
        )

        handler.handle(Intent(ShareForegroundService.ACTION_STOP_BACKGROUND_RECEIVE))

        assertEquals(Service.START_STICKY, ShareForegroundService.RESTART_MODE)
        assertEquals(false, backgroundEnabled)
        assertEquals(0, cancelCount)
    }

    @Test
    fun cancelActionDelegatesToTheDeterministicCurrentTransfer() = runTest {
        var cancelCount = 0
        val handler = ShareServiceActionHandler(
            setBackgroundReceive = { },
            cancelCurrentTransfer = { cancelCount++ },
        )

        handler.handle(Intent(ShareForegroundService.ACTION_CANCEL_TRANSFER))

        assertEquals(1, cancelCount)
    }
}
