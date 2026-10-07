package com.unishare.app.data.transfer

import android.net.Uri
import com.unishare.app.domain.ShareErrorKind

fun interface ContentReadPermissionLease : AutoCloseable {
    override fun close()
}

data class RetainedContentUri(
    val uri: Uri,
    val lease: ContentReadPermissionLease,
)

fun interface ContentUriPermissionRetainer {
    @Throws(ContentPermissionException::class)
    fun retain(uri: Uri): RetainedContentUri
}

class ContentPermissionException(val errorKind: ShareErrorKind) : RuntimeException(errorKind.name)
