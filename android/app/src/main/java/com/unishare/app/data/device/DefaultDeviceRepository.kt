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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Merges peers by stable ID. Trusted peers survive offline events without inventing names. */
class DefaultDeviceRepository(private val session: ShareSessionManager, scope: CoroutineScope) : DeviceRepository {
    private val gate = Any()
    private val peers = LinkedHashMap<DeviceKey, DeviceSummary>()
    private var trusted = emptySet<DeviceKey>()
    private val blocked = HashSet<DeviceKey>()
    private val identityVersions = HashMap<DeviceKey, Long>()
    private val connections = HashMap<DeviceKey, Mutex>()
    private val connectedInSession = HashMap<DeviceKey, SessionSnapshotToken>()
    private var mutationRevision = 0L
    private val mutableDevices = MutableStateFlow<List<DeviceSummary>>(emptyList())
    override val devices: StateFlow<List<DeviceSummary>> = mutableDevices.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { session.sequencedEvents.collect(::observe) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.collect { state ->
                synchronized(gate) {
                    mutationRevision++
                    if (state == SessionState.STOPPED || state == SessionState.CLOSED) {
                        connectedInSession.clear()
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
            peers.replaceAll { id, peer -> peer.copy(online = id in onlineIds, trusted = id in trusted,
                connected = peer.connected && connectedInSession[id] === capturedSession) }
            snapshot.forEach(::mergeOnline)
            trusted.forEach { id ->
                peers.putIfAbsent(id, DeviceSummary(id, "", null, online = false, trusted = true, identityChanged = id in blocked))
            }
            peers.entries.removeAll { !it.value.online && !it.value.connected && !it.value.trusted && !it.value.identityChanged }
            mutationRevision++
            publish()
        }
    }

    private fun observe(delivery: GatewayEventEnvelope) {
        val event = delivery.event
        if (event is GatewayEvent.PairingCompleted && event.failure == null) {
            if (!session.applyIfCurrentEvent(delivery) {
                synchronized(gate) { mutationRevision++ }
            }) {
                return
            }
            refresh()
            return
        }
        session.applyIfCurrentEvent(delivery) {
            synchronized(gate) { when (event) {
                is GatewayEvent.DeviceOnline -> mergeOnline(event.device)
                is GatewayEvent.DeviceUpdated -> mergeOnline(event.device)
                is GatewayEvent.DeviceOffline -> peers[event.deviceId]?.let { peer ->
                    if (peer.trusted || peer.connected || peer.identityChanged) peers[event.deviceId] = peer.copy(online = false)
                    else peers.remove(event.deviceId)
                }
                is GatewayEvent.ConnectionChanged -> {
                    if (event.connected) connectedInSession[event.deviceId] = session.snapshotToken
                    else connectedInSession.remove(event.deviceId)
                    val peer = peers[event.deviceId]
                    if (event.connected) {
                        peers[event.deviceId] = (peer ?: DeviceSummary(
                            event.deviceId, "", null, online = false, trusted = event.deviceId in trusted,
                            identityChanged = event.deviceId in blocked,
                        )).copy(connected = true)
                    } else if (peer != null) {
                        val disconnected = peer.copy(connected = false)
                        if (disconnected.shouldRetain()) peers[event.deviceId] = disconnected else peers.remove(event.deviceId)
                    }
                }
                is GatewayEvent.PairingRequested -> mergeKnown(event.device)
                is GatewayEvent.PairingCompleted -> {
                    if (event.failure != null) peers[event.deviceId]?.let { peer ->
                        if (!peer.shouldRetain()) peers.remove(event.deviceId)
                    }
                }
                is GatewayEvent.IdentityChanged -> {
                    identityVersions[event.deviceId] = (identityVersions[event.deviceId] ?: 0) + 1
                    blocked.add(event.deviceId)
                    peers[event.deviceId]?.let { peers[event.deviceId] = it.copy(identityChanged = true, connected = false) }
                }
                else -> return@applyIfCurrentEvent
            }
            mutationRevision++
            publish()
            }
        }
    }

    private fun mergeOnline(device: DeviceSnapshot) {
        val current = peers[device.id]
        peers[device.id] = DeviceSummary(device.id, device.name, device.platform, online = true,
            trusted = device.id in trusted, connected = current?.connected ?: false,
            identityChanged = device.id in blocked, lastSeenUnixMillis = device.lastSeenUnixMillis)
    }
    private fun mergeKnown(device: DeviceSnapshot) {
        val current = peers[device.id]
        peers[device.id] = DeviceSummary(
            id = device.id,
            name = device.name,
            platform = device.platform,
            online = current?.online == true,
            trusted = current?.trusted ?: (device.id in trusted),
            connected = current?.connected == true,
            identityChanged = device.id in blocked,
            lastSeenUnixMillis = device.lastSeenUnixMillis,
        )
    }
    private fun DeviceSummary.shouldRetain() = online || connected || trusted || identityChanged
    private fun publish() { mutableDevices.value = Collections.unmodifiableList(peers.values.toList()) }
    override fun find(deviceId: DeviceKey): DeviceSummary? = synchronized(gate) { peers[deviceId] }
    override fun isTrusted(deviceId: DeviceKey): Boolean = synchronized(gate) { peers[deviceId]?.trusted == true }
    override fun canSend(deviceId: DeviceKey): Boolean = synchronized(gate) {
        peers[deviceId]?.let { (it.online || it.connected) && it.trusted && !it.identityChanged } == true
    }

    override suspend fun connect(deviceId: DeviceKey) {
        val connection = synchronized(gate) { connections.getOrPut(deviceId) { Mutex() } }
        // Native operations outlive their caller. Keep this peer's slot until completion so
        // Activity disposal cannot allow a second pairing operation to overtake the first.
        connection.withLock {
            withContext(NonCancellable) {
                session.requireReady()
                val token = session.snapshotToken
                synchronized(gate) {
                    if (deviceId in blocked) throw GatewayException(ShareFailure(ShareErrorKind.IDENTITY_CHANGED))
                    if (peers[deviceId]?.connected == true && connectedInSession[deviceId] === token) return@withContext
                }
                session.connect(deviceId)
                if (session.snapshotToken !== token) throw GatewayException(ShareFailure(ShareErrorKind.INTERRUPTED))
                refresh()
                synchronized(gate) {
                    if (deviceId in blocked) throw GatewayException(ShareFailure(ShareErrorKind.IDENTITY_CHANGED))
                    if (session.snapshotToken !== token) throw GatewayException(ShareFailure(ShareErrorKind.INTERRUPTED))
                    peers[deviceId]?.let { peers[deviceId] = it.copy(connected = true) }
                    connectedInSession[deviceId] = token
                    mutationRevision++
                    publish()
                }
            }
        }
    }
    override suspend fun forget(deviceId: DeviceKey) {
        val identityVersion = synchronized(gate) { identityVersions[deviceId] ?: 0 }
        session.forgetDevice(deviceId)
        synchronized(gate) {
            connectedInSession.remove(deviceId)
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
