package com.unishare.app.presentation.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unishare.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    deviceName: String,
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    onClipboard: () -> Unit,
    onText: () -> Unit,
    onLink: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.session_share_to, deviceName),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
            ShareTileRow(
                first = ShareTileSpec(Icons.Outlined.Image, R.string.session_photo, R.string.session_photo_detail, Color(0xFFEAF3FF), Color(0xFF1389F4), onPhoto),
                second = ShareTileSpec(Icons.Outlined.Description, R.string.session_file, R.string.session_file_detail, Color(0xFFEDF3FC), Color(0xFF0E78E8), onFile),
            )
            ShareTileRow(
                first = ShareTileSpec(Icons.Outlined.ContentPaste, R.string.session_clipboard, R.string.session_clipboard_detail, Color(0xFFF5EDFF), Color(0xFF8B4DE8), onClipboard),
                second = ShareTileSpec(Icons.AutoMirrored.Outlined.Chat, R.string.session_text, R.string.session_text_detail, Color(0xFFEAF9EF), Color(0xFF16A850), onText),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShareTile(
                    spec = ShareTileSpec(Icons.Outlined.Link, R.string.session_link, R.string.session_link_detail, Color(0xFFF1EFFF), Color(0xFF7355E8), onLink),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class ShareTileSpec(
    val icon: ImageVector,
    val label: Int,
    val detail: Int,
    val background: Color,
    val iconTint: Color,
    val onClick: () -> Unit,
)

@Composable
private fun ShareTileRow(first: ShareTileSpec, second: ShareTileSpec) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShareTile(first, Modifier.weight(1f))
        ShareTile(second, Modifier.weight(1f))
    }
}

@Composable
private fun ShareTile(spec: ShareTileSpec, modifier: Modifier = Modifier) {
    Surface(
        onClick = spec.onClick,
        modifier = modifier.heightIn(min = 114.dp),
        shape = RoundedCornerShape(20.dp),
        color = spec.background,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = spec.iconTint,
                modifier = Modifier.padding(bottom = 7.dp),
            )
            Text(
                text = stringResource(spec.label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(spec.detail),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
