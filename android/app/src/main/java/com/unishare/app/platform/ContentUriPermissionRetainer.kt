package com.unishare.app.platform

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.unishare.app.data.transfer.ContentPermissionException
import com.unishare.app.data.transfer.ContentReadPermissionLease
import com.unishare.app.data.transfer.ContentUriPermissionRetainer
import com.unishare.app.data.transfer.RetainedContentUri
import com.unishare.app.domain.ShareErrorKind
import java.util.concurrent.atomic.AtomicBoolean

internal interface PersistableUriPermissionAccess {
    fun hasPersistedRead(uri: Uri): Boolean
    fun takePersistableRead(uri: Uri)
    fun releasePersistableRead(uri: Uri)
}

private class ResolverPersistableUriPermissionAccess(
    private val resolver: ContentResolver,
) : PersistableUriPermissionAccess {
    override fun hasPersistedRead(uri: Uri): Boolean = resolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }

    override fun takePersistableRead(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun releasePersistableRead(uri: Uri) {
        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** Process owner for ref-counted persistable read grants obtained from picker results. */
class AndroidContentUriPermissionRetainer internal constructor(
    private val access: PersistableUriPermissionAccess,
) : ContentUriPermissionRetainer {
    constructor(contentResolver: ContentResolver) : this(ResolverPersistableUriPermissionAccess(contentResolver))

    private data class Grant(val uri: Uri, var references: Int)

    private val gate = Any()
    private val grants = HashMap<String, Grant>()

    override fun retain(uri: Uri): RetainedContentUri {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw ContentPermissionException(ShareErrorKind.INVALID_INPUT)
        }
        val key = uri.toString()
        synchronized(gate) {
            grants[key]?.let { grant ->
                grant.references++
                return retained(uri, key)
            }
            val alreadyPersisted = platformCall { access.hasPersistedRead(uri) }
            if (!alreadyPersisted) platformCall { access.takePersistableRead(uri) }
            // Persisted URI grants are app-scoped. Adopt a grant left by an earlier
            // process so this lease can deterministically retire it instead of leaking it.
            grants[key] = Grant(uri = uri, references = 1)
            return retained(uri, key)
        }
    }

    private fun retained(uri: Uri, key: String): RetainedContentUri {
        val closed = AtomicBoolean(false)
        return RetainedContentUri(uri, ContentReadPermissionLease {
            if (closed.compareAndSet(false, true)) release(key)
        })
    }

    private fun release(key: String) = synchronized(gate) {
        val grant = grants[key] ?: return@synchronized
        grant.references--
        if (grant.references > 0) return@synchronized
        grants.remove(key)
        try {
            access.releasePersistableRead(grant.uri)
        } catch (_: RuntimeException) {
            // The provider can disappear after the transfer; the in-process lease is retired.
        }
    }

    private fun <T> platformCall(block: () -> T): T = try {
        block()
    } catch (_: SecurityException) {
        throw ContentPermissionException(ShareErrorKind.PERMISSION_DENIED)
    } catch (_: UnsupportedOperationException) {
        throw ContentPermissionException(ShareErrorKind.PERMISSION_DENIED)
    } catch (_: IllegalArgumentException) {
        throw ContentPermissionException(ShareErrorKind.INVALID_INPUT)
    }
}
