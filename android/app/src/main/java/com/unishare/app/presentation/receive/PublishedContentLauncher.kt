package com.unishare.app.presentation.receive

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/** Launches directly because package visibility may hide an otherwise launchable viewer. */
internal fun launchPublishedContent(
    rawUri: String,
    mimeType: String,
    onUnavailable: () -> Unit = {},
    launch: (Intent) -> Unit,
): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(rawUri), mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        launch(intent)
        true
    } catch (_: ActivityNotFoundException) {
        onUnavailable()
        false
    } catch (_: SecurityException) {
        onUnavailable()
        false
    }
}
