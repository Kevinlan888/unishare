package com.unishare.app.presentation.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.unishare.app.R
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.DeviceKey
import com.unishare.app.presentation.home.HomeScreen
import com.unishare.app.presentation.home.HomeUiState
import com.unishare.app.presentation.devices.DevicesScreen
import com.unishare.app.presentation.devices.DevicesUiState
import com.unishare.app.presentation.history.HistoryScreen
import com.unishare.app.presentation.history.HistoryUiState
import com.unishare.app.presentation.settings.SettingsScreen
import com.unishare.app.presentation.settings.SettingsUiState

private enum class TopLevelItem(
    val destination: AppDestination,
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    SHARE(AppDestination.Home, R.string.nav_share, Icons.Outlined.Share),
    DEVICES(AppDestination.Devices, R.string.nav_devices, Icons.Outlined.Devices),
    HISTORY(AppDestination.History, R.string.nav_history, Icons.Outlined.History),
    SETTINGS(AppDestination.Settings, R.string.nav_settings, Icons.Outlined.Settings),
}

private val topLevelRoutes = AppDestination.topLevel.map { it.route }.toSet()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTopLevelTabSwitch(): Boolean =
    initialState.destination.route in topLevelRoutes &&
        targetState.destination.route in topLevelRoutes

private fun AnimatedContentTransitionScope<NavBackStackEntry>.motion(isPop: Boolean) =
    navigationMotion(initialState.destination.route, targetState.destination.route, isPop)

private val topLevelEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition? = {
    if (isTopLevelTabSwitch()) EnterTransition.None else null
}

private val topLevelExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition? = {
    if (isTopLevelTabSwitch()) ExitTransition.None else null
}

private fun NavGraphBuilder.topLevelComposable(
    route: String,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(
        route = route,
        enterTransition = topLevelEnterTransition,
        exitTransition = topLevelExitTransition,
        popEnterTransition = topLevelEnterTransition,
        popExitTransition = topLevelExitTransition,
        content = content,
    )
}

@Composable
fun UniShareNavHost(
    homeUiState: HomeUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    sessionContent: (@Composable (DeviceKey, () -> Unit) -> Unit)? = null,
    receiveContent: (@Composable (ActivityKey, () -> Unit) -> Unit)? = null,
    pendingReceiveActivityId: ActivityKey? = null,
    onReceiveActivityConsumed: () -> Unit = {},
    pendingSessionDeviceId: DeviceKey? = null,
    onSessionConsumed: () -> Unit = {},
    devicesContent: (@Composable ((DeviceKey) -> Unit) -> Unit)? = null,
    historyContent: (@Composable () -> Unit)? = null,
    settingsContent: (@Composable () -> Unit)? = null,
) {
    val entry by navController.currentBackStackEntryAsState()
    val visibleEntries by navController.visibleEntries.collectAsState()
    val currentRoute = entry?.destination?.route ?: AppDestination.Home.route
    val showBottomNavigation = currentRoute in topLevelRoutes
    // Popped details remain visible until their exit transition completes.
    val showHomeFab = currentRoute == AppDestination.Home.route &&
        visibleEntries.all { it.destination.route in topLevelRoutes }

    LaunchedEffect(pendingReceiveActivityId?.value) {
        pendingReceiveActivityId?.let { activityId ->
            navController.navigate(AppDestination.Receive(activityId).route) {
                launchSingleTop = true
            }
            onReceiveActivityConsumed()
        }
    }
    LaunchedEffect(pendingSessionDeviceId?.value) {
        pendingSessionDeviceId?.let {
            navController.navigate(AppDestination.Session(it).route) { launchSingleTop = true }
            onSessionConsumed()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomNavigation) {
                UniShareBottomNavigation(
                    selectedRoute = currentRoute,
                    onSelect = { navigateTopLevel(navController, it.destination) },
                )
            }
        },
        floatingActionButton = {
            if (showHomeFab) {
                FloatingActionButton(
                    onClick = { navigateTopLevel(navController, AppDestination.Devices) },
                    modifier = Modifier.size(64.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.action_new_share),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Home.route,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            enterTransition = {
                when (motion(isPop = false)) {
                    NavigationMotion.ForwardDetail -> slideInHorizontally(initialOffsetX = { it })
                    else -> EnterTransition.None
                }
            },
            exitTransition = {
                when (motion(isPop = false)) {
                    NavigationMotion.ForwardDetail -> slideOutHorizontally(targetOffsetX = { -it / 4 })
                    else -> ExitTransition.None
                }
            },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = {
                when (motion(isPop = true)) {
                    NavigationMotion.PopDetail -> slideOutHorizontally(targetOffsetX = { it }) + scaleOut(
                        targetScale = 0.9f,
                        transformOrigin = TransformOrigin.Center,
                    )
                    else -> ExitTransition.None
                }
            },
        ) {
            topLevelComposable(AppDestination.Home.route) {
                HomeScreen(
                    state = homeUiState,
                    onThisDeviceClick = { navigateTopLevel(navController, AppDestination.Settings) },
                    onRecentClick = { navController.navigate(AppDestination.Session(it).route) },
                    onNearbyClick = { navController.navigate(AppDestination.Session(it).route) },
                    onSeeAll = { navigateTopLevel(navController, AppDestination.History) },
                    onRefresh = onRefresh,
                    onSearch = { navigateTopLevel(navController, AppDestination.History) },
                    onMore = { navigateTopLevel(navController, AppDestination.Settings) },
                )
            }
            topLevelComposable(AppDestination.Devices.route) {
                val open: (DeviceKey) -> Unit = { navController.navigate(AppDestination.Session(it).route) }
                if (devicesContent != null) devicesContent(open)
                else DevicesScreen(DevicesUiState(), {}, {}, {}, {}, open)
            }
            topLevelComposable(AppDestination.History.route) {
                if (historyContent != null) historyContent()
                else HistoryScreen(HistoryUiState(), {}, {})
            }
            topLevelComposable(AppDestination.Settings.route) {
                if (settingsContent != null) settingsContent()
                else SettingsScreen(SettingsUiState(), {}, {}, {}, {}, {}, {})
            }
            composable(
                route = AppDestination.SESSION_PATTERN,
                arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val encodedId = backStackEntry.arguments?.getString("deviceId").orEmpty()
                val deviceId = (AppDestination.fromRoute("${AppDestination.SESSION_PREFIX}/$encodedId") as? AppDestination.Session)
                    ?.deviceId ?: DeviceKey("")
                if (sessionContent == null) {
                    NestedPlaceholder(
                        title = stringResource(R.string.screen_session_title),
                        argument = deviceId.value,
                        onBack = navController::popBackStack,
                    )
                } else {
                    sessionContent(deviceId, navController::popBackStack)
                }
            }
            composable(
                route = AppDestination.RECEIVE_PATTERN,
                arguments = listOf(navArgument("activityId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val encodedId = backStackEntry.arguments?.getString("activityId").orEmpty()
                val activityId = (AppDestination.fromRoute("${AppDestination.RECEIVE_PREFIX}/$encodedId") as? AppDestination.Receive)
                    ?.activityId
                if (activityId == null || receiveContent == null) {
                    NestedPlaceholder(
                        title = stringResource(R.string.screen_receive_title),
                        argument = activityId?.value.orEmpty(),
                        onBack = navController::popBackStack,
                    )
                } else {
                    receiveContent(activityId, navController::popBackStack)
                }
            }
        }
    }
}

@Composable
private fun UniShareBottomNavigation(selectedRoute: String, onSelect: (TopLevelItem) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
    ) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 0.dp,
        ) {
            TopLevelItem.entries.forEach { item ->
                val label = stringResource(item.label)
                NavigationBarItem(
                    selected = selectedRoute == item.destination.route,
                    onClick = { onSelect(item) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = label,
                        )
                    },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
    }
}

private fun navigateTopLevel(navController: NavController, destination: AppDestination) {
    navController.navigate(destination.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun TopLevelPlaceholder(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = detail,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NestedPlaceholder(title: String, argument: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = argument,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
