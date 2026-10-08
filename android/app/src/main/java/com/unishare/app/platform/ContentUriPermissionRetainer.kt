package com.unishare.app.platform

import android.content.ContentResolver
import android.content.Context
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
    fun hasPersistedRead(rawUri: String) = hasPersistedRead(Uri.parse(rawUri))
    fun takePersistableRead(uri: Uri)
    fun releasePersistableRead(uri: Uri)
    fun releasePersistableRead(rawUri: String) = releasePersistableRead(Uri.parse(rawUri))
}

/** App-private crash journal containing only outgoing picker URI grant ownership. */
internal interface ContentUriGrantJournal {
    fun recordedUris(): Set<String>
    fun record(uri: String)
    fun remove(uri: String)
}

private object EmptyContentUriGrantJournal : ContentUriGrantJournal {
    override fun recordedUris() = emptySet<String>()
    override fun record(uri: String) = Unit
    override fun remove(uri: String) = Unit
}

private class SharedPreferencesContentUriGrantJournal(context: Context) : ContentUriGrantJournal {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gate = Any()

    override fun recordedUris(): Set<String> = synchronized(gate) {
        preferences.getStringSet(KEY_URIS, emptySet()).orEmpty().toSet()
    }

    override fun record(uri: String) = update { it + uri }

    override fun remove(uri: String) = update { it - uri }

    private fun update(transform: (Set<String>) -> Set<String>) = synchronized(gate) {
        val current = preferences.getStringSet(KEY_URIS, emptySet()).orEmpty().toSet()
        check(preferences.edit().putStringSet(KEY_URIS, transform(current)).commit()) {
            "Unable to persist outgoing content URI grant journal"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "outgoing_content_uri_grants"
        const val KEY_URIS = "owned_content_uris"
    }
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
    private val journal: ContentUriGrantJournal = EmptyContentUriGrantJournal,
) : ContentUriPermissionRetainer {
    constructor(context: Context) : this(
        ResolverPersistableUriPermissionAccess(context.contentResolver),
        SharedPreferencesContentUriGrantJournal(context),
    )

    private data class Grant(val uri: Uri, var references: Int)

    private val gate = Any()
    private val grants = HashMap<String, Grant>()

    init {
        cleanRecordedOrphans()
    }

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
            try {
                journal.record(key)
            } catch (_: RuntimeException) {
                throw ContentPermissionException(ShareErrorKind.STORAGE_FAILED)
            }
            val alreadyPersisted = try {
                platformCall { access.hasPersistedRead(uri) }
            } catch (failure: ContentPermissionException) {
                forgetJournalEntry(key)
                throw failure
            }
            if (!alreadyPersisted) {
                try {
                    platformCall { access.takePersistableRead(uri) }
                } catch (failure: ContentPermissionException) {
                    forgetJournalEntry(key)
                    throw failure
                }
            }
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
        // Serialize release with a replacement retain for this process. Otherwise a new
        // lease could adopt the still-present grant just before this call revokes it.
        grants.remove(key)
        try {
            access.releasePersistableRead(grant.uri)
        } catch (_: RuntimeException) {
            // Keep the journal entry so the next process can retry deterministic cleanup.
            return@synchronized
        }
        forgetJournalEntry(key)
    }

    private fun cleanRecordedOrphans() {
        val recorded = try {
            journal.recordedUris()
        } catch (_: RuntimeException) {
            return
        }
        recorded.forEach { rawUri ->
            val persisted = try {
                access.hasPersistedRead(rawUri)
            } catch (_: RuntimeException) {
                // The current state is indeterminate; retain the sole cleanup record.
                return@forEach
            }
            if (!persisted) {
                // Covers a crash after journal record or after platform release.
                forgetJournalEntry(rawUri)
                return@forEach
            }
            try {
                access.releasePersistableRead(rawUri)
            } catch (_: RuntimeException) {
                // Keep the record so a later process can retry an indeterminate failure.
                return@forEach
            }
            forgetJournalEntry(rawUri)
        }
    }

    private fun forgetJournalEntry(uri: String) {
        try {
            journal.remove(uri)
        } catch (_: RuntimeException) {
            // A surviving record is safe: startup cleanup is idempotent and will retry it.
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
