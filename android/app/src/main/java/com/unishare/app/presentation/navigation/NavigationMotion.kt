package com.unishare.app.presentation.navigation

enum class NavigationMotion {
    None,
    ForwardDetail,
    PopDetail,
}

private val motionTopLevelRoutes = AppDestination.topLevel.map { it.route }.toSet()

fun navigationMotion(initialRoute: String?, targetRoute: String?, isPop: Boolean): NavigationMotion = when {
    !isPop && initialRoute in motionTopLevelRoutes && targetRoute.isDetailRoute() ->
        NavigationMotion.ForwardDetail
    isPop && initialRoute.isDetailRoute() && targetRoute in motionTopLevelRoutes ->
        NavigationMotion.PopDetail
    else -> NavigationMotion.None
}

private fun String?.isDetailRoute(): Boolean =
    this?.startsWith("${AppDestination.SESSION_PREFIX}/") == true ||
        this?.startsWith("${AppDestination.RECEIVE_PREFIX}/") == true
