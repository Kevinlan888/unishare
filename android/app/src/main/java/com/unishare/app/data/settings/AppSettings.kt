package com.unishare.app.data.settings

enum class AppLanguage { SYSTEM, ENGLISH, SIMPLIFIED_CHINESE }
enum class AppTheme { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val deviceName: String = "UniShare",
    val receiveTreeUri: String? = null,
    val receiveDirectoryDisplayName: String? = null,
    val backgroundReceiveEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val theme: AppTheme = AppTheme.SYSTEM,
)
