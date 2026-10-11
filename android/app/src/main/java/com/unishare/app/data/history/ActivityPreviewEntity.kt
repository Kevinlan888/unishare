package com.unishare.app.data.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ActivityPreview

/** Payloads are separate so history metadata queries never load message bodies or thumbnails. */
@Entity(
    tableName = "activity_previews",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ActivityPreviewEntity(
    @PrimaryKey val activityId: String,
    val textBody: String? = null,
    val imagePreviewBytes: ByteArray? = null,
) {
    override fun toString(): String =
        "ActivityPreviewEntity(activityId=$activityId, hasTextBody=${textBody != null}, imageBytes=${imagePreviewBytes?.size ?: 0})"

    fun toDomain(): ActivityPreview? = when {
        textBody != null && imagePreviewBytes == null -> ActivityPreview.Text(textBody)
        textBody == null && imagePreviewBytes != null -> ActivityPreview.Image("", imagePreviewBytes)
        else -> null
    }

    companion object {
        fun fromDomain(activityId: ActivityKey, preview: ActivityPreview): ActivityPreviewEntity? = when (preview) {
            is ActivityPreview.Text -> ActivityPreviewEntity(activityId.value, textBody = preview.text)
            is ActivityPreview.Image -> preview.encodedBytes?.let {
                ActivityPreviewEntity(activityId.value, imagePreviewBytes = it)
            }
        }
    }
}
