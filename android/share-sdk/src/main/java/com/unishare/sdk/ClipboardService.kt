package com.unishare.sdk

class ClipboardService internal constructor(private val sdk: ShareSdk) {
    /** Call only from an explicit user action while the host is focused and foreground. */
    fun readShareableContent(): Operation = sdk.command(13)
    fun writeContent(content: ShareContent): Operation = when (content) {
        is ShareContent.Text -> sdk.command(14, text = content.text, number = 1)
        is ShareContent.Image -> sdk.command(14, mime = content.mimeType, bytes = content.bytes, number = 2)
    }
}
