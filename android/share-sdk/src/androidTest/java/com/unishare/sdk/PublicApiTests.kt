package com.unishare.sdk

import android.app.Instrumentation
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executor

/** Public consumer tests deliberately use only business API in nativeLifecycle. */
class PublicApiTests : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
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
    override fun onStart() {
        val result = Bundle()
        try {
            for ((name, test) in listOf("nativeLifecycle" to ::nativeLifecycle, "delayedUriClose" to ::delayedUriClose,
                "apiContract" to ApiContractCases::run, "stopDuringUri" to ApiContractCases::stopDuringUri)) {
                val status = Bundle(); status.putString("class", javaClass.name); status.putString("test", name)
                sendStatus(1, status); test(); sendStatus(0, status)
            }
            result.putString("stream", "PASS public Kotlin API cases\n"); finish(-1, result)
        } catch (failure: Throwable) { result.putString("stream", android.util.Log.getStackTraceString(failure)); finish(0, result) }
    }
}
