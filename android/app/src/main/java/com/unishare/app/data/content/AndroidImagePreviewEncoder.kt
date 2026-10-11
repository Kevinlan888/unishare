package com.unishare.app.data.content

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Encodes a small private thumbnail; the user's selected URI is never persisted. */
class AndroidImagePreviewEncoder(private val resolver: ContentResolver) {
    fun encode(uri: Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (maxOf(bounds.outWidth / (sampleSize * 2), bounds.outHeight / (sampleSize * 2)) >= MAX_DIMENSION) {
                sampleSize *= 2
            }
            var bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            } ?: return null

            try {
                val longestEdge = maxOf(bitmap.width, bitmap.height)
                if (longestEdge > MAX_DIMENSION) {
                    val scale = MAX_DIMENSION.toFloat() / longestEdge
                    val resized = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                    if (resized !== bitmap) bitmap.recycle()
                    bitmap = resized
                }
                repeat(MAX_RESIZE_ATTEMPTS + 1) { attempt ->
                    val output = ByteArrayOutputStream()
                    val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    val quality = if (format == Bitmap.CompressFormat.JPEG) 82 else 100
                    if (bitmap.compress(format, quality, output) && output.size() <= MAX_BYTES) return output.toByteArray()
                    if (attempt < MAX_RESIZE_ATTEMPTS) {
                        val resized = Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width / 2).coerceAtLeast(1),
                            (bitmap.height / 2).coerceAtLeast(1),
                            true,
                        )
                        if (resized !== bitmap) bitmap.recycle()
                        bitmap = resized
                    }
                }
                null
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_DIMENSION = 512
        const val MAX_BYTES = 256 * 1024
        const val MAX_RESIZE_ATTEMPTS = 4
    }
}
