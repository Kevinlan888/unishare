package com.unishare.consumer

import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import com.unishare.sdk.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** This project has no SDK sources or project dependency: it consumes only the AAR. */
class ConsumerSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        try {
            ShareSdk(targetContext, "release-consumer").use { sdk ->
                check(sdk.initialize(SdkConfig("Release Kotlin consumer 世界")).result.get(15, TimeUnit.SECONDS).isSuccess)
                check(sdk.state == SdkState.INITIALIZED)
                check(sdk.discovery.devices().isEmpty())
                check(sdk.connections.trustedDevices().isEmpty())
                val browsing = java.util.concurrent.CountDownLatch(1)
                sdk.discovery.observe { event -> if (event is DiscoveryEvent.State && event.state == DiscoveryState.BROWSING) browsing.countDown() }
                sdk.setForeground(true)
                check(sdk.start().result.get(15, TimeUnit.SECONDS).isSuccess)
                check(browsing.await(15, TimeUnit.SECONDS))
                check(sdk.stop().result.get(15, TimeUnit.SECONDS).isSuccess)
            }
            output.putString("stream", "PASS minified release AAR consumer JNI lifecycle\n"); finish(-1, output)
        } catch (failure: Throwable) { output.putString("stream", android.util.Log.getStackTraceString(failure)); finish(0, output) }
    }
    // Compile the entire supported business surface against packaged Kotlin metadata.
    @Suppress("unused")
    private fun compileSurface(sdk: ShareSdk, peer: DeviceId, transfer: TransferId, uri: Uri) {
        sdk.discovery.observe { event -> when (event) {
            is DiscoveryEvent.Online -> event.device.addresses
            is DiscoveryEvent.Updated -> event.device.protocolVersion
            is DiscoveryEvent.Offline -> event.device.platform
            is DiscoveryEvent.Error -> event.error.platformError
            is DiscoveryEvent.State -> event.state
        } }.close()
        sdk.connections.observe { event -> when (event) {
            is ConnectionEvent.PairingRequest -> sdk.connections.confirmPairing(event.requestId, event.verificationCode.length == 6)
            is ConnectionEvent.PairingResult -> event.error
            is ConnectionEvent.IdentityChanged -> event.error
            is ConnectionEvent.Connected -> event.device.id
            is ConnectionEvent.Disconnected -> event.error
        } }.close()
        sdk.transfers.observe { event -> when (event) {
            is TransferEvent.IncomingOffer -> sdk.transfers.accept(event.requestId, uri)
            is TransferEvent.Progress -> event.transferred
            is TransferEvent.Result -> event.error
        } }.close()
        sdk.connections.connect(peer); sdk.connections.rejectPairing(1); sdk.connections.forgetDevice(peer)
        sdk.connections.trustedDevices().forEach { it.publicKeyFingerprint }
        sdk.transfers.sendText(peer, "text"); sdk.transfers.sendImage(peer, uri, "image/png", 3)
        sdk.transfers.sendFile(peer, uri, "file", 0); sdk.transfers.reject(1); sdk.transfers.cancel(transfer)
        sdk.clipboard.readShareableContent(); sdk.clipboard.writeContent(ShareContent.Text("text"))
        sdk.clipboard.writeContent(ShareContent.Image("image/png", byteArrayOf(1, 2, 3)))
        sdk.transfers.sendText(DeviceId(UUID.randomUUID()), "x").result.thenAccept { result ->
            result.operationId; result.transferId; result.content; result.error?.category; result.isSuccess
        }
    }
}
