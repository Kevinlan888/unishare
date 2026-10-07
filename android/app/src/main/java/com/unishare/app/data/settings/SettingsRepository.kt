package com.unishare.app.data.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setDeviceName(name: String)
    suspend fun setReceiveDirectory(treeUri: String?, displayName: String?)
    suspend fun setBackgroundReceiveEnabled(enabled: Boolean)
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setTheme(theme: AppTheme)
}
