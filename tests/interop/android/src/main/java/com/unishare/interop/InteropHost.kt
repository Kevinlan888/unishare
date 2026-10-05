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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
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
    private fun pixels(bytes: ByteArray): String {
        val image=checkNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size));check(image.width==2&&image.height==2)
        val colors=IntArray(4);image.getPixels(colors,0,2,0,0,2,2);image.recycle()
        val rgba=ByteArray(16);colors.forEachIndexed {index,value->rgba[index*4]=(value shr 16).toByte();rgba[index*4+1]=(value shr 8).toByte();rgba[index*4+2]=value.toByte();rgba[index*4+3]=(value ushr 24).toByte()};return hash(rgba)
    }
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
                "name" to "interop-$run-$role", "sdkPlatform" to "android", "scenario" to "extended", "expectedPeer" to expected)
            val activity = startActivitySync(Intent(targetContext, HostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!activity.hasWindowFocus()) { check(System.nanoTime() < deadline) { "foreground focus required" }; Thread.sleep(50) }
            try { ShareSdk(targetContext, "interop-$run-$role").use { sdk ->
                wait(watch("initialize", sdk.initialize(SdkConfig("interop-$run-$role", requestTimeoutSeconds = 30))))
                sdk.setForeground(true)
                val peers = LinkedBlockingQueue<DeviceInfo>()
                val offers = LinkedBlockingQueue<TransferEvent.IncomingOffer>()
                val incomingResults = LinkedBlockingQueue<TransferEvent.Result>()
                val selected=AtomicReference<DeviceInfo>()
                val pendingPairing=LinkedBlockingQueue<ConnectionEvent.PairingRequest>()
                val cancelId=AtomicReference<TransferId>()
                val cancelIssued=AtomicBoolean()
                val offline=LinkedBlockingQueue<DeviceId>()
                fun confirm(event: ConnectionEvent.PairingRequest) {
                    val match=event.device.id==selected.get()?.id
                    record("pairing", "id" to event.device.id.value,"sas" to event.verificationCode,"decision" to if(match) "confirm" else "reject")
                    watch("confirm",sdk.connections.confirmPairing(event.requestId,match))
                }
                val observations = listOf(
                    sdk.discovery.observe { event ->
                        when (event) {
                            is DiscoveryEvent.Online -> { record("discovery", "id" to event.device.id.value, "name" to event.device.name,"port" to event.device.port,"paired" to event.device.paired); if(event.device.name == expected) peers.add(event.device) }
                            is DiscoveryEvent.Error -> record("discovery_error", "error" to event.error.toString())
                            is DiscoveryEvent.Updated -> record("updated","id" to event.device.id.value,"name" to event.device.name,"port" to event.device.port,"paired" to event.device.paired)
                            is DiscoveryEvent.Offline -> {record("offline","id" to event.device.id.value,"name" to event.device.name);offline.add(event.device.id)}
                            else -> {}
                        }
                    },
                    sdk.connections.observe { event -> when(event){
                    is ConnectionEvent.Disconnected -> record("connection_error","id" to event.deviceId.value,"error" to (event.error?.category?.name?:""),"native" to (event.error?.platformError?:0))
                    is ConnectionEvent.PairingResult -> record("pairing_result","id" to event.deviceId.value,"error" to (event.error?.category?.name?:""),"native" to (event.error?.platformError?:0))
                    is ConnectionEvent.Connected -> record("connected","id" to event.deviceId.value)
                    is ConnectionEvent.PairingRequest -> {
                        pendingPairing.add(event)
                        if(selected.get()!=null) pendingPairing.poll()?.let { confirm(it) }
                    }
                    else -> {}
                    } },
                    sdk.transfers.observe { event -> when(event) {
                        is TransferEvent.IncomingOffer -> { record("offer", "id" to event.transferId.value, "requestId" to event.requestId, "size" to event.byteCount, "kind" to event.contentType.name); offers.add(event) }
                        is TransferEvent.Progress -> {record("progress", "id" to event.transferId.value, "bytes" to event.transferred, "size" to event.byteCount)
                            if(event.transferId==cancelId.get()&&event.transferred>0&&event.transferred<event.byteCount&&cancelIssued.compareAndSet(false,true)){
                                record("cancel","id" to event.transferId.value,"bytes" to event.transferred,"size" to event.byteCount)
                                watch("cancel",sdk.transfers.cancel(event.transferId),transfer=event.transferId.value.toString())
                            }}
                        is TransferEvent.Result -> { terminals.add(event); incomingResults.add(event); record("terminal", "id" to event.transferId.value, "error" to (event.error?.category?.name ?: "")) }
                    } }
                )
                wait(watch("start", sdk.start()))
                val peer = checkNotNull(peers.poll(60, TimeUnit.SECONDS)) { "discovery timeout" }
                selected.set(peer); while(true) { val request=pendingPairing.poll()?:break; confirm(request) }
                record("discovered", "id" to peer.id.value, "name" to peer.name, "platform" to peer.platform.name,
                    "protocol" to "${peer.protocolVersion.major}.${peer.protocolVersion.minor}", "addresses" to peer.addresses.joinToString(","), "port" to peer.port)
                val names = listOf("text.txt", "image.png", "image.jpg", "empty.bin", "large.bin", "clipboard.txt","text-large.txt","clipboard.png","isolation.txt")
                if(role == "sender") {
                    wait(watch("connect", sdk.connections.connect(peer.id)))
                    for((index, name) in names.take(8).withIndex()) {
                        var bytes = targetContext.assets.open(name).use { it.readBytes() }
                        val source = File(targetContext.cacheDir, "source-$run-$index").apply { writeBytes(bytes) }
                        if(index == 5) {
                            wait(watch("clipboard_write", sdk.clipboard.writeContent(ShareContent.Text(bytes.toString(Charsets.UTF_8)))))
                            val content = wait(watch("clipboard_read", sdk.clipboard.readShareableContent())).content
                            check(content is ShareContent.Text && content.text.toByteArray().contentEquals(bytes))
                            record("clipboard", "decision" to "explicit_read_send")
                        }
                        if(index==7){wait(watch("clipboard_write",sdk.clipboard.writeContent(ShareContent.Image("image/png",bytes))));val value=wait(watch("clipboard_read",sdk.clipboard.readShareableContent())).content;check(value is ShareContent.Image&&value.mimeType=="image/png"&&pixels(value.bytes)==pixels(bytes));bytes=value.bytes;source.writeBytes(bytes);record("clipboard","decision" to "explicit_read_send")}
                        val operation = when(index) {
                            0,5,6 -> sdk.transfers.sendText(peer.id, bytes.toString(Charsets.UTF_8))
                            1,2,7 -> sdk.transfers.sendImage(peer.id, uri(source), if(index == 2) "image/jpeg" else "image/png", bytes.size.toLong(), name)
                            else -> sdk.transfers.sendFile(peer.id, uri(source), name, bytes.size.toLong())
                        }
                        val result = wait(watch("send", operation))
                        check(source.readBytes().contentEquals(bytes))
                        record("sent", "id" to requireNotNull(result.transferId).value, "name" to name, "size" to bytes.size, "sha256" to hash(bytes),"pixel_sha256" to if(index==7)pixels(bytes) else "")
                        source.delete()
                    }
                    check(wait(watch("send_reject", sdk.transfers.sendText(peer.id, "reject")), false).error?.category == ErrorCategory.TRANSFER_REJECTED)
                    val source=File(targetContext.cacheDir,"source-$run-cancel").apply {writeBytes(targetContext.assets.open("cancel.bin").use {it.readBytes()})}
                    val cancelled=wait(watch("send_cancel",sdk.transfers.sendFile(peer.id,uri(source),"cancel.bin",source.length())),false)
                    check(cancelled.error?.category in listOf(ErrorCategory.CANCELLED,ErrorCategory.NETWORK_LOST));source.delete()
                } else {
                    for(index in 0..9) {
                        val offer = checkNotNull(offers.poll(60, TimeUnit.SECONDS)) { "offer timeout" }; check(offer.deviceId == peer.id)
                        val id = offer.transferId.value
                        val sink = File(targetContext.cacheDir, "sink-$run-$index"); check(!sink.exists())
                        if(index == 8) { record("consent", "id" to id, "requestId" to offer.requestId, "decision" to "reject"); wait(watch("reject", sdk.transfers.reject(offer.requestId), offer.requestId, id.toString())) }
                        else {
                            val expectedBytes = targetContext.assets.open(if(index==9) "cancel.bin" else names[index]).use { it.readBytes() }
                            check(if(index==7)offer.byteCount>0 else offer.byteCount == expectedBytes.size.toLong())
                            check(offer.contentType == when(index) { 0,5,6 -> ContentType.TEXT; 1,2,7 -> ContentType.IMAGE; else -> ContentType.FILE })
                            if(index==9)cancelId.set(offer.transferId)
                            record("consent", "id" to id, "requestId" to offer.requestId, "decision" to "accept"); wait(watch("accept", sdk.transfers.accept(offer.requestId, uri(sink)), offer.requestId, id.toString()))
                        }
                        val result = checkNotNull(incomingResults.poll(35, TimeUnit.SECONDS)); check(result.transferId == offer.transferId)
                        if(index == 8) { check(result.error?.category == ErrorCategory.TRANSFER_REJECTED); check(!sink.exists()) }
                        else if(index==9) {check(result.error?.category==ErrorCategory.CANCELLED);check(!sink.exists());record("cleanup","id" to id,"deleted" to true)}
                        else { check(result.error == null); val bytes = sink.readBytes();val original=targetContext.assets.open(names[index]).use {it.readBytes()};check(if(index==7)pixels(bytes)==pixels(original) else bytes.contentEquals(original)); record("received", "id" to id, "name" to names[index], "size" to bytes.size, "sha256" to hash(bytes),"pixel_sha256" to if(index==7)pixels(bytes) else ""); sink.delete() }
                    }
                }
                val trust=sdk.connections.trustedDevices();check(trust.size==1&&trust[0].id==peer.id)
                wait(watch("restart_stop",sdk.stop()));wait(watch("restart_start",sdk.start()))
                val restored=sdk.connections.trustedDevices();check(restored.size==1&&restored[0].id==trust[0].id&&restored[0].publicKeyFingerprint.contentEquals(trust[0].publicKeyFingerprint));record("restart","trustStable" to true)
                fun recordFresh() { val fresh=sdk.discovery.devices().first {it.id==peer.id&&it.port!=peer.port};record("rediscovered","id" to fresh.id.value,"port" to fresh.port,"paired" to fresh.paired) }
                if(role=="sender"){
                    val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
                    while(sdk.discovery.devices().none {it.id==peer.id&&it.port!=peer.port}) {check(System.nanoTime()<until){"new listener discovery timeout"};Thread.sleep(20)}
                    recordFresh()
                    wait(watch("reconnect",sdk.connections.connect(peer.id)))
                    val bytes=targetContext.assets.open("isolation.txt").use {it.readBytes()}
                    val result=wait(watch("send",sdk.transfers.sendText(peer.id,bytes.toString(Charsets.UTF_8))))
                    record("sent","id" to requireNotNull(result.transferId).value,"name" to "isolation.txt","size" to bytes.size,"sha256" to hash(bytes))
                }else{
                    val rediscover=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(sdk.discovery.devices().none {it.id==peer.id&&it.port!=peer.port}) {check(System.nanoTime()<rediscover){"receiver restart discovery"};Thread.sleep(20)}
                    recordFresh()
                    val offer=checkNotNull(offers.poll(60,TimeUnit.SECONDS));check(offer.deviceId==peer.id&&offer.contentType==ContentType.TEXT&&offer.byteCount==14L)
                    val sink=File(targetContext.cacheDir,"sink-$run-isolation");check(!sink.exists())
                    record("consent","id" to offer.transferId.value,"requestId" to offer.requestId,"decision" to "accept");wait(watch("accept",sdk.transfers.accept(offer.requestId,uri(sink)),offer.requestId,offer.transferId.value.toString()))
                    val result=checkNotNull(incomingResults.poll(35,TimeUnit.SECONDS));check(result.transferId==offer.transferId&&result.error==null)
                    val bytes=sink.readBytes();check(bytes.contentEquals(targetContext.assets.open("isolation.txt").use {it.readBytes()}));record("received","id" to offer.transferId.value,"name" to "isolation.txt","size" to bytes.size,"sha256" to hash(bytes));sink.delete()
                    val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(offline.poll(1,TimeUnit.SECONDS)!=peer.id){check(System.nanoTime()<until){"peer offline timeout"}}
                }
                wait(watch("stop", sdk.stop()))
                check(terminals.size == 11 && terminals.map { it.transferId }.toSet().size == 11)
                check(operations.all { it.second.get() == 1 && it.first.result.isDone })
                observations.forEach { it.close() }
                record("complete", "terminals" to 11)
            } } finally { runOnMainSync { activity.finish() } }
            output.putString("stream", "INTEROP_PASS\n"); finish(-1, output)
        } catch(failure: Throwable) {
            if(::log.isInitialized) record("failure", "message" to failure.toString())
            output.putString("stream", android.util.Log.getStackTraceString(failure)); finish(0, output)
        }
    }
}
