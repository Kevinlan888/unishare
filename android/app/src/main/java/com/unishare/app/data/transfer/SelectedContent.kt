package com.unishare.app.data.transfer

import android.net.Uri
import com.unishare.app.domain.ContentKind

/** Process-only metadata for a user-selected content URI. The URI is never persisted. */
data class SelectedContent(
    val uri: Uri,
    val kind: ContentKind,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val readPermission: ContentReadPermissionLease? = null,
)
