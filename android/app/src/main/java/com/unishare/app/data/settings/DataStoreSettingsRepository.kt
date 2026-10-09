package com.unishare.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    override val settings: Flow<AppSettings> = store.data.map { preferences ->
        AppSettings(
            deviceName = preferences[DEVICE_NAME] ?: "UniShare",
            receiveTreeUri = preferences[RECEIVE_TREE_URI],
            receiveDirectoryDisplayName = preferences[RECEIVE_DIRECTORY_DISPLAY_NAME],
            backgroundReceiveEnabled = preferences[BACKGROUND_RECEIVE] ?: false,
            notificationsEnabled = preferences[NOTIFICATIONS] ?: true,
            language = when (preferences[LANGUAGE]) {
                "ENGLISH" -> AppLanguage.ENGLISH
                "SIMPLIFIED_CHINESE" -> AppLanguage.SIMPLIFIED_CHINESE
                else -> AppLanguage.SYSTEM
            },
            theme = when (preferences[THEME]) {
                "LIGHT" -> AppTheme.LIGHT
                "DARK" -> AppTheme.DARK
                else -> AppTheme.SYSTEM
            },
        )
    }

    override suspend fun setDeviceName(name: String) {
        val normalized = name.trim()
        require(normalized.isNotBlank()) { "Device name must not be blank" }
        store.edit { it[DEVICE_NAME] = normalized }
    }

    override suspend fun setReceiveDirectory(treeUri: String?, displayName: String?) {
        val sanitizedName = displayName?.replace('\\', '/')?.substringAfterLast('/')
            ?.filterNot { it.isISOControl() }?.trim()?.takeIf { it.isNotBlank() }
        store.edit {
            if (treeUri == null) {
                it.remove(RECEIVE_TREE_URI)
                it.remove(RECEIVE_DIRECTORY_DISPLAY_NAME)
            } else {
                it[RECEIVE_TREE_URI] = treeUri
                if (sanitizedName == null) it.remove(RECEIVE_DIRECTORY_DISPLAY_NAME)
                else it[RECEIVE_DIRECTORY_DISPLAY_NAME] = sanitizedName
            }
        }
    }

    override suspend fun setBackgroundReceiveEnabled(enabled: Boolean) {
        store.edit { it[BACKGROUND_RECEIVE] = enabled }
    }
    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        store.edit { it[NOTIFICATIONS] = enabled }
    }
    override suspend fun setLanguage(language: AppLanguage) {
        store.edit { it[LANGUAGE] = language.name }
    }
    override suspend fun setTheme(theme: AppTheme) {
        store.edit { it[THEME] = theme.name }
    }

    private companion object {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val RECEIVE_TREE_URI = stringPreferencesKey("receive_tree_uri")
        val RECEIVE_DIRECTORY_DISPLAY_NAME = stringPreferencesKey("receive_directory_display_name")
        val BACKGROUND_RECEIVE = booleanPreferencesKey("background_receive")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME = stringPreferencesKey("theme")
    }
}
