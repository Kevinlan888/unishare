package com.unishare.app.presentation.session

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unishare.app.R
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ActivityStatus
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.ShareDirection
import com.unishare.app.domain.ShareOrigin
import com.unishare.app.platform.ContentUriThumbnail
import java.util.Date

@Composable
fun ActivityCard(
    activity: SessionActivityUi,
    onCancel: (ActivityKey) -> Unit,
    onRetry: (ActivityKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outgoing = activity.direction == ShareDirection.OUTGOING
    val stateDescription = activityStatusDescription(activity.status)
    val cardModifier = if (activity.kind == ContentKind.TEXT) {
        Modifier.width(IntrinsicSize.Max).widthIn(min = 112.dp, max = 312.dp)
    } else {
        Modifier.fillMaxWidth(0.86f).widthIn(max = 344.dp)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = cardModifier
                .semantics { contentDescription = stateDescription },
            shape = RoundedCornerShape(18.dp),
            color = if (outgoing) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = if (outgoing) 0.dp else 1.dp,
            shadowElevation = 1.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                when (activity.kind) {
                    ContentKind.TEXT -> TextActivityContent(activity)
                    ContentKind.IMAGE -> ImageActivityContent(activity, onCancel, onRetry)
                    ContentKind.FILE -> FileActivityContent(activity, onCancel, onRetry)
                }
                if (activity.kind == ContentKind.TEXT) {
                    Spacer(Modifier.height(7.dp))
                    ActivityMeta(activity, onCancel, onRetry)
                }
            }
        }
    }
}

@Composable
private fun TextActivityContent(activity: SessionActivityUi) {
    val text = activity.text
    if (activity.origin == ShareOrigin.CLIPBOARD) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(
                    stringResource(R.string.session_clipboard),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text ?: stringResource(R.string.session_text_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else if (text?.startsWith("http://", ignoreCase = true) == true ||
        text?.startsWith("https://", ignoreCase = true) == true
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = text,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Text(
            text = text ?: stringResource(R.string.session_text_unavailable),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ImageActivityContent(
    activity: SessionActivityUi,
    onCancel: (ActivityKey) -> Unit,
    onRetry: (ActivityKey) -> Unit,
) {
    val previewModifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1.95f)
        .clip(RoundedCornerShape(12.dp))
    val previewDescription = stringResource(R.string.session_image_preview, activity.displayName)
    val previewBitmap = activity.previewBitmap
    if (previewBitmap != null) {
        Image(
            bitmap = previewBitmap,
            contentDescription = previewDescription,
            contentScale = ContentScale.Crop,
            modifier = previewModifier,
        )
    } else if (activity.previewReference != null) {
        ContentUriThumbnail(
            reference = activity.previewReference,
            contentDescription = previewDescription,
            modifier = previewModifier,
        ) {
            ImagePreviewFallback(previewDescription, previewModifier)
        }
    } else {
        ImagePreviewFallback(previewDescription, previewModifier)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                activity.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatBytes(activity.byteCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = activityTime(activity),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActivityAction(activity, onCancel, onRetry)
    }
}

@Composable
private fun ImagePreviewFallback(contentDescription: String, modifier: Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Image,
            contentDescription = contentDescription,
            modifier = Modifier.size(46.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun FileActivityContent(
    activity: SessionActivityUi,
    onCancel: (ActivityKey) -> Unit,
    onRetry: (ActivityKey) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.75f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                activity.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatBytes(activity.byteCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ActivityAction(activity, onCancel, onRetry)
    }
    if (activity.status == ActivityStatus.TRANSFERRING) {
        val percent = (activity.progressFraction * 100f).toInt().coerceIn(0, 100)
        val progressDescription = stringResource(R.string.session_progress_description, percent)
        LinearProgressIndicator(
            progress = { activity.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .semantics { contentDescription = progressDescription },
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                transferDetail(activity),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                activityTime(activity),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.session_progress_percent, percent),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ActivityMeta(
    activity: SessionActivityUi,
    onCancel: (ActivityKey) -> Unit,
    onRetry: (ActivityKey) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = activityTime(activity),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActivityAction(activity, onCancel, onRetry)
    }
}

@Composable
private fun ActivityAction(
    activity: SessionActivityUi,
    onCancel: (ActivityKey) -> Unit,
    onRetry: (ActivityKey) -> Unit,
) {
    when (activity.status) {
        ActivityStatus.AWAITING_PEER, ActivityStatus.TRANSFERRING -> IconButton(
            onClick = { onCancel(activity.id) },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.session_cancel_transfer),
                modifier = Modifier.size(20.dp),
            )
        }
        ActivityStatus.FAILED -> IconButton(
            onClick = { onRetry(activity.id) },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                Icons.Outlined.Replay,
                contentDescription = stringResource(R.string.session_retry_transfer),
                modifier = Modifier.size(22.dp),
            )
        }
        ActivityStatus.COMPLETED -> if (activity.direction == ShareDirection.OUTGOING) {
            Icon(
                Icons.Outlined.DoneAll,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 5.dp)
                    .size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else if (activity.kind != ContentKind.TEXT) {
            Icon(
                Icons.Outlined.Download,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 7.dp)
                    .size(22.dp),
            )
        }
        else -> Unit
    }
}

@Composable
private fun activityStatusDescription(status: ActivityStatus): String = stringResource(
    when (status) {
        ActivityStatus.AWAITING_PEER -> R.string.session_status_waiting_description
        ActivityStatus.TRANSFERRING -> R.string.session_status_transferring_description
        ActivityStatus.COMPLETED -> R.string.session_status_completed_description
        ActivityStatus.REJECTED -> R.string.session_status_rejected_description
        ActivityStatus.CANCELLED -> R.string.session_status_cancelled_description
        ActivityStatus.FAILED -> R.string.session_status_failed_description
    },
)

@Composable
private fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0L))

@Composable
private fun activityTime(activity: SessionActivityUi): String =
    DateFormat.getTimeFormat(LocalContext.current).format(Date(activity.timestampMillis))

@Composable
private fun transferDetail(activity: SessionActivityUi): String {
    val rate = activity.rateBytesPerSecond
    val eta = activity.etaSeconds
    return when {
        rate != null && eta != null -> stringResource(R.string.session_rate_eta, formatBytes(rate), eta)
        rate != null -> stringResource(R.string.session_rate, formatBytes(rate))
        else -> stringResource(R.string.session_status_transferring)
    }
}
