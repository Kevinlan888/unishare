package com.unishare.sdk.internal

import com.unishare.sdk.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.Collections

internal object ApiPackets {
    fun uuid(value: UUID?): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { it.writeLong(value?.mostSignificantBits ?: 0); it.writeLong(value?.leastSignificantBits ?: 0) }
    }.toByteArray()
    fun config(c: SdkConfig): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use {
            val name = c.deviceName.toByteArray(Charsets.UTF_8); it.writeInt(name.size); it.write(name)
            it.writeInt(c.maxConnections); it.writeInt(c.maxPendingRequests); it.writeLong(c.maxBufferBytes)
            it.writeLong(c.maxTextBytes); it.writeLong(c.maxImageBytes); it.writeLong(c.maxFileBytes)
            it.writeInt(c.controlFrameBytes); it.writeInt(c.dataChunkBytes); it.writeInt(c.requestTimeoutSeconds); it.writeInt(c.offlineDebounceSeconds)
        }
    }.toByteArray()
    private fun input(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))
    private fun blob(d: DataInputStream): ByteArray { val size = d.readInt(); require(size >= 0 && size <= d.available()); return ByteArray(size).also { d.readFully(it) } }
    private fun text(d: DataInputStream) = String(blob(d), Charsets.UTF_8)
    private fun uuid(d: DataInputStream) = UUID(d.readLong(), d.readLong())
    private fun error(d: DataInputStream): ShareError? {
        val code = d.readInt(); val hasRaw = d.readBoolean(); val raw = d.readLong()
        return if (code == 0) null else ShareError(ErrorCategory.entries[code - 1], if (hasRaw) raw else null)
    }
    private fun device(d: DataInputStream): DeviceInfo {
        val id = DeviceId(uuid(d)); val name = text(d); val platform = Platform.entries[d.readInt()]
        val version = ProtocolVersion(d.readInt(), d.readInt()); val port = d.readInt(); val paired = d.readBoolean(); val seen = d.readLong()
        val count = d.readInt(); require(count in 0..65536)
        return DeviceInfo(id, name, platform, version, List(count) { text(d) }, port, paired, seen)
    }
    fun result(id: Long, bytes: ByteArray): OperationResult {
        val d = input(bytes); val error = error(d); val transfer = uuid(d)
        val type = d.readInt(); val text = text(d); val mime = text(d); val image = blob(d)
        val content = when (type) { 1 -> ShareContent.Text(text); 2 -> ShareContent.Image(mime, image); else -> null }
        return OperationResult(id, error, if (transfer == UUID(0, 0)) null else TransferId(transfer), content)
    }
    fun devices(bytes: ByteArray): List<DeviceInfo> {
        val d = input(bytes); val count = d.readInt(); require(count in 0..65536)
        return Collections.unmodifiableList(List(count) { device(d) })
    }
    fun trusted(bytes: ByteArray): List<TrustedDevice> {
        val d = input(bytes); val count = d.readInt(); require(count in 0..100000)
        return Collections.unmodifiableList(List(count) { TrustedDevice(DeviceId(uuid(d)), ByteArray(32).also { d.readFully(it) }) })
    }
    fun event(bytes: ByteArray): Any {
        val d = input(bytes); val kind = d.readInt(); val state = d.readInt(); val error = error(d)
        val request = d.readLong(); val transfer = TransferId(uuid(d)); val deviceId = DeviceId(uuid(d))
        val deviceName = text(d); val devicePlatform = d.readInt(); val deviceMajor = d.readInt(); val deviceMinor = d.readInt()
        val devicePort = d.readInt(); val devicePaired = d.readBoolean(); val deviceSeen = d.readLong()
        val addressCount = d.readInt(); require(addressCount in 0..65536)
        val deviceAddresses = List(addressCount) { text(d) }
        fun fullDevice() = DeviceInfo(deviceId, deviceName, Platform.entries[devicePlatform],
            ProtocolVersion(deviceMajor, deviceMinor), deviceAddresses, devicePort, devicePaired, deviceSeen)
        val code = text(d)
        val type = d.readInt(); val name = text(d); val mime = text(d); val size = d.readLong(); val progress = d.readLong()
        return when (kind) {
            1 -> DiscoveryEvent.Online(fullDevice()); 2 -> DiscoveryEvent.Updated(fullDevice()); 3 -> DiscoveryEvent.Offline(fullDevice())
            4 -> DiscoveryEvent.Error(requireNotNull(error)); 5 -> DiscoveryEvent.State(DiscoveryState.entries[state])
            6 -> if (state == 0) ConnectionEvent.Connected(deviceId) else ConnectionEvent.Disconnected(deviceId, error)
            7 -> ConnectionEvent.PairingRequest(request, fullDevice(), code); 8 -> ConnectionEvent.PairingResult(request, deviceId, error)
            9 -> ConnectionEvent.IdentityChanged(deviceId)
            10 -> TransferEvent.IncomingOffer(request, transfer, deviceId, ContentType.entries[type - 1], name, mime, size)
            11 -> TransferEvent.Progress(transfer, progress, size); 12 -> TransferEvent.Result(transfer, error)
            else -> error("Unknown native event $kind")
        }
    }
}
