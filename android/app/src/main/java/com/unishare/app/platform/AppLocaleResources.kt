package com.unishare.app.platform

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.StringRes
import com.unishare.app.data.settings.AppLanguage
import com.unishare.app.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** One app-language policy for Compose, notifications, channels and transient messages.
 * Configuration contexts are private copies; the device/system locale is never changed.
 */
class AppLocaleResources(private val application: Context, sourceSettings: Flow<AppSettings>, scope: CoroutineScope) {
    private val currentSettings = MutableStateFlow<AppSettings?>(null)
    val settings: Flow<AppSettings> = currentSettings.filterNotNull()
    val language: Flow<AppLanguage> = settings.map { it.language }.distinctUntilChanged()
    val isReady: Boolean get() = currentSettings.value != null
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sourceSettings.collect { currentSettings.value = it }
        }
    }
    suspend fun awaitReady(): AppLanguage = language.first()
    fun configuration(base: Configuration, language: AppLanguage = currentSettings.value?.language ?: AppLanguage.SYSTEM): Configuration =
        Configuration(base).apply {
            when (language) {
                AppLanguage.ENGLISH -> setLocales(LocaleList.forLanguageTags("en"))
                AppLanguage.SIMPLIFIED_CHINESE -> setLocales(LocaleList.forLanguageTags("zh-CN"))
                AppLanguage.SYSTEM -> Unit
            }
        }
    fun context(base: Context = application): Context = base.createConfigurationContext(configuration(base.resources.configuration))
    val resources: Resources get() = context().resources
    fun string(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)
}
