package com.unishare.app.presentation.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.unishare.app.R
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.sanitizedDisplayName

/** Text has no filename; nameless image/file records use a localized semantic label.
 * A genuine filename, including literal "File" or "Image", is never translated.
 */
@Composable
fun activityDisplayName(kind: ContentKind, rawName: String): String {
    if (kind == ContentKind.TEXT) return stringResource(R.string.session_text)
    return sanitizedDisplayName(rawName).takeUnless { it.isBlank() || it == "." || it == ".." }
        ?: stringResource(if (kind == ContentKind.IMAGE) R.string.summary_image else R.string.session_file)
}
