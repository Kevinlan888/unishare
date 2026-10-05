package com.unishare.sdk.internal

import android.content.Context

internal interface ApiBackend : AutoCloseable {
    fun foreground(value: Boolean)
    fun create(config: ByteArray, key: Long, receiver: SdkNative.Receiver)
    fun submit(command: Int, uuid: ByteArray, request: Long, stream: Long, number: Long,
               text: ByteArray, name: ByteArray, mime: ByteArray, bytes: ByteArray, size: Long, key: Long)
    fun snapshot(trusted: Boolean): ByteArray
    fun state(): Int
    fun open(uri: String, destination: Boolean): Long
    fun release(stream: Long)
    fun abort(stream: Long)
}

internal class JniBackend(context: Context, store: String) : ApiBackend {
    private val platform = NativeBridge(context, store)
    private var handle = 0L
    override fun foreground(value: Boolean) = platform.setForeground(value)
    override fun create(config: ByteArray, key: Long, receiver: SdkNative.Receiver) {
        check(handle == 0L)
        handle = SdkNative.create(platform.platformHandle(), config, key, receiver)
    }
    override fun submit(command: Int, uuid: ByteArray, request: Long, stream: Long, number: Long,
                        text: ByteArray, name: ByteArray, mime: ByteArray, bytes: ByteArray, size: Long, key: Long) {
        SdkNative.submit(handle, command, uuid, request, stream, number, text, name, mime, bytes, size, key)
    }
    override fun snapshot(trusted: Boolean): ByteArray = SdkNative.snapshot(handle, trusted)
    override fun state(): Int = if (handle == 0L) 0 else SdkNative.state(handle)
    override fun open(uri: String, destination: Boolean): Long = platform.openStream(uri, destination)
    override fun release(stream: Long) = NativeBridge.releaseStream(stream)
    override fun abort(stream: Long) = NativeBridge.abortStream(stream)
    override fun close() { if (handle != 0L) { SdkNative.destroy(handle); handle = 0 }; platform.close() }
}
