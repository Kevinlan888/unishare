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
    private val mutableDevices = MutableStateFlow<List<DeviceSummary>>(emptyList())
    override val devices: StateFlow<List<DeviceSummary>> = mutableDevices.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect(::observe) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.collect { state ->
                when (state) {
                    SessionState.READY, SessionState.RUNNING -> refresh()
                    SessionState.STOPPED, SessionState.CLOSED -> synchronized(gate) {
                        peers.entries.removeAll { !it.value.trusted }
                        peers.replaceAll { _, peer -> peer.copy(online = false, connected = false) }
                        publish()
                    }
                    else -> Unit
                }
            }
        }
    }

    fun refresh() {
        if (session.state.value !in setOf(SessionState.READY, SessionState.RUNNING)) return
        val (snapshot, trustedSnapshot) = try {
            session.devices() to session.trustedDevices()
        } catch (_: IllegalStateException) {
            // Stop/close can win between the state check and snapshot access. A subsequent
            // READY/RUNNING state refreshes trust; SDK errors remain GatewayExceptions.
            return
        }
        synchronized(gate) {
            trusted = trustedSnapshot.toSet()
            val onlineIds = snapshot.mapTo(HashSet()) { it.id }
            peers.replaceAll { id, peer -> peer.copy(online = id in onlineIds, trusted = id in trusted, connected = peer.connected && id in onlineIds) }
            snapshot.forEach(::mergeOnline)
            trusted.forEach { id ->
                peers.putIfAbsent(id, DeviceSummary(id, "", null, online = false, trusted = true, identityChanged = id in blocked))
            }
            peers.entries.removeAll { !it.value.online && !it.value.trusted }
            publish()
        }
    }

    private fun observe(event: GatewayEvent) {
        if (event is GatewayEvent.PairingCompleted && event.failure == null) {
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
                    blocked.add(event.deviceId)
                    peers[event.deviceId]?.let { peers[event.deviceId] = it.copy(identityChanged = true, connected = false) }
                }
                else -> return
            }
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
        session.forgetDevice(deviceId)
        synchronized(gate) {
            blocked.remove(deviceId)
            trusted = trusted - deviceId
            peers[deviceId]?.let { peer ->
                if (peer.online) peers[deviceId] = peer.copy(trusted = false, identityChanged = false, connected = false) else peers.remove(deviceId)
            }
            publish()
        }
    }
}
