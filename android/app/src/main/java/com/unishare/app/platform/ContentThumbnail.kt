package com.unishare.app.platform

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ContentUriThumbnail(
    reference: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, reference, resolver) {
        value = withContext(Dispatchers.IO) {
            loadThumbnail(resolver, Uri.parse(reference), TARGET_WIDTH, TARGET_HEIGHT)?.asImageBitmap()
        }
    }
    val image = thumbnail
    if (image == null) {
        fallback()
    } else {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

internal fun calculateThumbnailSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    while (
        sourceWidth / (sample * 2) >= targetWidth &&
        sourceHeight / (sample * 2) >= targetHeight
    ) {
        sample *= 2
    }
    return sample
}

private fun loadThumbnail(
    resolver: ContentResolver,
    uri: Uri,
    targetWidth: Int,
    targetHeight: Int,
) = run {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateThumbnailSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    targetWidth,
                    targetHeight,
                )
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (_: Exception) {
        null
    }
}

private const val TARGET_WIDTH = 1_000
private const val TARGET_HEIGHT = 500
