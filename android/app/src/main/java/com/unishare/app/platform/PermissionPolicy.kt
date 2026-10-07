package com.unishare.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class PermissionRequirements(
    val postNotifications: Boolean,
    val localNetwork: Boolean,
)

object PermissionPolicy {
    /** Target 36 remains on Android's compatibility path for local-network access. */
    fun requirementsFor(api: Int, target: Int): PermissionRequirements = PermissionRequirements(
        postNotifications = api >= 33 && target >= 33,
        localNetwork = api >= 37 && target >= 37,
    )
}

fun interface NotificationPermissionChecker {
    fun isGranted(): Boolean
}

class AndroidNotificationPermissionChecker(context: Context) : NotificationPermissionChecker {
    private val application = context.applicationContext

    override fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
