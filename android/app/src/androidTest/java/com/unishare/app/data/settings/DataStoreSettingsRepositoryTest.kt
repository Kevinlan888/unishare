package com.unishare.app.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataStoreSettingsRepositoryTest {
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DataStoreSettingsRepository

    @Before fun setUp() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString())
        check(directory.mkdirs())
        openRepository()
    }

    @After fun tearDown() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        directory.deleteRecursively()
        Unit
    }

    @Test fun defaultsDisableBackgroundReceiveAndUseSystemAppearance() = runBlocking {
        val settings = repository.settings.first()
        assertFalse(settings.backgroundReceiveEnabled)
        assertEquals(AppLanguage.SYSTEM, settings.language)
        assertEquals(AppTheme.SYSTEM, settings.theme)
        assertNull(settings.receiveTreeUri)
        assertNull(settings.receiveDirectoryDisplayName)
    }

    @Test fun everySetterSurvivesRepositoryAndDataStoreRecreation() = runBlocking {
        repository.setDeviceName("  Laptop  ")
        repository.setReceiveDirectory("content://provider/tree/1", "C:\\private\\Transfers\n")
        repository.setBackgroundReceiveEnabled(true)
        repository.setNotificationsEnabled(false)
        repository.setLanguage(AppLanguage.SIMPLIFIED_CHINESE)
        repository.setTheme(AppTheme.DARK)
        recreate()
        val actual = repository.settings.first()
        assertEquals("Laptop", actual.deviceName)
        assertEquals("content://provider/tree/1", actual.receiveTreeUri)
        assertEquals("Transfers", actual.receiveDirectoryDisplayName)
        assertTrue(actual.backgroundReceiveEnabled)
        assertFalse(actual.notificationsEnabled)
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, actual.language)
        assertEquals(AppTheme.DARK, actual.theme)
    }

    @Test fun clearingDirectoryClearsBothFieldsTransactionally() = runBlocking {
        repository.setReceiveDirectory("content://provider/tree/1", "folder")
        repository.setReceiveDirectory(null, "ignored")
        recreate()
        assertNull(repository.settings.first().receiveTreeUri)
        assertNull(repository.settings.first().receiveDirectoryDisplayName)
    }

    @Test fun blankNameIsRejectedWithoutChangingStoredName() = runBlocking {
        repository.setDeviceName("Laptop")
        try {
            repository.setDeviceName(" \n\t ")
            fail("Blank names must be rejected")
        } catch (_: IllegalArgumentException) { }
        recreate()
        assertEquals("Laptop", repository.settings.first().deviceName)
    }

    @Test fun directoryDisplayNameStripsPathAndControlCharacters() = runBlocking {
        repository.setReceiveDirectory("content://provider/tree/1", "/private/Folder\u0000\t")
        assertEquals("Folder", repository.settings.first().receiveDirectoryDisplayName)
    }

    private suspend fun recreate() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        openRepository()
    }

    private fun openRepository() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "settings.preferences_pb")
        })
    }
}
