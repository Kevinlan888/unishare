package com.unishare.app.data.device

import com.unishare.app.data.sdk.*
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareErrorKind
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Merges peers by stable ID. Trusted peers survive offline events without inventing names. */
class DefaultDeviceRepository(private val session: ShareSessionManager, scope: CoroutineScope) : DeviceRepository {
    private val gate = Any()
    private val peers = LinkedHashMap<DeviceKey, DeviceSummary>()
    private var trusted = emptySet<DeviceKey>()
    private val blocked = HashSet<DeviceKey>()
    private val identityVersions = HashMap<DeviceKey, Long>()
    private var mutationRevision = 0L
    private val mutableDevices = MutableStateFlow<List<DeviceSummary>>(emptyList())
    override val devices: StateFlow<List<DeviceSummary>> = mutableDevices.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect(::observe) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.collect { state ->
                synchronized(gate) {
                    mutationRevision++
                    if (state == SessionState.STOPPED || state == SessionState.CLOSED) {
                        peers.entries.removeAll { !it.value.trusted }
                        peers.replaceAll { _, peer -> peer.copy(online = false, connected = false) }
                        publish()
                    }
                }
                if (state == SessionState.READY || state == SessionState.RUNNING) refresh()
            }
        }
    }

    fun refresh() {
        val (capturedRevision, capturedSession) = synchronized(gate) { mutationRevision to session.snapshotToken }
        if (capturedSession.state !in setOf(SessionState.READY, SessionState.RUNNING)) return
        val (snapshot, trustedSnapshot) = try {
            session.devices() to session.trustedDevices()
        } catch (_: IllegalStateException) {
            // Stop/close can win between the state check and snapshot access. A subsequent
            // READY/RUNNING state refreshes trust; SDK errors remain GatewayExceptions.
            return
        }
        synchronized(gate) {
            // Snapshot acquisition happens outside this lock. Never overwrite a newer event,
            // forget result or lifecycle transition, even if stop/start returns to RUNNING.
            if (mutationRevision != capturedRevision || session.snapshotToken !== capturedSession) return
            trusted = trustedSnapshot.toSet()
            val onlineIds = snapshot.mapTo(HashSet()) { it.id }
            peers.replaceAll { id, peer -> peer.copy(online = id in onlineIds, trusted = id in trusted, connected = peer.connected && id in onlineIds) }
            snapshot.forEach(::mergeOnline)
            trusted.forEach { id ->
                peers.putIfAbsent(id, DeviceSummary(id, "", null, online = false, trusted = true, identityChanged = id in blocked))
            }
            peers.entries.removeAll { !it.value.online && !it.value.trusted }
            mutationRevision++
            publish()
        }
    }

    private fun observe(event: GatewayEvent) {
        if (event is GatewayEvent.PairingCompleted && event.failure == null) {
            synchronized(gate) { mutationRevision++ }
            refresh()
            return
        }
        synchronized(gate) {
            when (event) {
                is GatewayEvent.DeviceOnline -> mergeOnline(event.device)
                is GatewayEvent.DeviceUpdated -> mergeOnline(event.device)
                is GatewayEvent.DeviceOffline -> peers[event.deviceId]?.let { peer ->
                    if (peer.trusted) peers[event.deviceId] = peer.copy(online = false, connected = false) else peers.remove(event.deviceId)
                }
                is GatewayEvent.ConnectionChanged -> peers[event.deviceId]?.let { peers[event.deviceId] = it.copy(connected = event.connected) }
                is GatewayEvent.IdentityChanged -> {
                    identityVersions[event.deviceId] = (identityVersions[event.deviceId] ?: 0) + 1
                    blocked.add(event.deviceId)
                    peers[event.deviceId]?.let { peers[event.deviceId] = it.copy(identityChanged = true, connected = false) }
                }
                else -> return
            }
            mutationRevision++
            publish()
        }
    }

    private fun mergeOnline(device: DeviceSnapshot) {
        val current = peers[device.id]
        peers[device.id] = DeviceSummary(device.id, device.name, device.platform, online = true,
            trusted = device.id in trusted, connected = current?.connected ?: false,
            identityChanged = device.id in blocked, lastSeenUnixMillis = device.lastSeenUnixMillis)
    }
    private fun publish() { mutableDevices.value = Collections.unmodifiableList(peers.values.toList()) }
    override fun find(deviceId: DeviceKey): DeviceSummary? = synchronized(gate) { peers[deviceId] }
    override fun isTrusted(deviceId: DeviceKey): Boolean = synchronized(gate) { peers[deviceId]?.trusted == true }
    override fun canSend(deviceId: DeviceKey): Boolean = synchronized(gate) { peers[deviceId]?.let { it.online && !it.identityChanged } == true }

    override suspend fun connect(deviceId: DeviceKey) {
        session.requireReady()
        synchronized(gate) { if (deviceId in blocked) throw GatewayException(ShareFailure(ShareErrorKind.IDENTITY_CHANGED)) }
        session.connect(deviceId)
    }
    override suspend fun forget(deviceId: DeviceKey) {
        val identityVersion = synchronized(gate) { identityVersions[deviceId] ?: 0 }
        session.forgetDevice(deviceId)
        synchronized(gate) {
            if ((identityVersions[deviceId] ?: 0) == identityVersion) blocked.remove(deviceId)
            trusted = trusted - deviceId
            peers[deviceId]?.let { peer ->
                if (peer.online) peers[deviceId] = peer.copy(trusted = false, identityChanged = deviceId in blocked, connected = false) else peers.remove(deviceId)
            }
            mutationRevision++
            publish()
        }
    }
}
