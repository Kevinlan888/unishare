package com.unishare.sdk

import com.unishare.sdk.internal.ApiBackend
import com.unishare.sdk.internal.SdkNative
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.net.Uri

internal object ApiContractCases {
    private fun check(value: Boolean, why: String) { if (!value) throw AssertionError(why) }
    private fun packet(write: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(); write(DataOutputStream(bytes)); return bytes.toByteArray()
    }
    private fun text(out: DataOutputStream, value: String) { val bytes = value.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes) }
    private fun result(error: Int = 0, raw: Long? = null) = packet {
        it.writeInt(error); it.writeBoolean(raw != null); it.writeLong(raw ?: 0); it.write(ByteArray(16));
        it.writeInt(0); text(it, ""); text(it, ""); it.writeInt(0)
    }
    private class Fake : ApiBackend {
        lateinit var receiver: SdkNative.Receiver
        val submissions = java.util.concurrent.LinkedBlockingQueue<Pair<Int, Long>>()
        val released = AtomicInteger(); var closed = false
        val aborted = AtomicInteger()
        var openEntered: CountDownLatch? = null; var openRelease: CountDownLatch? = null
        override fun create(config: ByteArray, key: Long, receiver: SdkNative.Receiver) { this.receiver = receiver; receiver.completion(key, result()) }
        override fun submit(command: Int, uuid: ByteArray, request: Long, stream: Long, number: Long, text: ByteArray,
                            name: ByteArray, mime: ByteArray, bytes: ByteArray, size: Long, key: Long) {
            submissions.put(command to key)
            if (command == 2) receiver.completion(key, result())
        }
        override fun foreground(value: Boolean) {}
        override fun snapshot(trusted: Boolean) = packet { it.writeInt(0) }
        override fun state() = 1
        override fun open(uri: String, destination: Boolean): Long { openEntered?.countDown(); openRelease?.await(5, TimeUnit.SECONDS); return 73L }
        override fun release(stream: Long) { check(stream == 73L, "stream token"); released.incrementAndGet() }
        override fun abort(stream: Long) { aborted.incrementAndGet() }
        override fun close() { closed = true }
        fun next(command: Int): Long { val next = submissions.poll(5, TimeUnit.SECONDS) ?: error("missing command"); check(next.first == command, "command order"); return next.second }
    }
    private fun initialized(fake: Fake, executor: Executor = Executor { it.run() }): ShareSdk {
        val sdk = ShareSdk(fake, executor); check(sdk.initialize(SdkConfig("test")).result.get(5, TimeUnit.SECONDS).isSuccess, "fake initialize"); return sdk
    }
    fun run() {
        configBoundaries()
        val fake = Fake(); val sdk = initialized(fake)
        val peer = DeviceId(UUID.randomUUID())
        val send = sdk.transfers.sendText(peer, "UTF-8 世界\u0000")
        val key = fake.next(7); val completions = AtomicInteger()
        val observed = send.result.thenAccept { completions.incrementAndGet() }
        fake.receiver.completion(key, result(10, -42)); fake.receiver.completion(key, result())
        val outcome = send.result.get(5, TimeUnit.SECONDS)
        observed.get(5, TimeUnit.SECONDS)
        check(outcome.error == ShareError(ErrorCategory.NETWORK_LOST, -42), "original errors")
        check(completions.get() == 1, "duplicate terminal result")
        val image = sdk.transfers.sendImage(peer, Uri.parse("content://test/source"), "image/png", 3)
        val imageKey = fake.next(8)
        fake.receiver.completion(imageKey, result())
        check(image.result.get(5, TimeUnit.SECONDS).isSuccess, "image outcome")
        val sourceReleaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (fake.released.get() == 0 && System.nanoTime() < sourceReleaseDeadline) Thread.yield()
        check(fake.released.get() == 1, "source ownership")
        val pending = sdk.transfers.sendFile(peer, Uri.parse("content://test/file"), "file", 0)
        val pendingKey = fake.next(9)
        val stop = sdk.stop(); fake.next(2)
        // Fake core deliberately delays the cancellation until after Stop was submitted.
        fake.receiver.completion(pendingKey, result(8))
        check(stop.result.get(5, TimeUnit.SECONDS).isSuccess, "stop result")
        check(pending.result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.CANCELLED, "stop terminal")
        val unsupported = sdk.clipboard.readShareableContent(); val clipboardKey = fake.next(13)
        fake.receiver.completion(clipboardKey, result(13))
        check(unsupported.result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.INVALID_INPUT, "clipboard unsupported")
        val closing = sdk.connections.connect(peer); val closeKey = fake.next(3)
        sdk.close()
        check(closing.result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.CANCELLED && fake.closed, "close completes pending")
        fake.receiver.completion(closeKey, result())
        check(closing.result.get().error?.category == ErrorCategory.CANCELLED, "late result after close")
        val reentrant = Fake(); val nested = initialized(reentrant)
        val nestedOp = nested.connections.connect(peer); val nestedKey = reentrant.next(3)
        val done = CountDownLatch(1)
        nestedOp.result.thenAccept { nested.close(); done.countDown() }
        reentrant.receiver.completion(nestedKey, result())
        check(done.await(5, TimeUnit.SECONDS), "close inside callback deadlocked")
        eventCopies()
        allEventsAndContent()
        queuedClose()
        lateUriClose()
        closeDrainsCallback()
    }
    private fun configBoundaries() {
        val c = SdkConfig("numeric boundaries")
        val invalid = listOf(
            c.copy(maxConnections = -1), c.copy(maxConnections = 0), c.copy(maxConnections = 9),
            c.copy(maxPendingRequests = -1), c.copy(maxPendingRequests = 0), c.copy(maxPendingRequests = 5),
            c.copy(maxBufferBytes = -1), c.copy(maxBufferBytes = 0), c.copy(maxBufferBytes = 4194305),
            c.copy(maxTextBytes = -1), c.copy(maxImageBytes = -1), c.copy(maxFileBytes = -1),
            c.copy(controlFrameBytes = -1), c.copy(controlFrameBytes = 0), c.copy(controlFrameBytes = 65537),
            c.copy(dataChunkBytes = -1), c.copy(dataChunkBytes = 0), c.copy(dataChunkBytes = 262145),
            c.copy(requestTimeoutSeconds = -1), c.copy(requestTimeoutSeconds = 0), c.copy(offlineDebounceSeconds = -1)
        )
        for (config in invalid) {
            val sdk = ShareSdk(Fake(), null)
            try { check(sdk.initialize(config).result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.INVALID_INPUT, "invalid numeric config: $config") }
            finally { sdk.close() }
        }
        val valid = listOf(
            c.copy(maxConnections = 1, maxPendingRequests = 1, maxBufferBytes = 1, maxTextBytes = 0, maxImageBytes = 0,
                maxFileBytes = 0, controlFrameBytes = 1, dataChunkBytes = 1, requestTimeoutSeconds = 1, offlineDebounceSeconds = 0),
            c.copy(maxTextBytes = Long.MAX_VALUE, maxImageBytes = Long.MAX_VALUE, maxFileBytes = Long.MAX_VALUE,
                requestTimeoutSeconds = Int.MAX_VALUE, offlineDebounceSeconds = Int.MAX_VALUE)
        )
        for (config in valid) {
            val sdk = ShareSdk(Fake(), null)
            try { check(sdk.initialize(config).result.get(5, TimeUnit.SECONDS).isSuccess, "valid unsigned-representable numeric config: $config") }
            finally { sdk.close() }
        }
    }
    private fun eventCopies() {
        val fake = Fake(); val sdk = initialized(fake); val received = java.util.concurrent.LinkedBlockingQueue<DiscoveryEvent>()
        sdk.discovery.observe { received.put(it) }
        val id = UUID.randomUUID()
        val event = packet {
            it.writeInt(1); it.writeInt(0); it.writeInt(0); it.writeBoolean(false); it.writeLong(0); it.writeLong(0); it.write(ByteArray(16))
            it.writeLong(id.mostSignificantBits); it.writeLong(id.leastSignificantBits); text(it, "设备\u0000"); it.writeInt(1)
            it.writeInt(1); it.writeInt(2); it.writeInt(3456); it.writeBoolean(true); it.writeLong(1234)
            it.writeInt(2); text(it, "192.0.2.1"); text(it, "fe80::1%7")
            text(it, ""); it.writeInt(0); text(it, ""); text(it, ""); it.writeLong(0); it.writeLong(0)
        }
        fake.receiver.event(event); event.fill(0)
        val online = received.poll(5, TimeUnit.SECONDS) as? DiscoveryEvent.Online ?: error("missing Online")
        check(online.device.id == DeviceId(id) && online.device.name == "设备\u0000", "copied UUID/UTF-8")
        check(online.device.platform == Platform.ANDROID && online.device.protocolVersion == ProtocolVersion(1, 2), "platform/version")
        check(online.device.addresses == listOf("192.0.2.1", "fe80::1%7") && online.device.paired && online.device.port == 3456, "metadata")
        sdk.close()
    }
    private fun queuedClose() {
        val tasks = java.util.concurrent.LinkedBlockingQueue<Runnable>(); val fake = Fake()
        val sdk = ShareSdk(fake, Executor { tasks.put(it) })
        val init = sdk.initialize(SdkConfig("queued")); tasks.poll(5, TimeUnit.SECONDS)!!.run(); init.result.get(5, TimeUnit.SECONDS)
        val calls = AtomicInteger(); sdk.discovery.observe { calls.incrementAndGet() }
        val op = sdk.connections.connect(DeviceId(UUID.randomUUID())); val key = fake.next(3)
        fake.receiver.completion(key, result())
        fake.receiver.event(eventPacket(5))
        val queued = tasks.poll(5, TimeUnit.SECONDS)!!
        sdk.close(); queued.run()
        check(calls.get() == 0 && op.result.get().error?.category == ErrorCategory.CANCELLED, "queued completion suppressed after Close")
    }
    private fun eventPacket(kind: Int, state: Int = 0, error: Int = 0): ByteArray = packet {
        it.writeInt(kind); it.writeInt(state); it.writeInt(error); it.writeBoolean(error != 0); it.writeLong(-7); it.writeLong(42)
        it.writeLong(1); it.writeLong(2); it.writeLong(3); it.writeLong(4); text(it, "peer"); it.writeInt(2)
        it.writeInt(1); it.writeInt(0); it.writeInt(2345); it.writeBoolean(false); it.writeLong(500)
        it.writeInt(1); text(it, "2001:db8::1"); text(it, "000042"); it.writeInt(2)
        text(it, "image"); text(it, "image/png"); it.writeLong(99); it.writeLong(25)
    }
    private fun allEventsAndContent() {
        val fake = Fake(); val sdk = initialized(fake)
        val discovery = java.util.concurrent.LinkedBlockingQueue<DiscoveryEvent>()
        val connection = java.util.concurrent.LinkedBlockingQueue<ConnectionEvent>()
        val transfer = java.util.concurrent.LinkedBlockingQueue<TransferEvent>()
        sdk.discovery.observe { discovery.put(it) }; sdk.connections.observe { connection.put(it) }; sdk.transfers.observe { transfer.put(it) }
        fake.receiver.event(eventPacket(2)); check(discovery.poll(5, TimeUnit.SECONDS) is DiscoveryEvent.Updated, "Updated mapping")
        fake.receiver.event(eventPacket(3)); check(discovery.poll(5, TimeUnit.SECONDS) is DiscoveryEvent.Offline, "Offline mapping")
        fake.receiver.event(eventPacket(4, error = 2))
        check((discovery.poll(5, TimeUnit.SECONDS) as DiscoveryEvent.Error).error == ShareError(ErrorCategory.DISCOVERY_FAILED, -7), "discovery error mapping")
        fake.receiver.event(eventPacket(5, state = 3)); check((discovery.poll(5, TimeUnit.SECONDS) as DiscoveryEvent.State).state == DiscoveryState.STOPPED, "state mapping")
        fake.receiver.event(eventPacket(6)); check(connection.poll(5, TimeUnit.SECONDS) is ConnectionEvent.Connected, "connected mapping")
        fake.receiver.event(eventPacket(6, state = 1, error = 10)); check(connection.poll(5, TimeUnit.SECONDS) is ConnectionEvent.Disconnected, "disconnected mapping")
        fake.receiver.event(eventPacket(7)); val pairing = connection.poll(5, TimeUnit.SECONDS) as ConnectionEvent.PairingRequest
        check(pairing.requestId == 42L && pairing.verificationCode == "000042", "six-digit pairing code")
        fake.receiver.event(eventPacket(8, error = 4)); check((connection.poll(5, TimeUnit.SECONDS) as ConnectionEvent.PairingResult).error?.category == ErrorCategory.PAIRING_REJECTED, "pairing result mapping")
        fake.receiver.event(eventPacket(9, error = 6)); check(connection.poll(5, TimeUnit.SECONDS) is ConnectionEvent.IdentityChanged, "identity mapping")
        fake.receiver.event(eventPacket(10)); val offer = transfer.poll(5, TimeUnit.SECONDS) as TransferEvent.IncomingOffer
        check(offer.requestId == 42L && offer.transferId == TransferId(UUID(1, 2)) && offer.byteCount == 99L && offer.mimeType == "image/png" && offer.contentType == ContentType.IMAGE, "incoming metadata")
        fake.receiver.event(eventPacket(11)); check((transfer.poll(5, TimeUnit.SECONDS) as TransferEvent.Progress).transferred == 25L, "progress mapping")
        fake.receiver.event(eventPacket(12, error = 12)); check((transfer.poll(5, TimeUnit.SECONDS) as TransferEvent.Result).error?.category == ErrorCategory.INTEGRITY_FAILED, "terminal mapping")
        val read = sdk.clipboard.readShareableContent(); val key = fake.next(13)
        val bytes = packet { it.writeInt(0); it.writeBoolean(false); it.writeLong(0); it.write(ByteArray(16)); it.writeInt(2); text(it, ""); text(it, "image/png"); it.writeInt(3); it.write(byteArrayOf(1, 2, 3)) }
        fake.receiver.completion(key, bytes); bytes.fill(0)
        val image = read.result.get(5, TimeUnit.SECONDS).content as ShareContent.Image
        val exposed = image.bytes; exposed.fill(0)
        check(image.bytes.contentEquals(byteArrayOf(1, 2, 3)) && image.mimeType == "image/png", "copied immutable clipboard image")
        sdk.close()
    }
    private fun lateUriClose() {
        val fake = Fake(); val sdk = initialized(fake)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fake.openEntered = entered; fake.openRelease = release
        val operation = sdk.transfers.accept(42, Uri.parse("content://test/staging"))
        check(entered.await(5, TimeUnit.SECONDS), "stream acquisition not scheduled")
        sdk.close(); check(operation.result.get().error?.category == ErrorCategory.CANCELLED, "Close cancels acquisition")
        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (fake.released.get() == 0 && System.nanoTime() < deadline) Thread.yield()
        check(fake.released.get() == 1 && fake.aborted.get() == 1 && fake.submissions.isEmpty(), "late sink abort/release exactly once")
    }
    fun stopDuringUri() {
        val fake = Fake(); val sdk = initialized(fake)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fake.openEntered = entered; fake.openRelease = release
        val operation = sdk.transfers.accept(42, Uri.parse("content://test/staging"))
        check(entered.await(5, TimeUnit.SECONDS), "Stop URI acquisition not scheduled")
        val stop = sdk.stop()
        try {
            check(stop.result.get(1, TimeUnit.SECONDS).isSuccess, "Stop must not wait for blocked provider")
            fake.next(2)
            check(operation.result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.CANCELLED, "Stop must cancel pending URI acquisition")
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (fake.released.get() == 0 && System.nanoTime() < deadline) Thread.yield()
            check(stop.result.get(5, TimeUnit.SECONDS).isSuccess && fake.aborted.get() == 1 && fake.released.get() == 1, "Stop URI cleanup exactly once")
        } finally { release.countDown(); sdk.close() }
    }
    private fun closeDrainsCallback() {
        val fake = Fake(); val sdk = initialized(fake)
        val op = sdk.connections.connect(DeviceId(UUID.randomUUID())); val key = fake.next(3)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val callback = op.result.thenAccept {
            val stateRead = CountDownLatch(1)
            Thread { check(sdk.state == SdkState.INITIALIZED, "callback state"); stateRead.countDown() }.start()
            check(stateRead.await(2, TimeUnit.SECONDS), "host callback held command gate")
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS), "callback release")
        }
        fake.receiver.completion(key, result())
        check(entered.await(5, TimeUnit.SECONDS), "running callback")
        val closing = CountDownLatch(1); val closed = CountDownLatch(1)
        Thread { closing.countDown(); sdk.close(); closed.countDown() }.start()
        check(closing.await(5, TimeUnit.SECONDS), "closing thread")
        check(!closed.await(100, TimeUnit.MILLISECONDS), "Close returned before active callback drained")
        release.countDown(); check(closed.await(5, TimeUnit.SECONDS), "Close did not drain")
        callback.get(5, TimeUnit.SECONDS)
    }
}
