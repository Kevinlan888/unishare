package com.unishare.app.data.content

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID

/** Bounded copying with durable private recovery intents for pending MediaStore Downloads. */
class AndroidReceivePublisher(
    context: Context,
    private val resolver: ContentResolver = context.contentResolver,
    private val journalDirectory: File = File(context.filesDir, "receive-publications"),
) : ReceivePublisher {
    private val store = StagingStore(context)

    override fun publish(staging: PreparedStaging, displayName: String, mimeType: String, destination: ReceiveDestination): Uri = synchronized(lock) {
        // Generic SAF has neither hidden pending documents nor a provably atomic publication API.
        // Reject before touching its provider, even when it offers document creation or rename.
        if (destination is ReceiveDestination.SafTree) {
            throw IOException("Receive directory cannot guarantee private atomic publication")
        }
        store.validate(staging)
        val name = safeName(displayName)
        val mime = mimeType.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT < 29) throw IOException("Safe Downloads publication requires Android 10 or later")
        val container = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val finalName = uniqueName(name, container)
        val token = UUID.randomUUID().toString()
        val temporaryName = "unishare-$token.partial"
        val journal = File(journalDirectory, "$token.properties")
        val record = Properties().apply {
            setProperty("kind", "downloads")
            setProperty("container", container.toString())
            setProperty("temporaryName", temporaryName)
        }
        writeJournal(journal, record)
        var target: Uri? = null
        try {
            target = resolver.insert(container, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, temporaryName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, downloadsPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw IOException("Could not prepare receive destination")
            record.setProperty("target", target.toString())
            writeJournal(journal, record)
            copyAndSync(staging.file, target)
            val updated = resolver.update(target, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            if (updated != 1) throw IOException("Could not commit receive destination")
            // Only full, synced content can reach this point. No journal is needed for a committed file.
            AtomicFile(journal).delete()
            store.deleteUri(staging.uri)
            target
        } catch (failure: Exception) {
            // Errors are deliberately not caught: real process death leaves the durable intent for startup.
            if (removeUnfinished(record, target)) AtomicFile(journal).delete()
            throw IOException("Receive publication failed", failure)
        }
    }

    /** Failed cleanup keeps its intent for the next startup (e.g. unavailable external storage). */
    fun cleanupAfterRestart() = synchronized(lock) {
        journalDirectory.listFiles().orEmpty().filter { it.name.endsWith(".properties") || it.name.endsWith(".properties.bak") }
            .map { File(journalDirectory, it.name.removeSuffix(".bak")) }.distinct().forEach { journal ->
                try {
                    val record = Properties().apply { AtomicFile(journal).openRead().use { load(it) } }
                    val target = record.getProperty("target")?.let(Uri::parse)
                    if (removeUnfinished(record, target)) AtomicFile(journal).delete()
                } catch (_: Exception) { /* Keep recovery metadata; never log content identifiers. */ }
            }
        Unit
    }

    private fun copyAndSync(source: File, target: Uri) {
        val descriptor = resolver.openFileDescriptor(target, "w") ?: throw IOException("Receive destination unavailable")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
            output.fd.sync()
        }
    }

    private fun uniqueName(name: String, container: Uri): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val extension = name.substring(dot)
        for (index in 0..10_000) {
            val candidate = if (index == 0) name else "$base ($index)$extension"
            if (!hasName(candidate, container)) return candidate
        }
        throw IOException("Receive directory has too many name collisions")
    }

    private fun hasName(name: String, container: Uri): Boolean = resolver.query(container, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(name, downloadsPath), null)
            ?.use { it.moveToFirst() } ?: throw IOException("Receive directory unavailable")

    private fun removeUnfinished(record: Properties, target: Uri?): Boolean {
        return try {
            // Only pending Downloads are supported; never replay document operations from legacy intents.
            if (record.getProperty("kind") != "downloads") return false
            val container = Uri.parse(record.getProperty("container") ?: return false)
            val name = record.getProperty("temporaryName") ?: return false
            if (target != null) resolver.delete(target, null, null)
            // Intent is written before insert, so this also handles death before insert returns its URI.
            queryPending(container, name)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (resolver.delete(ContentUris.withAppendedId(container, cursor.getLong(0)), null, null) != 1) return false
                }
            } ?: return false
            true
        } catch (_: Exception) { false }
    }

    private fun writeJournal(file: File, record: Properties) {
        if (!journalDirectory.isDirectory && !journalDirectory.mkdirs()) throw IOException("Private recovery storage unavailable")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            record.store(output, null)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw IOException("Could not prepare publication recovery", failure)
        }
    }

    @Suppress("DEPRECATION") // API29 predates QUERY_ARG_MATCH_PENDING; use its public compatibility API.
    private fun queryPending(container: Uri, name: String): Cursor? {
        // Recovery may encounter a journal on older OS versions. Keep the intent
        // for later recovery rather than resolving API29-only pending Downloads.
        if (Build.VERSION.SDK_INT < 29) return null
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val arguments = arrayOf("$name%", downloadsPath)
        return if (Build.VERSION.SDK_INT >= 30) resolver.query(container, arrayOf(MediaStore.MediaColumns._ID), Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arguments)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }, null)
        else resolver.query(MediaStore.setIncludePending(container), arrayOf(MediaStore.MediaColumns._ID), selection, arguments, null)
    }

    private fun safeName(value: String): String = value.replace('\\', '/').substringAfterLast('/')
        .filterNot { it.isISOControl() }.trim().take(180).takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "received"

    private companion object {
        val lock = Any()
        val downloadsPath = "${Environment.DIRECTORY_DOWNLOADS}/UniShare/"
    }
}
