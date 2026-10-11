package com.unishare.app.presentation.session

import androidx.compose.ui.graphics.ImageBitmap
import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ActivityStatus
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareDirection
import com.unishare.app.domain.ShareErrorKind
import com.unishare.app.domain.ShareOrigin

data class SessionActivityUi(
    val id: ActivityKey,
    val direction: ShareDirection,
    val kind: ContentKind,
    val origin: ShareOrigin = ShareOrigin.DIRECT,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val transferredBytes: Long,
    val status: ActivityStatus,
    val timestampMillis: Long,
    val text: String? = null,
    val previewReference: String? = null,
    val previewBitmap: ImageBitmap? = null,
    val previewBytes: ByteArray? = null,
    val rateBytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val errorKind: ShareErrorKind? = null,
) {
    val progressFraction: Float
        get() = if (byteCount <= 0L) 0f
        else (transferredBytes.toDouble() / byteCount.toDouble()).toFloat().coerceIn(0f, 1f)
}

data class SessionUiState(
    val deviceId: DeviceKey,
    val deviceName: String = "",
    val platform: DevicePlatform? = null,
    val online: Boolean = false,
    val connected: Boolean = false,
    val trusted: Boolean = false,
    val identityChanged: Boolean = false,
    val pairing: Boolean = false,
    val composerText: String = "",
    val composerMode: ComposerMode = ComposerMode.TEXT,
    val activities: List<SessionActivityUi> = emptyList(),
    val shareSheetVisible: Boolean = false,
)

enum class ComposerMode { TEXT, LINK }

enum class SessionUserMessage {
    DEVICE_OFFLINE,
    DEVICE_NOT_TRUSTED,
    TEXT_REQUIRED,
    SEND_FAILED,
    CANCEL_FAILED,
    RETRY_FAILED,
    CLIPBOARD_EMPTY,
    CLIPBOARD_REQUIRES_FOREGROUND,
    CONTENT_PERMISSION_DENIED,
    INVALID_LINK,
    FORGET_FAILED,
}

data class SessionEvent(val message: SessionUserMessage)
