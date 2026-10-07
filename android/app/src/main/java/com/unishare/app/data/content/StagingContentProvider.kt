package com.unishare.app.data.content

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/** There is no query, insert, update, or enumeration API, even for granted callers. */
class StagingContentProvider : ContentProvider() {
    private lateinit var store: StagingStore

    override fun onCreate(): Boolean {
        val context = context ?: return false
        store = StagingStore(context)
        AndroidReceivePublisher(context).cleanupAfterRestart()
        store.cleanupAfterRestart()
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
            "w", "wt", "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw FileNotFoundException("Unsupported private destination mode")
        }
        return ParcelFileDescriptor.open(store.resolve(uri), flags)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (selection != null || selectionArgs != null) return 0
        return store.deleteUri(uri)
    }

    override fun getType(uri: Uri): String {
        store.resolve(uri)
        return "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        throw UnsupportedOperationException("Private destinations cannot be enumerated")
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Private destinations cannot be inserted")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Private destinations cannot be updated")
}
