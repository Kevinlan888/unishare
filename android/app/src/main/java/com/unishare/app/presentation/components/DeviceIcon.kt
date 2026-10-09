package com.unishare.app.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.unishare.app.data.sdk.DevicePlatform

@Composable
fun DeviceIcon(
    platform: DevicePlatform?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val icon = when (platform) {
        DevicePlatform.WINDOWS -> Icons.Outlined.Computer
        DevicePlatform.ANDROID -> Icons.Outlined.Smartphone
        DevicePlatform.IOS -> Icons.Outlined.PhoneIphone
        null -> Icons.Outlined.DevicesOther
    }
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}
