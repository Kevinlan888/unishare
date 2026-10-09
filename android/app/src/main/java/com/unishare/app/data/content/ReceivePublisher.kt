package com.unishare.app.data.content

import android.net.Uri

sealed interface ReceiveDestination {
    data object Downloads : ReceiveDestination
    /** Generic SAF publication is unsupported: requests fail before any directory side effects. */
    data class SafTree(val uri: Uri) : ReceiveDestination
}

/** Invoke on an IO dispatcher only after the SDK reports successful terminal completion. */
fun interface ReceivePublisher {
    fun publish(staging: PreparedStaging, displayName: String, mimeType: String, destination: ReceiveDestination): Uri
}
