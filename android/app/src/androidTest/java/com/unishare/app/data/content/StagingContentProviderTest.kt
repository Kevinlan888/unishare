package com.unishare.app.data.content

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.unishare.app.domain.TransferKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StagingContentProviderTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = StagingStore(context)
    private val keys = mutableListOf<TransferKey>()

    @After fun cleanUp() { keys.forEach(store::delete) }

    @Test fun generatedTokenOpensARegularReadWriteFileAndTruncates() {
        val staging = create()
        assertTrue(staging.file.isFile)
        assertEquals("${context.packageName}.staging", staging.uri.authority)
        assertFalse(staging.uri.toString().contains(staging.transferId.value))
        context.contentResolver.openFileDescriptor(staging.uri, "rwt")!!.use { fd ->
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use {
                it.write(byteArrayOf(1, 2, 3))
            }
        }
        context.contentResolver.openFileDescriptor(staging.uri, "rw")!!.use { fd ->
            assertEquals(3L, fd.statSize)
        }
        context.contentResolver.openFileDescriptor(staging.uri, "rwt")!!.close()
        assertEquals(0L, staging.file.length())
    }

    @Test fun fabricatedTokensTraversalAndAdditionalSegmentsCannotOpen() {
        val staging = create()
        listOf(
            Uri.parse("content://${context.packageName}.staging/${UUID.randomUUID()}"),
            Uri.parse("content://${context.packageName}.staging/../secret"),
            staging.uri.buildUpon().appendPath("extra").build(),
            staging.uri.buildUpon().encodedPath("/%2e%2e%2fsecret").build(),
            staging.uri.buildUpon().appendQueryParameter("path", "secret").build(),
        ).forEach { uri ->
            try { context.contentResolver.openFileDescriptor(uri, "rwt"); fail("Invalid token opened") }
            catch (_: FileNotFoundException) { }
        }
    }

    @Test fun resolverDeletionReturnsOneOnceAndZeroThereafter() {
        val staging = create()
        assertEquals(1, context.contentResolver.delete(staging.uri, null, null))
        assertFalse(staging.file.exists())
        assertEquals(0, context.contentResolver.delete(staging.uri, null, null))
    }

    @Test fun providerDoesNotExposeAnEnumerationSurfaceOrExportItself() {
        create()
        val info = context.packageManager.resolveContentProvider("${context.packageName}.staging", 0)!!
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        try {
            context.contentResolver.query(Uri.parse("content://${info.authority}"), null, null, null, null)
            fail("Enumeration must be denied")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun expiryUsesCreationTimeEvenAfterWritingAndExplicitDiscardRemovesSource() {
        val old = create()
        old.file.writeBytes(byteArrayOf(42))
        store.cleanupOrphans(old.createdAtMillis + 599_999)
        assertTrue(old.file.exists())
        store.cleanupOrphans(old.createdAtMillis + 600_000)
        assertFalse(old.file.exists())
        val discarded = create()
        store.delete(discarded.transferId)
        assertFalse(discarded.file.exists())
    }

    @Test fun restartCleanupDiscardsPreviousProcessFiles() {
        val staging = create()
        StagingStore(context).cleanupAfterRestart()
        assertFalse(staging.file.exists())
    }

    @Test @SdkSuppress(maxSdkVersion = 28)
    fun oldAndroidRequiresSafInsteadOfExposingANonAtomicDownloadsFile() {
        val staging = create()
        try {
            AndroidReceivePublisher(context).publish(staging, "report.txt", "text/plain", ReceiveDestination.Downloads)
            fail("Legacy Android must require a receive directory")
        } catch (_: IOException) { }
        assertTrue(staging.file.exists())
    }

    private fun create(): PreparedStaging {
        val key = TransferKey("transfer-${UUID.randomUUID()}")
        keys += key
        return store.create(key)
    }
}
