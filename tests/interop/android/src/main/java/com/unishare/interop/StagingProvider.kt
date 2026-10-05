package com.unishare.interop

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

class HostActivity : Activity()

/** Test host owns its staging files; remote display names never become paths. */
class StagingProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val name = requireNotNull(uri.lastPathSegment)
        require(name.matches(Regex("[A-Za-z0-9.-]+")) && name != "." && name != "..")
        return File(requireNotNull(context).cacheDir, name)
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = if (file(uri).delete()) 1 else 0
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
