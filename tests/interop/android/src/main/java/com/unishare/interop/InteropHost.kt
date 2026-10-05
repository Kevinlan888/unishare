package com.unishare.interop

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.unishare.sdk.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Consumes only the delivered AAR. All content/discovery use public SDK APIs. */
class InteropHost : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var log: File
    private lateinit var role: String
    private lateinit var run: String
    private val operations = CopyOnWriteArrayList<Pair<Operation, AtomicInteger>>()
    private val terminals = CopyOnWriteArrayList<TransferEvent.Result>()
    @Synchronized private fun record(event: String, vararg fields: Pair<String, Any>) {
        val value = JSONObject().put("event", event).put("role", role).put("run", run)
        fields.forEach { value.put(it.first, it.second) }
        log.appendText(value.toString() + "\n")
        android.util.Log.i("UniShareInterop", value.toString())
    }
    private fun watch(name: String, operation: Operation, request: Long = 0, transfer: String = ""): Operation {
        val count = AtomicInteger()
        operations.add(operation to count)
        operation.result.thenAccept { result ->
            count.incrementAndGet()
            record("operation", "operation" to name, "operationId" to result.operationId,
                "error" to (result.error?.category?.name ?: ""), "requestId" to request, "id" to transfer)
        }
        return operation
    }
    private fun wait(operation: Operation, success: Boolean = true): OperationResult {
        val result = operation.result.get(35, TimeUnit.SECONDS)
        if (success) check(result.isSuccess) { result.toString() }
        return result
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun uri(file: File) = Uri.parse("content://com.unishare.interop.staging/${file.name}")
    override fun onCreate(arguments: Bundle?) { args = requireNotNull(arguments); super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        try {
            role = requireNotNull(args.getString("role")); run = requireNotNull(args.getString("run"))
            check(role == "sender" || role == "receiver"); check(run.matches(Regex("[A-Za-z0-9-]+")))
            check(args.getString("testConsent") == "true")
            val expected = requireNotNull(args.getString("peer"))
            log = File(targetContext.filesDir, "$run-$role.jsonl"); check(!log.exists()) { "fresh run required" }
            record("begin", "platform" to "android", "os" to android.os.Build.VERSION.RELEASE,
                "name" to "interop-$run-$role", "sdkPlatform" to "android", "expectedPeer" to expected)
            val activity = startActivitySync(Intent(targetContext, HostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!activity.hasWindowFocus()) { check(System.nanoTime() < deadline) { "foreground focus required" }; Thread.sleep(50) }
            try { ShareSdk(targetContext, "interop-$run-$role").use { sdk ->
                wait(watch("initialize", sdk.initialize(SdkConfig("interop-$run-$role", requestTimeoutSeconds = 30))))
                sdk.setForeground(true)
                val peers = LinkedBlockingQueue<DeviceInfo>()
                val offers = LinkedBlockingQueue<TransferEvent.IncomingOffer>()
                val incomingResults = LinkedBlockingQueue<TransferEvent.Result>()
                val observations = listOf(
                    sdk.discovery.observe { event ->
                        when (event) {
                            is DiscoveryEvent.Online -> { record("discovery", "id" to event.device.id.value, "name" to event.device.name); if(event.device.name == expected) peers.add(event.device) }
                            is DiscoveryEvent.Error -> record("discovery_error", "error" to event.error.toString())
                            else -> {}
                        }
                    },
                    sdk.connections.observe { event -> if (event is ConnectionEvent.PairingRequest) {
                        val match = event.device.name == expected
                        record("pairing", "id" to event.device.id.value, "sas" to event.verificationCode, "decision" to if(match) "confirm" else "reject")
                        watch("confirm", sdk.connections.confirmPairing(event.requestId, match))
                    } },
                    sdk.transfers.observe { event -> when(event) {
                        is TransferEvent.IncomingOffer -> { record("offer", "id" to event.transferId.value, "requestId" to event.requestId, "size" to event.byteCount, "kind" to event.contentType.name); offers.add(event) }
                        is TransferEvent.Progress -> record("progress", "id" to event.transferId.value, "bytes" to event.transferred, "size" to event.byteCount)
                        is TransferEvent.Result -> { terminals.add(event); incomingResults.add(event); record("terminal", "id" to event.transferId.value, "error" to (event.error?.category?.name ?: "")) }
                    } }
                )
                wait(watch("start", sdk.start()))
                val peer = checkNotNull(peers.poll(60, TimeUnit.SECONDS)) { "discovery timeout" }
                record("discovered", "id" to peer.id.value, "name" to peer.name, "platform" to peer.platform.name,
                    "protocol" to "${peer.protocolVersion.major}.${peer.protocolVersion.minor}", "addresses" to peer.addresses.joinToString(","), "port" to peer.port)
                val names = listOf("text.txt", "image.png", "image.jpg", "empty.bin", "large.bin", "clipboard.txt")
                if(role == "sender") {
                    wait(watch("connect", sdk.connections.connect(peer.id)))
                    for((index, name) in names.withIndex()) {
                        val bytes = targetContext.assets.open(name).use { it.readBytes() }
                        val source = File(targetContext.cacheDir, "source-$run-$index").apply { writeBytes(bytes) }
                        if(index == 5) {
                            wait(watch("clipboard_write", sdk.clipboard.writeContent(ShareContent.Text(bytes.toString(Charsets.UTF_8)))))
                            val content = wait(watch("clipboard_read", sdk.clipboard.readShareableContent())).content
                            check(content is ShareContent.Text && content.text.toByteArray().contentEquals(bytes))
                            record("clipboard", "decision" to "explicit_read_send")
                        }
                        val operation = when(index) {
                            0,5 -> sdk.transfers.sendText(peer.id, bytes.toString(Charsets.UTF_8))
                            1,2 -> sdk.transfers.sendImage(peer.id, uri(source), if(index == 1) "image/png" else "image/jpeg", bytes.size.toLong(), name)
                            else -> sdk.transfers.sendFile(peer.id, uri(source), name, bytes.size.toLong())
                        }
                        val result = wait(watch("send", operation))
                        check(source.readBytes().contentEquals(bytes))
                        record("sent", "id" to requireNotNull(result.transferId).value, "name" to name, "size" to bytes.size, "sha256" to hash(bytes))
                        source.delete()
                    }
                    check(wait(watch("send_reject", sdk.transfers.sendText(peer.id, "reject")), false).error?.category == ErrorCategory.TRANSFER_REJECTED)
                } else {
                    for(index in 0..6) {
                        val offer = checkNotNull(offers.poll(60, TimeUnit.SECONDS)) { "offer timeout" }; check(offer.device.id == peer.id)
                        val id = offer.transferId.value
                        val sink = File(targetContext.cacheDir, "sink-$run-$index"); check(!sink.exists())
                        if(index == 6) { record("consent", "id" to id, "requestId" to offer.requestId, "decision" to "reject"); wait(watch("reject", sdk.transfers.reject(offer.requestId), offer.requestId, id.toString())) }
                        else {
                            val expectedBytes = targetContext.assets.open(names[index]).use { it.readBytes() }
                            check(offer.byteCount == expectedBytes.size.toLong())
                            check(offer.contentType == when(index) { 0,5 -> ContentType.TEXT; 1,2 -> ContentType.IMAGE; else -> ContentType.FILE })
                            record("consent", "id" to id, "requestId" to offer.requestId, "decision" to "accept"); wait(watch("accept", sdk.transfers.accept(offer.requestId, uri(sink)), offer.requestId, id.toString()))
                        }
                        val result = checkNotNull(incomingResults.poll(35, TimeUnit.SECONDS)); check(result.transferId == offer.transferId)
                        if(index == 6) { check(result.error?.category == ErrorCategory.TRANSFER_REJECTED); check(!sink.exists()) }
                        else { check(result.error == null); val bytes = sink.readBytes(); check(bytes.contentEquals(targetContext.assets.open(names[index]).use { it.readBytes() })); record("received", "id" to id, "name" to names[index], "size" to bytes.size, "sha256" to hash(bytes)); sink.delete() }
                    }
                }
                wait(watch("stop", sdk.stop()))
                check(terminals.size == 7 && terminals.map { it.transferId }.toSet().size == 7)
                check(operations.all { it.second.get() == 1 && it.first.result.isDone })
                observations.forEach { it.close() }
                record("complete", "terminals" to 7)
            } } finally { runOnMainSync { activity.finish() } }
            output.putString("stream", "INTEROP_PASS\n"); finish(-1, output)
        } catch(failure: Throwable) {
            if(::log.isInitialized) record("failure", "message" to failure.toString())
            output.putString("stream", android.util.Log.getStackTraceString(failure)); finish(0, output)
        }
    }
}
