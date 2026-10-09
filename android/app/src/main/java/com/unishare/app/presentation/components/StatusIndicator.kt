package com.unishare.app.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class StatusTone { ONLINE, NEUTRAL, ACTIVE, ERROR }

@Composable
fun StatusIndicator(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val color = when (tone) {
        StatusTone.ONLINE -> Color(0xFF0DB84B)
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.outline
        StatusTone.ACTIVE -> MaterialTheme.colorScheme.primary
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Circle,
            contentDescription = null,
            modifier = Modifier.size(9.dp),
            tint = color,
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
