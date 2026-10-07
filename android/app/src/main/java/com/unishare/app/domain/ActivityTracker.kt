package com.unishare.app.domain

import com.unishare.app.data.history.ActivityRepository
import com.unishare.app.data.sdk.IncomingOffer
import com.unishare.app.data.sdk.ShareFailure
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process activity index. Live state is installed before persistence, so synchronous SDK
 * callbacks can be replayed safely while history hydration and Room writes finish in order.
 */
class ActivityTracker(
    private val repository: ActivityRepository,
    private val scope: CoroutineScope,
    private val idFactory: () -> ActivityKey = { ActivityKey(UUID.randomUUID().toString()) },
) {
    private data class DeviceState(
        val mutable: MutableStateFlow<List<ShareActivity>> = MutableStateFlow(emptyList()),
        val values: LinkedHashMap<ActivityKey, ShareActivity> = LinkedHashMap(),
        val liveIds: MutableSet<ActivityKey> = HashSet(),
        val loadedIds: MutableSet<ActivityKey> = HashSet(),
        val hydrated: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private data class Persist(val deviceId: DeviceKey, val activity: ShareActivity)

    private val gate = Any()
    private val devices = LinkedHashMap<DeviceKey, DeviceState>()
    private val byId = HashMap<ActivityKey, ShareActivity>()
    private val operationToActivity = HashMap<OperationKey, ActivityKey>()
    private val transferToActivity = HashMap<TransferKey, ActivityKey>()
    private val pendingTransferByOperation = LinkedHashMap<OperationKey, Pair<TransferKey, Long>>()
    private val pendingEvents = LinkedHashMap<TransferKey, MutableList<ActivityEvent>>()
    private val previews = HashMap<ActivityKey, ActivityPreview>()
    private val persistence = Channel<Persist>(Channel.UNLIMITED)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (write in persistence) {
                stateFor(write.deviceId).hydrated.await()
                repository.upsert(write.activity)
            }
        }
    }

    fun activities(device: DeviceKey): StateFlow<List<ShareActivity>> = stateFor(device).mutable.asStateFlow()

    fun find(activityId: ActivityKey): ShareActivity? = synchronized(gate) { byId[activityId] }

    fun findByOperation(operationId: OperationKey): ShareActivity? = synchronized(gate) {
        operationToActivity[operationId]?.let(byId::get)
    }

    fun findByTransfer(transferId: TransferKey): ShareActivity? = synchronized(gate) {
        transferToActivity[transferId]?.let(byId::get)
    }

    fun preview(activityId: ActivityKey): ActivityPreview? = synchronized(gate) { previews[activityId] }

    fun setPreview(activityId: ActivityKey, preview: ActivityPreview) = synchronized(gate) {
        if (activityId in byId) previews[activityId] = preview
    }

    fun reserve(activity: ShareActivity, preview: ActivityPreview? = null): ActivityKey = synchronized(gate) {
        val sanitized = sanitize(activity)
        byId[sanitized.id]?.let { return@synchronized it.id }
        val state = stateFor(sanitized.deviceId)
        state.values[sanitized.id] = sanitized
        state.liveIds += sanitized.id
        byId[sanitized.id] = sanitized
        sanitized.operationId?.let { claimOperation(sanitized.id, it) }
        preview?.let { previews[sanitized.id] = it }
        publish(state)
        enqueue(sanitized)
        sanitized.transferId?.let { transferId ->
            if (claimTransfer(sanitized.id, transferId)) {
                pendingEvents.remove(transferId).orEmpty().forEach { update(sanitized.id, it) }
            }
        }
        sanitized.id
    }

    fun registerIncoming(offer: IncomingOffer, atMillis: Long): ActivityKey = synchronized(gate) {
        transferToActivity[offer.transferId]?.takeIf(::isLiveLocked)?.let { return@synchronized it }
        val activity = ShareActivity(
            id = idFactory(),
            deviceId = offer.deviceId,
            direction = ShareDirection.INCOMING,
            kind = offer.kind,
            origin = ShareOrigin.DIRECT,
            displayName = offer.displayName,
            mimeType = offer.mimeType,
            byteCount = offer.byteCount.coerceAtLeast(0),
            createdAtMillis = atMillis,
            updatedAtMillis = atMillis,
            transferId = offer.transferId,
        )
        reserve(activity)
    }

    fun reserveRetry(originalId: ActivityKey, atMillis: Long): ActivityKey = synchronized(gate) {
        val original = requireNotNull(byId[originalId]) { "Unknown activity" }
        check(original.status.isTerminal) { "Only terminal activities can be retried" }
        val retry = original.copy(
            id = idFactory(),
            createdAtMillis = atMillis,
            updatedAtMillis = atMillis,
            transferId = null,
            operationId = null,
            retryOf = original.id,
            status = ActivityStatus.AWAITING_PEER,
            transferredBytes = 0,
            completedAtMillis = null,
            errorKind = null,
            finalContentUri = null,
        )
        previews[originalId]?.let { previews[retry.id] = it }
        reserve(retry)
    }

    fun bindOperation(activityId: ActivityKey, operationId: OperationKey, atMillis: Long) = synchronized(gate) {
        val existing = operationToActivity[operationId]
        if (existing != null && existing != activityId && isLiveLocked(existing)) return@synchronized
        update(activityId, ActivityEvent.OperationBound(operationId, atMillis))
        operationToActivity[operationId] = activityId
        pendingTransferByOperation.remove(operationId)?.let { (transferId, boundAt) ->
            bindTransferNow(activityId, transferId, boundAt)
        }
    }

    fun bindTransfer(operationId: OperationKey, transferId: TransferKey, atMillis: Long) = synchronized(gate) {
        val activityId = operationToActivity[operationId]
        if (activityId == null) pendingTransferByOperation.putIfAbsent(operationId, transferId to atMillis)
        else bindTransferNow(activityId, transferId, atMillis)
    }

    fun progress(transferId: TransferKey, transferredBytes: Long, atMillis: Long) = synchronized(gate) {
        applyTransferEvent(transferId, ActivityEvent.Progress(transferredBytes, atMillis))
    }

    fun terminal(transferId: TransferKey, failure: ShareFailure?, atMillis: Long) = synchronized(gate) {
        val event = when (failure?.kind) {
            null -> ActivityEvent.Completed(atMillis)
            ShareErrorKind.TRANSFER_REJECTED -> ActivityEvent.Rejected(atMillis)
            ShareErrorKind.CANCELLED -> ActivityEvent.Cancelled(atMillis)
            else -> ActivityEvent.Failed(failure.kind, atMillis)
        }
        applyTransferEvent(transferId, event)
    }

    fun completeIncoming(activityId: ActivityKey, atMillis: Long, finalContentUri: String? = null) = synchronized(gate) {
        update(activityId, ActivityEvent.Completed(atMillis, finalContentUri))
    }

    fun fail(activityId: ActivityKey, error: ShareErrorKind, atMillis: Long) = synchronized(gate) {
        val event = when (error) {
            ShareErrorKind.TRANSFER_REJECTED -> ActivityEvent.Rejected(atMillis)
            ShareErrorKind.CANCELLED -> ActivityEvent.Cancelled(atMillis)
            else -> ActivityEvent.Failed(error, atMillis)
        }
        update(activityId, event)
    }

    private fun bindTransferNow(activityId: ActivityKey, transferId: TransferKey, atMillis: Long) {
        val existing = transferToActivity[transferId]
        if (existing != null && existing != activityId && isLiveLocked(existing)) return
        update(activityId, ActivityEvent.TransferBound(transferId, atMillis))
        transferToActivity[transferId] = activityId
        pendingEvents.remove(transferId).orEmpty().forEach { update(activityId, it) }
    }

    private fun applyTransferEvent(transferId: TransferKey, event: ActivityEvent) {
        val activityId = transferToActivity[transferId]
        if (activityId == null) pendingEvents.getOrPut(transferId) { mutableListOf() }.add(event)
        else update(activityId, event)
    }

    private fun update(activityId: ActivityKey, event: ActivityEvent) {
        val current = byId[activityId] ?: return
        val updated = ActivityReducer.reduce(current, event)
        if (updated === current) return
        val state = stateFor(current.deviceId)
        state.values[activityId] = updated
        state.liveIds += activityId
        byId[activityId] = updated
        updated.operationId?.let { claimOperation(activityId, it) }
        updated.transferId?.let { claimTransfer(activityId, it) }
        publish(state)
        enqueue(updated)
    }

    private fun claimOperation(activityId: ActivityKey, operationId: OperationKey): Boolean {
        val existing = operationToActivity[operationId]
        if (existing != null && existing != activityId && isLiveLocked(existing)) return false
        operationToActivity[operationId] = activityId
        return true
    }

    private fun claimTransfer(activityId: ActivityKey, transferId: TransferKey): Boolean {
        val existing = transferToActivity[transferId]
        if (existing != null && existing != activityId && isLiveLocked(existing)) return false
        transferToActivity[transferId] = activityId
        return true
    }

    private fun isLiveLocked(activityId: ActivityKey): Boolean {
        val activity = byId[activityId] ?: return false
        return devices[activity.deviceId]?.liveIds?.contains(activityId) == true
    }

    private fun stateFor(device: DeviceKey): DeviceState = synchronized(gate) {
        devices[device]?.let { return@synchronized it }
        val state = DeviceState()
        devices[device] = state
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.observeDevice(device).collect { loaded -> mergeHistory(device, loaded) }
        }
        state
    }

    private fun mergeHistory(device: DeviceKey, loaded: List<ShareActivity>) = synchronized(gate) {
        val state = devices[device] ?: return@synchronized
        state.loadedIds.filterNot { it in state.liveIds }.forEach { id ->
            state.values.remove(id)?.let(::unindex)
            byId.remove(id)
        }
        state.loadedIds.clear()
        loaded.forEach { raw ->
            val activity = sanitize(raw)
            val liveCollision = activity.id in state.liveIds ||
                activity.transferId?.let { transfer ->
                    transferToActivity[transfer]?.let { it in state.liveIds } == true
            } == true
            if (!liveCollision) {
                state.values[activity.id]?.let(::unindex)
                state.values[activity.id] = activity
                state.loadedIds += activity.id
                byId[activity.id] = activity
                activity.operationId?.let { operationToActivity.putIfAbsent(it, activity.id) }
                activity.transferId?.let { transferToActivity.putIfAbsent(it, activity.id) }
            }
        }
        publish(state)
        state.hydrated.complete(Unit)
    }

    private fun unindex(activity: ShareActivity) {
        activity.operationId?.let { operation ->
            if (operationToActivity[operation] == activity.id) operationToActivity.remove(operation)
        }
        activity.transferId?.let { transfer ->
            if (transferToActivity[transfer] == activity.id) transferToActivity.remove(transfer)
        }
    }

    private fun publish(state: DeviceState) {
        state.mutable.value = Collections.unmodifiableList(
            state.values.values.sortedWith(compareBy<ShareActivity> { it.createdAtMillis }.thenBy { it.id.value }),
        )
    }

    private fun enqueue(activity: ShareActivity) {
        check(persistence.trySend(Persist(activity.deviceId, sanitize(activity))).isSuccess)
    }

    private fun sanitize(activity: ShareActivity): ShareActivity {
        val fallback = when (activity.kind) {
            ContentKind.TEXT -> "Text"
            ContentKind.IMAGE -> "Image"
            ContentKind.FILE -> "File"
        }
        val name = activity.displayName.replace('\\', '/').substringAfterLast('/')
            .filterNot(Char::isISOControl).trim().take(180)
            .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: fallback
        val mime = activity.mimeType.filterNot(Char::isISOControl).trim().take(180).ifEmpty {
            when (activity.kind) {
                ContentKind.TEXT -> "text/plain"
                ContentKind.IMAGE -> "image/*"
                ContentKind.FILE -> "application/octet-stream"
            }
        }
        return if (name == activity.displayName && mime == activity.mimeType) activity
        else activity.copy(displayName = name, mimeType = mime)
    }
}
