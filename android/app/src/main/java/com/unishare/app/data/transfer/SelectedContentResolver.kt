package com.unishare.app.data.transfer

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.ShareErrorKind
import java.util.Collections
import java.util.IdentityHashMap

internal data class RawSelectedContent(
    val displayName: String?,
    val mimeType: String?,
    val byteCount: Long?,
)

internal fun interface SelectedContentMetadataSource {
    fun open(): SelectedContentMetadataSession
}

internal interface SelectedContentMetadataSession : AutoCloseable {
    fun read(uri: Uri): RawSelectedContent
    fun cancel()
}

/** Stable public failure with no provider message, path, URI, or retained cause. */
class SelectedContentException(val errorKind: ShareErrorKind) : RuntimeException(errorKind.name)

/** Reads metadata only. Payload bytes are left for the SDK's streaming URI reader. */
class SelectedContentResolver internal constructor(private val source: SelectedContentMetadataSource) : AutoCloseable {
    constructor(contentResolver: ContentResolver) : this(AndroidSelectedContentMetadataSource(contentResolver))

    private val gate = Any()
    private val active = Collections.newSetFromMap(IdentityHashMap<SelectedContentMetadataSession, Boolean>())
    private var retired = false

    fun resolve(uri: Uri, expected: ContentKind): SelectedContent {
        val session = source.open()
        synchronized(gate) {
            if (retired) {
                session.close()
                throw SelectedContentException(ShareErrorKind.INTERRUPTED)
            }
            active += session
        }
        val raw = try {
            session.read(uri)
        } catch (_: Exception) {
            val kind = synchronized(gate) {
                if (retired) ShareErrorKind.INTERRUPTED else ShareErrorKind.STORAGE_FAILED
            }
            throw SelectedContentException(kind)
        } finally {
            synchronized(gate) { active -= session }
            try { session.close() } catch (_: Exception) { }
        }

        val size = raw.byteCount?.takeIf { it >= 0 }
            ?: throw SelectedContentException(ShareErrorKind.INVALID_INPUT)
        val mime = sanitizeMime(raw.mimeType, expected)
        return SelectedContent(
            uri = uri,
            kind = expected,
            displayName = sanitizeName(raw.displayName, expected),
            mimeType = mime,
            byteCount = size,
        )
    }

    override fun close() {
        val sessions = synchronized(gate) {
            if (retired) return
            retired = true
            active.toList()
        }
        sessions.forEach { try { it.cancel() } catch (_: Exception) { } }
    }

    private fun sanitizeName(value: String?, kind: ContentKind): String {
        val fallback = when (kind) {
            ContentKind.TEXT -> "selected-text"
            ContentKind.IMAGE -> "selected-image"
            ContentKind.FILE -> "selected-file"
        }
        return value.orEmpty().replace('\\', '/').substringAfterLast('/')
            .filterNot(Char::isISOControl).trim().take(180)
            .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: fallback
    }

    private fun sanitizeMime(value: String?, expected: ContentKind): String {
        val mime = value.orEmpty().filterNot(Char::isISOControl).trim().lowercase()
        if (expected == ContentKind.IMAGE && !mime.startsWith("image/")) {
            throw SelectedContentException(ShareErrorKind.INVALID_INPUT)
        }
        return mime.takeIf { it.isNotEmpty() } ?: when (expected) {
            ContentKind.TEXT -> "text/plain"
            ContentKind.IMAGE -> throw SelectedContentException(ShareErrorKind.INVALID_INPUT)
            ContentKind.FILE -> "application/octet-stream"
        }
    }
}

private class AndroidSelectedContentMetadataSource(private val resolver: ContentResolver) : SelectedContentMetadataSource {
    override fun open(): SelectedContentMetadataSession = AndroidSelectedContentMetadataSession(resolver)
}

private class AndroidSelectedContentMetadataSession(private val resolver: ContentResolver) : SelectedContentMetadataSession {
    private val signal = CancellationSignal()

    override fun read(uri: Uri): RawSelectedContent {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, signal)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME)
                size = cursor.longOrNull(OpenableColumns.SIZE)
            }
        }
        signal.throwIfCanceled()
        val mime = resolver.getType(uri)
        signal.throwIfCanceled()
        if (size == null || size!! < 0) {
            size = resolver.openFileDescriptor(uri, "r", signal)?.use { descriptor ->
                signal.throwIfCanceled()
                descriptor.statSize.takeIf { it >= 0 }
            }
        }
        return RawSelectedContent(name, mime, size)
    }

    override fun cancel() = signal.cancel()
    override fun close() = Unit

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }
}
