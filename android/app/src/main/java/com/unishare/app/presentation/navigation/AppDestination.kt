package com.unishare.app.presentation.navigation

import com.unishare.app.domain.DeviceKey
import java.nio.charset.StandardCharsets
import java.util.Base64

sealed interface AppDestination {
    val route: String
    val isTopLevel: Boolean

    data object Home : AppDestination {
        override val route = HOME_ROUTE
        override val isTopLevel = true
    }

    data class Session(val deviceId: DeviceKey) : AppDestination {
        override val route: String = "$SESSION_PREFIX/${deviceId.value.encodeRoutePart()}"
        override val isTopLevel = false
    }

    data class Receive(val requestId: Long) : AppDestination {
        override val route: String = "$RECEIVE_PREFIX/$requestId"
        override val isTopLevel = false
    }

    data object Devices : AppDestination {
        override val route = DEVICES_ROUTE
        override val isTopLevel = true
    }

    data object History : AppDestination {
        override val route = HISTORY_ROUTE
        override val isTopLevel = true
    }

    data object Settings : AppDestination {
        override val route = SETTINGS_ROUTE
        override val isTopLevel = true
    }

    companion object {
        const val HOME_ROUTE = "home"
        const val DEVICES_ROUTE = "devices"
        const val HISTORY_ROUTE = "history"
        const val SETTINGS_ROUTE = "settings"
        const val SESSION_PREFIX = "session"
        const val RECEIVE_PREFIX = "receive"
        const val SESSION_PATTERN = "$SESSION_PREFIX/{deviceId}"
        const val RECEIVE_PATTERN = "$RECEIVE_PREFIX/{requestId}"

        val topLevel = listOf(Home, Devices, History, Settings)

        fun fromRoute(route: String?): AppDestination? {
            if (route == null) return null
            return when (route) {
                HOME_ROUTE -> Home
                DEVICES_ROUTE -> Devices
                HISTORY_ROUTE -> History
                SETTINGS_ROUTE -> Settings
                else -> parseDynamic(route)
            }
        }

        private fun parseDynamic(route: String): AppDestination? {
            val separator = route.indexOf('/')
            if (separator <= 0 || separator == route.lastIndex) return null
            val prefix = route.substring(0, separator)
            val argument = route.substring(separator + 1)
            return when (prefix) {
                SESSION_PREFIX -> argument.decodeRoutePart()?.let { Session(DeviceKey(it)) }
                RECEIVE_PREFIX -> argument.toLongOrNull()?.let(::Receive)
                else -> null
            }
        }
    }
}

private fun String.encodeRoutePart(): String = Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(toByteArray(StandardCharsets.UTF_8))

private fun String.decodeRoutePart(): String? = try {
    String(Base64.getUrlDecoder().decode(this), StandardCharsets.UTF_8)
} catch (_: IllegalArgumentException) {
    null
}
