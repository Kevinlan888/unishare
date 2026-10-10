package com.unishare.app.platform

import android.content.ContentResolver
import android.os.Build
import android.provider.Settings
import com.unishare.app.domain.isValidDeviceName

class AndroidDeviceNameProvider(
    private val contentResolver: ContentResolver,
    private val modelName: String? = Build.MODEL,
) {
    fun deviceName(): String = selectAndroidDeviceName(
        systemName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME),
        modelName = modelName,
    )
}

internal fun selectAndroidDeviceName(systemName: String?, modelName: String?): String =
    sequenceOf(systemName, modelName)
        .mapNotNull { candidate -> candidate?.trim()?.takeIf(::isValidDeviceName) }
        .firstOrNull()
        ?: "Android"
