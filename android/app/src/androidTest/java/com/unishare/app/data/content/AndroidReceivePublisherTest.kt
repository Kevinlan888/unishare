package com.unishare.app.data.content

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.unishare.app.domain.TransferKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AndroidReceivePublisherTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = StagingStore(context)
    private val directory = File(context.cacheDir, "publisher-${UUID.randomUUID()}").apply { mkdirs() }
    private val destination = FileDestinationProvider(directory).apply {
        attachInfo(context, ProviderInfo().apply { authority = "media;test.documents" })
    }
    private val resolver = ContentResolver.wrap(destination)
    private val journalDirectory = File(directory, "journal")
    private val publisher = AndroidReceivePublisher(context, resolver, journalDirectory)
    private val keys = mutableListOf<TransferKey>()

    @After fun cleanUp() {
        keys.forEach(store::delete)
        directory.deleteRecursively()
    }

    @Test fun downloadsPublishesDurablyBeforeDeletingPrivateSourceAndResolvesCollisions() {
        destination.addExisting("report.txt")
        val staging = source()
        destination.onCommit = { assertTrue(staging.file.exists()) }
        val uri = publisher.publish(staging, "report.txt", "text/plain", ReceiveDestination.Downloads)
        assertFalse(staging.file.exists())
        assertEquals("report (1).txt", destination.names[uri])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), destination.files.getValue(uri).readBytes())
        assertEquals(0, destination.pending[uri])
        assertTrue(journalDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test fun downloadsFailureDeletesPendingRowAndKeepsSource() {
        val staging = source()
        destination.failOpen = true
        try { publisher.publish(staging, "report.txt", "text/plain", ReceiveDestination.Downloads); fail() }
        catch (_: IOException) { }
        assertTrue(staging.file.exists())
        assertTrue(destination.files.isEmpty())
    }

    @Test fun commitFailureRemovesCopiedTargetAndPreservesPrivateSource() {
        val staging = source()
        destination.failCommit = true
        try { publisher.publish(staging, "report.txt", "text/plain", ReceiveDestination.Downloads); fail() }
        catch (_: IOException) { }
        assertTrue(staging.file.exists())
        assertTrue(destination.files.isEmpty())
        assertTrue(journalDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test fun syncFailureForEitherBackendRemovesTargetAndKeepsPrivateSource() {
        destination.unsyncableTarget = true
        listOf(ReceiveDestination.Downloads, ReceiveDestination.SafTree(Uri.parse("content://test.documents/tree/root"))).forEach { target ->
            val staging = source()
            try { publisher.publish(staging, "report.txt", "text/plain", target); fail("Unsynced content must not publish") }
            catch (_: IOException) { }
            assertTrue(staging.file.exists())
            assertTrue(destination.files.isEmpty())
        }
    }

    @Test fun safPublishesByRenameAndDeletesTemporaryDocumentOnFailure() {
        val tree = Uri.parse("content://test.documents/tree/root")
        destination.addExisting("report.txt")
        val staging = source()
        destination.onCommit = { assertTrue(staging.file.exists()) }
        val uri = publisher.publish(staging, "report.txt", "text/plain", ReceiveDestination.SafTree(tree))
        assertEquals("report (1).txt", destination.names[uri])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), destination.files.getValue(uri).readBytes())
        assertFalse(staging.file.exists())
        val failed = source()
        destination.failOpen = true
        try { publisher.publish(failed, "failed.txt", "text/plain", ReceiveDestination.SafTree(tree)); fail() }
        catch (_: IOException) { }
        assertTrue(failed.file.exists())
        assertFalse(destination.names.values.any { it.endsWith(".partial") || it == "failed.txt" })
    }

    @Test fun startupRecoveryRemovesHalfPublicationFromSimulatedProcessDeath() {
        val staging = source()
        destination.failOpenWithProcessDeath = true
        try { publisher.publish(staging, "report.txt", "text/plain", ReceiveDestination.Downloads); fail() }
        catch (_: SimulatedProcessDeath) { }
        assertTrue(destination.files.isNotEmpty())
        AndroidReceivePublisher(context, resolver, journalDirectory).cleanupAfterRestart()
        StagingStore(context).cleanupAfterRestart()
        assertTrue(destination.files.isEmpty())
        assertFalse(staging.file.exists())
    }

    @Test fun safStartupRecoveryRemovesUnrenamedTemporaryDocument() {
        destination.failOpenWithProcessDeath = true
        val tree = Uri.parse("content://test.documents/tree/root")
        try { publisher.publish(source(), "report.txt", "text/plain", ReceiveDestination.SafTree(tree)); fail() }
        catch (_: SimulatedProcessDeath) { }
        AndroidReceivePublisher(context, resolver, journalDirectory).cleanupAfterRestart()
        assertTrue(destination.files.isEmpty())
    }

    @Test fun recoveryFindsCreatedRowWhenProcessDiesBeforeInsertReturnsUri() {
        destination.dieAfterCreate = true
        destination.appendTemporaryExtension = true
        try { publisher.publish(source(), "report.txt", "text/plain", ReceiveDestination.Downloads); fail() }
        catch (_: SimulatedProcessDeath) { }
        assertTrue(destination.files.isNotEmpty())
        AndroidReceivePublisher(context, resolver, journalDirectory).cleanupAfterRestart()
        assertTrue(destination.files.isEmpty())
    }

    @Test fun safRecoveryFindsProviderExtendedNameBeforeCreateReturnsUri() {
        destination.dieAfterCreate = true
        destination.appendTemporaryExtension = true
        val tree = Uri.parse("content://test.documents/tree/root")
        try { publisher.publish(source(), "report.txt", "text/plain", ReceiveDestination.SafTree(tree)); fail() }
        catch (_: SimulatedProcessDeath) { }
        AndroidReceivePublisher(context, resolver, journalDirectory).cleanupAfterRestart()
        assertTrue(destination.files.isEmpty())
    }

    @Test fun pathLikeDisplayNameCannotEscapeDestination() {
        val uri = publisher.publish(source(), "../folder/report.txt", "text/plain", ReceiveDestination.Downloads)
        assertEquals("report.txt", destination.names[uri])
    }

    private fun source(): PreparedStaging {
        val key = TransferKey(UUID.randomUUID().toString())
        keys += key
        return store.create(key).also { it.file.writeBytes(byteArrayOf(1, 2, 3, 4)) }
    }
}

private class SimulatedProcessDeath : Error()

/** Real file descriptors model the Android publication boundary without requiring a user's tree. */
private class FileDestinationProvider(private val directory: File) : ContentProvider() {
    val files = linkedMapOf<Uri, File>()
    val names = linkedMapOf<Uri, String>()
    val pending = linkedMapOf<Uri, Int>()
    var failOpen = false
    var failOpenWithProcessDeath = false
    var failCommit = false
    var dieAfterCreate = false
    var appendTemporaryExtension = false
    var unsyncableTarget = false
    private var nextId = 1L
    var onCommit: (() -> Unit)? = null
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri = create(uri.authority!!, values!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)).also {
        pending[it] = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
        if (dieAfterCreate) throw SimulatedProcessDeath()
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        queryRows(projection, selection, selectionArgs, uri.getBooleanQueryParameter("includePending", false))
    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: android.os.Bundle?, cancellationSignal: android.os.CancellationSignal?): Cursor =
        queryRows(projection, queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
            queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
            queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_INCLUDE || uri.getBooleanQueryParameter("includePending", false))
    private fun queryRows(projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, includePending: Boolean): Cursor =
        MatrixCursor(projection ?: arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)).apply {
            names.forEach { (uri, name) ->
                if ((includePending || pending[uri] != 1) && (selectionArgs == null || name in selectionArgs ||
                    (selection?.contains("LIKE") == true && name.startsWith(selectionArgs[0].removeSuffix("%"))))) addRow(columnNames.map {
                    when (it) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID, MediaStore.MediaColumns._ID -> uri.lastPathSegment
                        else -> name
                    }
                })
            }
        }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (failCommit) return 0
        onCommit?.invoke()
        pending[uri] = values!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
        values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)?.let { names[uri] = it }
        return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        names.remove(uri); pending.remove(uri)
        return if (files.remove(uri)?.delete() == true) 1 else 0
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failOpenWithProcessDeath) throw SimulatedProcessDeath()
        if (failOpen) throw IOException("Injected storage failure")
        if (unsyncableTarget) return ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_WRITE)
        return ParcelFileDescriptor.open(files.getValue(uri), ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
        val result = android.os.Bundle()
        when (method) {
            "android:createDocument" -> {
                val uri = create("test.documents", extras!!.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!)
                if (dieAfterCreate) throw SimulatedProcessDeath()
                result.putParcelable("uri", uri)
            }
            "android:renameDocument" -> {
                val uri = documentUri(extras!!)
                onCommit?.invoke()
                names[uri] = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!
                result.putParcelable("uri", uri)
            }
            "android:deleteDocument" -> delete(documentUri(extras!!), null, null)
            else -> throw UnsupportedOperationException()
        }
        return result
    }
    fun addExisting(name: String) { create("media", name) }
    private fun create(authority: String, name: String): Uri = (if (authority == "media")
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).buildUpon().appendPath((nextId++).toString()).build()
        else DocumentsContract.buildDocumentUriUsingTree(Uri.parse("content://$authority/tree/root"), UUID.randomUUID().toString())).also {
        files[it] = File(directory, it.lastPathSegment!!).apply { createNewFile() }
        names[it] = if (appendTemporaryExtension && name.startsWith("unishare-")) "$name.txt" else name
    }

    @Suppress("DEPRECATION") // Instrumentation also runs on API29–32, before the typed overload exists.
    private fun documentUri(bundle: android.os.Bundle): Uri = if (android.os.Build.VERSION.SDK_INT >= 33)
        bundle.getParcelable("uri", Uri::class.java)!! else bundle.getParcelable<Uri>("uri")!!
}
