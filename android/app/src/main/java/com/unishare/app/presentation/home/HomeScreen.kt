package com.unishare.app.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unishare.app.R
import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.domain.DeviceKey
import com.unishare.app.presentation.components.DeviceIcon
import com.unishare.app.presentation.components.EmptyState
import com.unishare.app.presentation.components.StatusIndicator
import com.unishare.app.presentation.components.StatusTone
import java.util.Calendar
import java.util.Date

@Composable
fun HomeScreen(
    state: HomeUiState,
    onThisDeviceClick: () -> Unit,
    onRecentClick: (DeviceKey) -> Unit,
    onNearbyClick: (DeviceKey) -> Unit,
    onSeeAll: () -> Unit,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp,
            top = 4.dp,
            end = 18.dp,
            bottom = 104.dp,
        ),
    ) {
        item {
            HomeHeader(onSearch = onSearch, onMore = onMore)
        }
        item {
            ThisDeviceCard(
                device = state.thisDevice,
                onClick = onThisDeviceClick,
            )
        }
        item {
            SectionHeader(
                title = stringResource(R.string.home_recent),
                actionLabel = stringResource(R.string.action_see_all),
                onAction = onSeeAll,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item {
            GroupedSurface {
                if (state.recent.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.home_no_recent),
                        detail = stringResource(R.string.home_no_recent_detail),
                    )
                } else {
                    state.recent.forEachIndexed { index, activity ->
                        RecentActivityRow(activity = activity, onClick = { onRecentClick(activity.deviceId) })
                        if (index != state.recent.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        item {
            SectionHeader(
                title = stringResource(R.string.home_nearby_devices),
                icon = Icons.Outlined.Refresh,
                iconDescription = stringResource(R.string.action_refresh_nearby),
                onAction = onRefresh,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
        if (state.discoveryError != null) {
            item { DiscoveryErrorBanner() }
        }
        if (state.isLoading) {
            item { LoadingBanner() }
        }
        item {
            GroupedSurface {
                if (state.nearby.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.home_no_nearby),
                        detail = stringResource(R.string.home_no_nearby_detail),
                    )
                } else {
                    state.nearby.forEachIndexed { index, device ->
                        NearbyDeviceRow(device = device, onClick = { onNearbyClick(device.id) })
                        if (index != state.nearby.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun HomeHeader(onSearch: () -> Unit, onMore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_title),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 31.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.action_search))
        }
        IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
    }
}

@Composable
private fun ThisDeviceCard(device: ThisDeviceUi, onClick: () -> Unit) {
    GroupedSurface(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 98.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Smartphone,
                contentDescription = stringResource(R.string.device_icon_android),
                modifier = Modifier.size(46.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = stringResource(R.string.home_this_device),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val (label, tone) = when (device.status) {
                    ThisDeviceStatus.ONLINE -> stringResource(R.string.status_online) to StatusTone.ONLINE
                    ThisDeviceStatus.OFFLINE -> stringResource(R.string.status_offline) to StatusTone.NEUTRAL
                    ThisDeviceStatus.STARTING -> stringResource(R.string.status_starting) to StatusTone.ACTIVE
                    ThisDeviceStatus.UNAVAILABLE -> stringResource(R.string.status_unavailable) to StatusTone.ERROR
                }
                StatusIndicator(label = label, tone = tone)
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = stringResource(R.string.action_open_device),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    icon: ImageVector? = null,
    iconDescription: String? = null,
    onAction: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (icon != null) {
            IconButton(onClick = onAction, modifier = Modifier.size(48.dp)) {
                Icon(icon, contentDescription = requireNotNull(iconDescription), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun GroupedSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column { content() }
    }
}

@Composable
private fun RecentActivityRow(activity: RecentActivityUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 74.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceIcon(
            platform = activity.platform,
            contentDescription = deviceIconDescription(activity.platform),
            modifier = Modifier.size(42.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = activity.deviceName.ifBlank { stringResource(R.string.unknown_device) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = activity.progressPercent?.let {
                    stringResource(R.string.recent_progress, activity.displayName, it)
                } ?: activity.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatActivityTime(activity.updatedAtMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = stringResource(R.string.action_open_device),
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NearbyDeviceRow(device: NearbyDeviceUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 76.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceIcon(
            platform = device.platform,
            contentDescription = deviceIconDescription(device.platform),
            modifier = Modifier.size(42.dp),
            tint = if (device.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = device.name.ifBlank { stringResource(R.string.unknown_device) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val identityChanged = device.identityChanged
            val trust = when {
                identityChanged -> stringResource(R.string.status_identity_changed)
                device.trusted -> stringResource(R.string.status_trusted)
                else -> stringResource(R.string.status_unpaired)
            }
            val presence = when {
                device.connected -> stringResource(R.string.status_connected)
                device.online -> stringResource(R.string.status_online)
                else -> stringResource(R.string.status_offline)
            }
            StatusIndicator(
                label = stringResource(R.string.device_status_format, trust, presence),
                tone = when {
                    identityChanged -> StatusTone.ERROR
                    device.online -> StatusTone.ONLINE
                    else -> StatusTone.NEUTRAL
                },
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = stringResource(R.string.action_open_device),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiscoveryErrorBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.WifiOff,
                contentDescription = stringResource(R.string.home_discovery_error),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column {
                Text(
                    text = stringResource(R.string.home_discovery_error),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.home_discovery_error_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun LoadingBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.home_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun deviceIconDescription(platform: DevicePlatform?): String = stringResource(
    when (platform) {
        DevicePlatform.WINDOWS -> R.string.device_icon_windows
        DevicePlatform.ANDROID -> R.string.device_icon_android
        DevicePlatform.IOS -> R.string.device_icon_ios
        null -> R.string.device_icon_unknown
    },
)

@Composable
private fun formatActivityTime(timestamp: Long): String {
    val context = LocalContext.current
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        now.get(Calendar.ERA) == then.get(Calendar.ERA) &&
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) ->
            android.text.format.DateFormat.getTimeFormat(context).format(Date(timestamp))
        yesterday.get(Calendar.ERA) == then.get(Calendar.ERA) &&
            yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) ->
            stringResource(R.string.recent_yesterday)
        else -> android.text.format.DateFormat.getDateFormat(context).format(Date(timestamp))
    }
}
