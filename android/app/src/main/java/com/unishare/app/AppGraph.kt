package com.unishare.app

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.unishare.app.data.content.AndroidReceivePublisher
import com.unishare.app.data.content.StagingStore
import com.unishare.app.data.device.DefaultDeviceRepository
import com.unishare.app.data.history.ActivityDatabase
import com.unishare.app.data.history.RoomActivityRepository
import com.unishare.app.data.sdk.AndroidShareSdkGateway
import com.unishare.app.data.sdk.ShareSessionManager
import com.unishare.app.data.settings.DataStoreSettingsRepository
import com.unishare.app.data.transfer.SelectedContentResolver
import com.unishare.app.data.transfer.TransferCoordinator
import com.unishare.app.domain.ActivityTracker
import com.unishare.app.platform.AndroidNotificationPermissionChecker
import com.unishare.app.service.ForegroundServiceController
import com.unishare.app.service.RuntimeController
import com.unishare.app.service.ShareSessionRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Injectable, factory-countable process owner used by UniShareApplication. */
internal class ProcessGraphOwner<T>(factory: () -> T) {
    val value: T by lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)
    fun initialize(): T = value
}

/**
 * Owns the SDK boundary for one process graph: one gateway feeds one session manager.
 *
 * Keeping both factories behind this seam makes the production composition countable in
 * local tests without constructing the native SDK or an Android database.
 */
internal class AppSessionComposition<G, S>(
    gatewayFactory: () -> G,
    sessionFactory: (G) -> S,
) {
    private val gatewayOwner = ProcessGraphOwner(gatewayFactory)
    private val sessionOwner = ProcessGraphOwner { sessionFactory(gatewayOwner.value) }

    val gateway: G
        get() = gatewayOwner.value

    val session: S
        get() = sessionOwner.value
}

/** Process-wide ownership graph. Every SDK consumer shares sessionManager's single gateway. */
class AppGraph(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settingsStore = PreferenceDataStoreFactory.create(scope = scope) {
        application.preferencesDataStoreFile("settings")
    }
    val settingsRepository = DataStoreSettingsRepository(settingsStore)

    private val activityDatabase = Room.databaseBuilder(
        application,
        ActivityDatabase::class.java,
        "unishare-activity.db",
    ).build()
    val activityRepository = RoomActivityRepository(activityDatabase)

    private val sessionComposition = AppSessionComposition(
        gatewayFactory = { AndroidShareSdkGateway(application, scope) },
        sessionFactory = { gateway -> ShareSessionManager(gateway, scope) },
    )
    val sessionManager = sessionComposition.session
    val deviceRepository = DefaultDeviceRepository(sessionManager, scope)
    val activityTracker = ActivityTracker(activityRepository, scope)
    val stagingStore = StagingStore(application)
    val receivePublisher = AndroidReceivePublisher(application)
    val selectedContentResolver = SelectedContentResolver(application.contentResolver)
    val transferCoordinator = TransferCoordinator(
        session = sessionManager,
        tracker = activityTracker,
        devices = deviceRepository,
        settings = settingsRepository,
        stagingStore = stagingStore,
        receivePublisher = receivePublisher,
        scope = scope,
    )
    val foregroundServiceController = ForegroundServiceController(
        application,
        transferCoordinator.activeTransferCount,
    )
    val runtimeController = RuntimeController(
        settingsRepository = settingsRepository,
        session = ShareSessionRuntime(sessionManager),
        foregroundService = foregroundServiceController,
        activeTransferCount = transferCoordinator.activeTransferCount,
        notificationPermission = AndroidNotificationPermissionChecker(application),
        scope = scope,
    )

    init {
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            activityRepository.markNonTerminalInterrupted(now)
            activityRepository.prune(now)
        }
    }
}
