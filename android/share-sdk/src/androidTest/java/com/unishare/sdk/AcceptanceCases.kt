package com.unishare.sdk

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Actual public SDK instances, platform discovery and TLS; no protocol surrogate. */
internal object AcceptanceCases {
    private fun Operation.await(): OperationResult = result.get(20, TimeUnit.SECONDS)
    fun run(context: Context) {
        val name = "accept-${UUID.randomUUID()}"
        val peerName = "B-${UUID.randomUUID()}"
        val peerStore = "accept-${UUID.randomUUID()}"
        val a = ShareSdk(context, name)
        val b = ShareSdk(context, peerStore)
        try {
            check(a.initialize(SdkConfig("Acceptance A", offlineDebounceSeconds = 1)).await().isSuccess)
            check(b.initialize(SdkConfig(peerName, offlineDebounceSeconds = 1)).await().isSuccess)
            check(a.start().await().error?.category == ErrorCategory.PERMISSION_DENIED)
            val peers = LinkedBlockingQueue<DeviceInfo>()
            val offline = LinkedBlockingQueue<DeviceInfo>()
            a.discovery.observe { when(it) {
                is DiscoveryEvent.Online -> if (it.device.name == peerName) peers.add(it.device)
                is DiscoveryEvent.Offline -> if (it.device.name == peerName) offline.add(it.device)
                else -> {}
            } }
            a.setForeground(true); b.setForeground(true)
            check(a.start().await().isSuccess); check(b.start().await().isSuccess)
            val peer = checkNotNull(peers.poll(20, TimeUnit.SECONDS)) {
                "real NSD produced no matching Online in20s; A=${a.discovery.devices().size}, B=${b.discovery.devices().size}"
            }
            check(peer.port in 1..65535 && peer.platform == Platform.ANDROID && peer.protocolVersion == ProtocolVersion(1, 0))
            check(peer.addresses.isNotEmpty())
            check(peers.isEmpty()) { "duplicate Online" }
            val pairingA = LinkedBlockingQueue<ConnectionEvent.PairingRequest>()
            val pairingB = LinkedBlockingQueue<ConnectionEvent.PairingRequest>()
            a.connections.observe { if (it is ConnectionEvent.PairingRequest) pairingA.add(it) }
            b.connections.observe { if (it is ConnectionEvent.PairingRequest) pairingB.add(it) }
            val connected = a.connections.connect(peer.id)
            val pa = checkNotNull(pairingA.poll(20, TimeUnit.SECONDS)) { "initiator pairing missing" }
            val pb = checkNotNull(pairingB.poll(20, TimeUnit.SECONDS)) { "receiver pairing missing" }
            check(pa.verificationCode == pb.verificationCode && pa.verificationCode.matches(Regex("[0-9]{6}")))
            check(a.connections.confirmPairing(pa.requestId, true).await().isSuccess)
            check(b.connections.confirmPairing(pb.requestId, true).await().isSuccess)
            check(connected.await().isSuccess)
            val offers = LinkedBlockingQueue<TransferEvent.IncomingOffer>()
            val results = LinkedBlockingQueue<TransferEvent.Result>()
            b.transfers.observe { when (it) { is TransferEvent.IncomingOffer -> offers.add(it); is TransferEvent.Result -> results.add(it); else -> {} } }
            fun transfer(bytes: ByteArray, type: ContentType) {
                val source = File(context.cacheDir, "source-${UUID.randomUUID()}").apply { writeBytes(bytes) }
                val sink = File(context.cacheDir, "sink-${UUID.randomUUID()}")
                fun uri(file: File) = Uri.parse("content://${context.packageName}.probe/${file.name}")
                try {
                    val send = when (type) {
                        ContentType.TEXT -> a.transfers.sendText(peer.id, bytes.toString(Charsets.UTF_8))
                        ContentType.IMAGE -> a.transfers.sendImage(peer.id, uri(source), "image/png", bytes.size.toLong())
                        ContentType.FILE -> a.transfers.sendFile(peer.id, uri(source), "acceptance.bin", bytes.size.toLong())
                    }
                    val offer = checkNotNull(offers.poll(15, TimeUnit.SECONDS)) { "missing $type offer: send=${if(send.result.isDone) send.await() else "pending"}" }
                    check(offer.contentType == type && offer.byteCount == bytes.size.toLong())
                    check(!send.result.isDone && !sink.exists() && results.isEmpty()) { "delivery before approval" }
                    check(b.transfers.accept(offer.requestId, uri(sink)).await().isSuccess)
                    check(send.await().isSuccess)
                    val delivered = checkNotNull(results.poll(15, TimeUnit.SECONDS))
                    check(delivered.transferId == offer.transferId && delivered.error == null)
                    check(sink.readBytes().contentEquals(bytes))
                    check(source.readBytes().contentEquals(bytes))
                } finally { source.delete(); sink.delete() }
            }
            transfer("Hello 世界\u0000".toByteArray(), ContentType.TEXT)
            transfer(byteArrayOf(1, 2, 3, 4), ContentType.IMAGE)
            transfer(ByteArray(524289) { (it % 251).toByte() }, ContentType.FILE)
            val rejected = a.transfers.sendText(peer.id, "reject")
            check(b.transfers.reject(checkNotNull(offers.poll(15, TimeUnit.SECONDS)).requestId).await().isSuccess)
            check(rejected.await().error?.category == ErrorCategory.TRANSFER_REJECTED)
            results.poll(5, TimeUnit.SECONDS)
            val cancelled = a.transfers.sendText(peer.id, "cancel before approval")
            val pending = checkNotNull(offers.poll(15, TimeUnit.SECONDS))
            check(a.transfers.cancel(pending.transferId).await().isSuccess)
            check(cancelled.await().error?.category == ErrorCategory.CANCELLED)
            results.poll(5, TimeUnit.SECONDS)
            // Cancellation retires the TLS session; each reconnect still requires receiving consent.
            val reconnect = a.connections.connect(peer.id)
            val reconnectDecision = checkNotNull(pairingB.poll(15, TimeUnit.SECONDS))
            check(b.connections.confirmPairing(reconnectDecision.requestId, true).await().isSuccess)
            check(reconnect.await().isSuccess)
            val largeSource = File(context.cacheDir, "cancel-source-${UUID.randomUUID()}")
            val cancelledSink = File(context.cacheDir, "cancel-sink-${UUID.randomUUID()}")
            val cancelTriggered = java.util.concurrent.CountDownLatch(1)
            val cancelOnce = java.util.concurrent.atomic.AtomicBoolean()
            val cancelObserver = b.transfers.observe { event ->
                if (event is TransferEvent.Progress && event.transferred > 0 && cancelOnce.compareAndSet(false, true)) {
                    b.transfers.cancel(event.transferId); cancelTriggered.countDown()
                }
            }
            try {
                largeSource.outputStream().use { stream -> repeat(64) { stream.write(ByteArray(262144) { 7 }) } }
                val interrupted = a.transfers.sendFile(peer.id, Uri.parse("content://${context.packageName}.probe/${largeSource.name}"), "cancel.bin", largeSource.length())
                val offer = checkNotNull(offers.poll(15, TimeUnit.SECONDS)) { "approved cancel missing offer: ${if(interrupted.result.isDone) interrupted.await() else "pending"}" }
                check(b.transfers.accept(offer.requestId, Uri.parse("content://${context.packageName}.probe/${cancelledSink.name}")).await().isSuccess)
                check(cancelTriggered.await(15, TimeUnit.SECONDS))
                // Core cancellation closes transport immediately; the remote sender sees loss.
                check(interrupted.await().error?.category == ErrorCategory.NETWORK_LOST)
                val terminal = checkNotNull(results.poll(15, TimeUnit.SECONDS))
                check(terminal.transferId == offer.transferId && terminal.error?.category == ErrorCategory.CANCELLED)
                check(!cancelledSink.exists()) { "cancelled approved transfer retained staging" }
                check(largeSource.exists()) { "cancelled transfer deleted source" }
            } finally { cancelObserver.close(); largeSource.delete(); cancelledSink.delete() }
            val pins = a.connections.trustedDevices().single()
            check(b.stop().await().isSuccess)
            check(checkNotNull(offline.poll(15, TimeUnit.SECONDS)) { "real service Stop missing Offline" }.id == peer.id)
            b.close()
            ShareSdk(context, peerStore).use { restartedPeer ->
                check(restartedPeer.initialize(SdkConfig(peerName)).await().isSuccess)
                val receivingDecision = LinkedBlockingQueue<ConnectionEvent.PairingRequest>()
                restartedPeer.connections.observe { if (it is ConnectionEvent.PairingRequest) receivingDecision.add(it) }
                restartedPeer.setForeground(true)
                check(restartedPeer.start().await().isSuccess)
                check(checkNotNull(peers.poll(15, TimeUnit.SECONDS)).id == peer.id) { "local DeviceId changed across restart" }
                val reconnected = a.connections.connect(peer.id)
                val decision = checkNotNull(receivingDecision.poll(15, TimeUnit.SECONDS))
                check(restartedPeer.connections.confirmPairing(decision.requestId, true).await().isSuccess)
                check(reconnected.await().isSuccess) { "certificate/pin did not survive restart" }
                check(pairingA.isEmpty()) { "trusted restart requested pairing" }
            }
            check(a.stop().await().isSuccess); a.close()
            ShareSdk(context, name).use { restarted ->
                check(restarted.initialize(SdkConfig("Restart A")).await().isSuccess)
                val persisted = restarted.connections.trustedDevices().single()
                check(persisted.id == pins.id && persisted.publicKeyFingerprint.contentEquals(pins.publicKeyFingerprint))
            }
            println("PASS real NSD Online/metadata, native pairing, approved text/image/file, rejection/cancellation, trust restart")
        } finally { a.close(); b.close() }
    }
}
