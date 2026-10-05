package com.unishare.sdk

import android.app.Instrumentation
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executor

/** Public consumer tests deliberately use only business API in nativeLifecycle. */
class PublicApiTests : Instrumentation() {
    private var selectedCase: String? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); selectedCase = arguments?.getString("case"); start() }
    private fun check(value: Boolean, why: String) { if (!value) throw AssertionError(why) }
    private fun nativeLifecycle() {
        val sdk = ShareSdk(targetContext, "public-${UUID.randomUUID()}", Executor { it.run() })
        try {
            val submitter = Thread.currentThread()
            val callbacks = AtomicInteger()
            val initialized = sdk.initialize(SdkConfig("Kotlin 世界"))
            val initializedCallback = initialized.result.thenAccept { check(Thread.currentThread() !== submitter, "callback ran on submitting stack"); callbacks.incrementAndGet() }
            check(initialized.result.get(15, TimeUnit.SECONDS).isSuccess, "initialize")
            initializedCallback.get(15, TimeUnit.SECONDS)
            check(initialized.id != 0L && sdk.state == SdkState.INITIALIZED, "operation/state")
            check(sdk.discovery.devices().isEmpty(), "initial devices")
            check(sdk.connections.trustedDevices().isEmpty(), "initial trust")
            sdk.setForeground(true)
            check(sdk.start().result.get(15, TimeUnit.SECONDS).isSuccess, "start")
            check(sdk.state == SdkState.RUNNING, "running state")
            val invalid = sdk.transfers.sendText(DeviceId(UUID.randomUUID()), "hello\u0000世界")
            check(invalid.result.get(15, TimeUnit.SECONDS).error?.category == ErrorCategory.AUTHENTICATION_FAILED, "unauthenticated peer error category")
            val beforeText = targetContext.cacheDir.listFiles()!!.filter { it.name.startsWith("unishare-text-") }.map { it.name }.toSet()
            val large = sdk.transfers.sendText(DeviceId(UUID.randomUUID()), "x".repeat(8388608))
            check(large.result.get(15, TimeUnit.SECONDS).error?.category == ErrorCategory.AUTHENTICATION_FAILED, "8MiB must reach peer validation through stream JNI")
            val overflow = sdk.transfers.sendText(DeviceId(UUID.randomUUID()), "x".repeat(8388609))
            check(overflow.result.get(15, TimeUnit.SECONDS).error?.category == ErrorCategory.RESOURCE_LIMIT, "over-limit text must fail before native submission")
            check(targetContext.cacheDir.listFiles()!!.filter { it.name.startsWith("unishare-text-") }.map { it.name }.toSet() == beforeText, "native failure must clean text staging")
            val staging = android.net.Uri.parse("content://${targetContext.packageName}.probe/public-staging-${UUID.randomUUID()}")
            val accept = sdk.transfers.accept(99999, staging)
            check(accept.result.get(15, TimeUnit.SECONDS).error?.category == ErrorCategory.INVALID_INPUT, "unknown incoming offer")
            check(!java.io.File(targetContext.cacheDir, staging.lastPathSegment!!).exists(), "failed Accept must remove staging destination")
            val peer = DeviceId(UUID.randomUUID())
            check(sdk.connections.confirmPairing(0, false).result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.INVALID_INPUT, "pairing confirmation JNI")
            check(sdk.connections.rejectPairing(0).result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.INVALID_INPUT, "pairing rejection JNI")
            check(sdk.connections.forgetDevice(peer).result.get(5, TimeUnit.SECONDS).isSuccess, "forget trust JNI")
            check(sdk.transfers.reject(999).result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.INVALID_INPUT, "incoming rejection JNI")
            check(sdk.transfers.cancel(TransferId(UUID.randomUUID())).result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.INVALID_INPUT, "transfer cancellation JNI")
            val source = java.io.File(targetContext.cacheDir, "public-source-${UUID.randomUUID()}")
            source.writeBytes(byteArrayOf(1, 2, 3))
            val sourceUri = android.net.Uri.parse("content://${targetContext.packageName}.probe/${source.name}")
            check(sdk.transfers.sendImage(peer, sourceUri, "image/png", 3).result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.AUTHENTICATION_FAILED, "image source JNI")
            check(sdk.transfers.sendFile(peer, sourceUri, "file", 3).result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.AUTHENTICATION_FAILED, "file source JNI")
            check(source.readBytes().contentEquals(byteArrayOf(1, 2, 3)), "failed sends preserve source data"); source.delete()
            check(sdk.clipboard.writeContent(ShareContent.Text("public 世界\u0000")).result.get(5, TimeUnit.SECONDS).isSuccess, "clipboard write JNI")
            val read = sdk.clipboard.readShareableContent().result.get(5, TimeUnit.SECONDS)
            if (read.isSuccess) check((read.content as ShareContent.Text).text == "public 世界\u0000", "clipboard UTF-8 borrowed copy")
            else check(read.error?.category in listOf(ErrorCategory.INVALID_INPUT, ErrorCategory.PERMISSION_DENIED), "clipboard platform failure category")
            check(sdk.clipboard.writeContent(ShareContent.Image("image/png", byteArrayOf())).result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.INVALID_INPUT, "invalid clipboard image JNI")
            check(sdk.stop().result.get(15, TimeUnit.SECONDS).isSuccess, "stop")
            check(sdk.state == SdkState.STOPPED, "stopped state")
            check(callbacks.get() == 1, "completion once")
        } finally { sdk.close() }
        check(sdk.state == SdkState.CLOSED, "closed state")
    }
    private fun delayedUriClose() {
        com.unishare.sdk.internal.ProbeProvider.delayedOpenEntered = java.util.concurrent.CountDownLatch(1)
        com.unishare.sdk.internal.ProbeProvider.delayedOpenRelease = java.util.concurrent.CountDownLatch(1)
        com.unishare.sdk.internal.ProbeProvider.delayedDelete = java.util.concurrent.CountDownLatch(1)
        val sdk = ShareSdk(targetContext, "late-uri-${UUID.randomUUID()}")
        check(sdk.initialize(SdkConfig("late uri")).result.get(15, TimeUnit.SECONDS).isSuccess, "late URI initialize")
        val uri = android.net.Uri.parse("content://${targetContext.packageName}.probe/delayed-public-${UUID.randomUUID()}")
        val operation = sdk.transfers.accept(999, uri)
        val callbacks = AtomicInteger(); operation.result.thenAccept { callbacks.incrementAndGet() }
        try {
            check(com.unishare.sdk.internal.ProbeProvider.delayedOpenEntered.await(5, TimeUnit.SECONDS), "provider did not open")
            val closed = java.util.concurrent.CountDownLatch(1)
            Thread { sdk.close(); closed.countDown() }.start()
            check(closed.await(3, TimeUnit.SECONDS), "Close waited for blocked URI provider")
            check(operation.result.get().error?.category == ErrorCategory.CANCELLED && callbacks.get() == 1, "late URI terminal result")
        } finally { com.unishare.sdk.internal.ProbeProvider.delayedOpenRelease.countDown(); sdk.close() }
        check(com.unishare.sdk.internal.ProbeProvider.delayedDelete.await(5, TimeUnit.SECONDS), "late destination was not deleted")
        check(callbacks.get() == 1 && !java.io.File(targetContext.cacheDir, uri.lastPathSegment!!).exists(), "late destination cleanup/no callback after Close")
    }
    private fun initializationRetry() {
        val sdk = ShareSdk(targetContext, "retry-${UUID.randomUUID()}")
        try {
            check(sdk.initialize(SdkConfig("")).result.get(15, TimeUnit.SECONDS).error?.category == ErrorCategory.INVALID_INPUT,
                "native invalid initialization")
            check(sdk.initialize(SdkConfig("retry after native failure")).result.get(15, TimeUnit.SECONDS).isSuccess,
                "same public wrapper retries the native SDK")
            check(sdk.state == SdkState.INITIALIZED, "retry initialized state")
        } finally { sdk.close() }
    }
    private fun focusedClipboard() {
        ClipboardHostActivity.focused = java.util.concurrent.CountDownLatch(1)
        val activity = startActivitySync(android.content.Intent(targetContext, ClipboardHostActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            check(ClipboardHostActivity.focused.await(10, TimeUnit.SECONDS), "clipboard test host focus unavailable")
            ShareSdk(targetContext, "clipboard-${UUID.randomUUID()}").use { sdk ->
                check(sdk.initialize(SdkConfig("clipboard")).result.get(15, TimeUnit.SECONDS).isSuccess, "clipboard initialize")
                val text = "explicit 世界"
                check(sdk.clipboard.writeContent(ShareContent.Text(text)).result.get(5, TimeUnit.SECONDS).isSuccess, "focused text write")
                val readText = sdk.clipboard.readShareableContent().result.get(5, TimeUnit.SECONDS)
                check(readText.isSuccess && (readText.content as ShareContent.Text).text == text, "focused ClipboardManager text roundtrip: $readText")
                val bytes = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9GQAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
                check(sdk.clipboard.writeContent(ShareContent.Image("image/png", bytes)).result.get(5, TimeUnit.SECONDS).isSuccess, "focused image write")
                val readImage = sdk.clipboard.readShareableContent().result.get(5, TimeUnit.SECONDS)
                check(readImage.isSuccess && (readImage.content as ShareContent.Image).bytes.contentEquals(bytes), "focused ClipboardManager image/ContentResolver roundtrip")
                runOnMainSync { targetContext.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newIntent("unsupported", android.content.Intent("fixture"))) }
                check(sdk.clipboard.readShareableContent().result.get(5, TimeUnit.SECONDS).error?.category == ErrorCategory.INVALID_INPUT, "actual unsupported clipboard InvalidInput")
            }
        } finally { runOnMainSync { activity.finish() } }
    }
    override fun onStart() {
        val result = Bundle()
        try {
            for ((name, test) in listOf("nativeLifecycle" to ::nativeLifecycle, "delayedUriClose" to ::delayedUriClose,
                "initializationRetry" to ::initializationRetry,
                "apiContract" to ApiContractCases::run, "stopDuringUri" to ApiContractCases::stopDuringUri,
                "identityAcceptance" to { com.unishare.sdk.internal.IdentityAcceptance.run(targetContext) },
                "focusedClipboard" to ::focusedClipboard,
                "acceptance" to { AcceptanceCases.run(targetContext) })) {
                if (selectedCase != null && selectedCase != name) continue
                val status = Bundle(); status.putString("class", javaClass.name); status.putString("test", name)
                sendStatus(1, status); test(); sendStatus(0, status)
            }
            result.putString("stream", "PASS public Kotlin API cases\n"); finish(-1, result)
        } catch (failure: Throwable) { result.putString("stream", android.util.Log.getStackTraceString(failure)); finish(0, result) }
    }
}
