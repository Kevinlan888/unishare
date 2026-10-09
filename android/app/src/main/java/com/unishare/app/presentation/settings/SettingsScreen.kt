package com.unishare.app.presentation.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.unishare.app.R
import com.unishare.app.data.settings.*
import com.unishare.app.presentation.components.ManagementChoice

@Composable
fun SettingsScreen(state: SettingsUiState, onName: (String) -> Unit, onBackground: (Boolean) -> Unit, onNotifications: (Boolean) -> Unit, onLanguage: (AppLanguage) -> Unit, onTheme: (AppTheme) -> Unit, onDownloads: () -> Unit, onNotificationSettings: () -> Unit = {}) {
    val settings = state.settings
    var name by rememberSaveable(settings.deviceName) { mutableStateOf(settings.deviceName) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.screen_settings_title), style = MaterialTheme.typography.headlineMedium) }
        state.error?.let { error -> item { Text(stringResource(when (error) {
            SettingsError.INVALID_NAME -> R.string.settings_invalid_name
            SettingsError.NOTIFICATION_DENIED -> R.string.settings_notification_denied
            SettingsError.ACTIVITY_NOT_VISIBLE -> R.string.settings_visible_required
            SettingsError.RUNTIME_FAILED -> R.string.settings_runtime_failed
            SettingsError.WRITE_FAILED -> R.string.settings_write_failed
        }), color = MaterialTheme.colorScheme.error) } }
        if (state.error == SettingsError.NOTIFICATION_DENIED) item {
            TextButton(onClick = onNotificationSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_open_notifications)) }
        }
        item { SettingsCard {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_device_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = { onName(name) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_save_name)) }
            Text(stringResource(R.string.settings_name_restart), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { SettingsCard {
            Text(stringResource(R.string.settings_receive_directory), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_downloads))
            Text(stringResource(if (Build.VERSION.SDK_INT >= 29) R.string.settings_downloads_only else R.string.settings_storage_unsupported), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.directoryCorrected) Text(stringResource(R.string.settings_directory_corrected))
            TextButton(onClick = onDownloads, enabled = Build.VERSION.SDK_INT >= 29, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_restore_downloads)) }
        } }
        item { SettingsCard {
            SettingsToggle(stringResource(R.string.settings_background), settings.backgroundReceiveEnabled, !state.busy && !state.requestNotificationPermission, onBackground)
            Text(stringResource(R.string.settings_background_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsToggle(stringResource(R.string.settings_notifications), settings.notificationsEnabled, !state.busy, onNotifications)
        } }
        item { ManagementChoice(stringResource(R.string.settings_language), settings.language, AppLanguage.entries, { language -> stringResource(when (language) { AppLanguage.SYSTEM -> R.string.settings_follow_system; AppLanguage.ENGLISH -> R.string.settings_english; AppLanguage.SIMPLIFIED_CHINESE -> R.string.settings_chinese }) }, onLanguage) }
        item { ManagementChoice(stringResource(R.string.settings_theme), settings.theme, AppTheme.entries, { theme -> stringResource(when (theme) { AppTheme.SYSTEM -> R.string.settings_follow_system; AppTheme.LIGHT -> R.string.settings_light; AppTheme.DARK -> R.string.settings_dark }) }, onTheme) }
        item { SettingsCard {
            Text(stringResource(R.string.settings_storage), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_storage_detail))
            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.settings_version))
        } }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}
@Composable
private fun SettingsToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
