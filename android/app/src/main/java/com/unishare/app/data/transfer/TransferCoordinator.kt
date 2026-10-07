package com.unishare.app.data.transfer

import android.net.Uri
import com.unishare.app.data.content.PreparedStaging
import com.unishare.app.data.content.ReceiveDestination
import com.unishare.app.data.content.ReceivePublisher
import com.unishare.app.data.content.StagingStore
import com.unishare.app.data.device.DeviceRepository
import com.unishare.app.data.sdk.*
import com.unishare.app.data.settings.SettingsRepository
import com.unishare.app.domain.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface StagingAccess {
    fun create(transferId: TransferKey): PreparedStaging
    fun delete(transferId: TransferKey)
}

internal fun interface ContentPublication {
    fun publish(
        staging: PreparedStaging,
        displayName: String,
        mimeType: String,
        destination: ReceiveDestination,
    ): String
}

/** Stable coordinator failure. Payload/provider exception details are deliberately discarded. */
class CoordinatorException(val errorKind: ShareErrorKind) : RuntimeException(errorKind.name)

/**
 * Owns the app-level transfer continuations. It never creates an SDK instance: all work passes
 * through the application ShareSessionManager and its single gateway.
 */
class TransferCoordinator internal constructor(
    private val session: ShareSessionManager,
    private val tracker: ActivityTracker,
    private val devices: DeviceRepository,
    private val settings: SettingsRepository,
    private val staging: StagingAccess,
    private val publication: ContentPublication,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> ActivityKey = { ActivityKey(UUID.randomUUID().toString()) },
) {
    constructor(
        session: ShareSessionManager,
        tracker: ActivityTracker,
        devices: DeviceRepository,
        settings: SettingsRepository,
        stagingStore: StagingStore,
        receivePublisher: ReceivePublisher,
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        clock: () -> Long = System::currentTimeMillis,
        idFactory: () -> ActivityKey = { ActivityKey(UUID.randomUUID().toString()) },
    ) : this(
        session, tracker, devices, settings,
        object : StagingAccess {
            override fun create(transferId: TransferKey) = stagingStore.create(transferId)
            override fun delete(transferId: TransferKey) = stagingStore.delete(transferId)
        },
        ContentPublication { prepared, name, mime, destination ->
            receivePublisher.publish(prepared, name, mime, destination).toString()
        },
        scope, ioDispatcher, clock, idFactory,
    )

    private enum class OfferDecision { PENDING, ACCEPTING, REJECTING, DONE }
    private data class OfferState(
        val activityId: ActivityKey,
        val offer: IncomingOffer,
        var decision: OfferDecision = OfferDecision.PENDING,
        var prepared: PreparedStaging? = null,
        val terminal: CompletableDeferred<TransferTerminal> = CompletableDeferred(),
    )
    private sealed interface RetryPayload {
        data class Text(val text: String, val origin: ShareOrigin) : RetryPayload
        data class Content(val content: SelectedContent, val origin: ShareOrigin) : RetryPayload
    }
    private data class Recovery(
        val staging: PreparedStaging,
        val displayName: String,
        val mimeType: String,
        val expiresAt: Long,
        val cleanup: Job,
    )

    private val gate = Any()
    private var acceptingWork = true
    private val offers = LinkedHashMap<ActivityKey, OfferState>()
    private val offerByTransfer = HashMap<TransferKey, ActivityKey>()
    private val retryPayloads = HashMap<ActivityKey, RetryPayload>()
    private val outgoingStaging = HashMap<ActivityKey, TransferKey>()
    private val recoveries = HashMap<ActivityKey, Recovery>()
    private val owned = LinkedHashSet<Job>()
    private val active = LinkedHashSet<ActivityKey>()
    private val mutableActiveTransferCount = MutableStateFlow(0)
    val activeTransferCount: StateFlow<Int> = mutableActiveTransferCount.asStateFlow()

    private val eventDelivery = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.events.collect(::onGatewayEvent)
    }

    suspend fun sendText(
        deviceId: DeviceKey,
        text: String,
        origin: ShareOrigin = ShareOrigin.DIRECT,
    ): ActivityKey {
        return runOwned {
            if (text.isBlank()) throw CoordinatorException(ShareErrorKind.INVALID_INPUT)
            ensureCanSubmit(deviceId)
            submitText(deviceId, text, origin, retryOf = null)
        }
    }

    suspend fun sendContent(
        deviceId: DeviceKey,
        content: SelectedContent,
        origin: ShareOrigin = ShareOrigin.DIRECT,
    ): ActivityKey {
        var ownershipAccepted = false
        try {
            return runOwned(onAccepted = { ownershipAccepted = true }) {
                try {
                    ensureCanSubmit(deviceId)
                    if (content.kind == ContentKind.TEXT || content.byteCount < 0) {
                        throw CoordinatorException(ShareErrorKind.INVALID_INPUT)
                    }
                } catch (failure: Exception) {
                    content.readPermission?.close()
                    throw failure
                }
                submitContent(deviceId, content, origin, retryOf = null, ownedStaging = null)
            }
        } finally {
            if (!ownershipAccepted) content.readPermission?.close()
        }
    }

    suspend fun sendClipboard(deviceId: DeviceKey, isForeground: () -> Boolean): ActivityKey? {
        return runOwned { sendClipboardOwned(deviceId, isForeground) }
    }

    private suspend fun sendClipboardOwned(deviceId: DeviceKey, isForeground: () -> Boolean): ActivityKey? {
        ensureCanSubmit(deviceId)
        if (!isForeground()) throw CoordinatorException(ShareErrorKind.PERMISSION_DENIED)
        val clipboard = try { session.readClipboard() } catch (failure: Exception) {
            throw CoordinatorException(failure.stableKind())
        } ?: return null
        return when (clipboard) {
            is ClipboardContent.Text -> {
                if (clipboard.text.isBlank()) null
                else submitText(deviceId, clipboard.text, ShareOrigin.CLIPBOARD, retryOf = null)
            }
            is ClipboardContent.Image -> {
                if (!clipboard.mimeType.lowercase().startsWith("image/") || clipboard.bytes.isEmpty()) return null
                val stagingId = TransferKey(UUID.randomUUID().toString())
                val prepared = try { staging.create(stagingId) } catch (_: Exception) {
                    throw CoordinatorException(ShareErrorKind.STORAGE_FAILED)
                }
                try {
                    prepared.file.outputStream().use { output ->
                        val bytes = clipboard.bytes
                        output.write(bytes)
                        output.flush()
                    }
                    submitContent(
                        deviceId,
                        SelectedContent(prepared.uri, ContentKind.IMAGE, "clipboard.png", clipboard.mimeType, clipboard.bytes.size.toLong()),
                        ShareOrigin.CLIPBOARD,
                        retryOf = null,
                        ownedStaging = stagingId,
                    )
                } catch (failure: Exception) {
                    staging.delete(stagingId)
                    throw failure
                }
            }
        }
    }

    fun pendingOffer(activityId: ActivityKey): IncomingOffer? = synchronized(gate) {
        offers[activityId]?.takeIf { it.decision == OfferDecision.PENDING }?.offer
    }

    internal fun pendingOffers(): List<Pair<ActivityKey, IncomingOffer>> = synchronized(gate) {
        offers.values.filter { it.decision == OfferDecision.PENDING }.map { it.activityId to it.offer }
    }

    suspend fun accept(activityId: ActivityKey): Boolean {
        return runOwned { acceptOwned(activityId) }
    }

    private suspend fun acceptOwned(activityId: ActivityKey): Boolean {
        val state = synchronized(gate) {
            ensureAcceptingLocked()
            offers[activityId]?.takeIf { it.decision == OfferDecision.PENDING }?.also {
                it.decision = OfferDecision.ACCEPTING
            }
        } ?: return false
        val prepared = try {
            staging.create(state.offer.transferId).also { state.prepared = it }
        } catch (_: Exception) {
            finishOfferFailure(state, ShareErrorKind.STORAGE_FAILED)
            return true
        }
        addActive(activityId)
        val acknowledgement = try {
            session.accept(state.offer.requestId, prepared.uri)
        } catch (failure: Exception) {
            TransferTerminal(state.offer.transferId, ShareFailure(failure.stableKind()))
        }
        if (acknowledgement.failure != null && synchronized(gate) { offers[activityId] === state }) {
            withContext(ioDispatcher) { staging.delete(state.offer.transferId) }
            finishOfferFailure(state, acknowledgement.failure.kind)
            removeActive(activityId)
        } else if (synchronized(gate) { offers[activityId] === state }) {
            launchOwned { completeAcceptedIncoming(state, state.terminal.await()) }
        }
        return true
    }

    suspend fun reject(activityId: ActivityKey): Boolean {
        return runOwned { rejectOwned(activityId) }
    }

    private suspend fun rejectOwned(activityId: ActivityKey): Boolean {
        val state = synchronized(gate) {
            ensureAcceptingLocked()
            offers[activityId]?.takeIf { it.decision == OfferDecision.PENDING }?.also {
                it.decision = OfferDecision.REJECTING
            }
        } ?: return false
        try {
            session.reject(state.offer.requestId)
            tracker.fail(activityId, ShareErrorKind.TRANSFER_REJECTED, clock())
            finishOfferState(state)
        } catch (failure: Exception) {
            synchronized(gate) {
                if (offers[state.activityId] === state) state.decision = OfferDecision.PENDING
            }
            throw CoordinatorException(failure.stableKind())
        }
        return true
    }

    suspend fun cancel(activityId: ActivityKey): Boolean {
        return runOwned {
            ensureAccepting()
            val activity = tracker.find(activityId) ?: return@runOwned false
            if (activity.status.isTerminal) return@runOwned false
            val transferId = activity.transferId ?: return@runOwned false
            try {
                session.cancel(transferId)
                true
            } catch (failure: Exception) {
                throw CoordinatorException(failure.stableKind())
            }
        }
    }

    /** Notification semantics: cancel the oldest still-active activity that has an SDK ID. */
    suspend fun cancelOldestActive(): Boolean {
        val activityId = synchronized(gate) {
            active.firstOrNull { candidate ->
                tracker.find(candidate)?.let { !it.status.isTerminal && it.transferId != null } == true
            }
        } ?: return false
        return cancel(activityId)
    }

    suspend fun retry(activityId: ActivityKey): ActivityKey {
        return runOwned {
            ensureAccepting()
            val recovery = synchronized(gate) { recoveries.remove(activityId) }
            if (recovery != null) return@runOwned retryPublication(activityId, recovery)
            val original = tracker.find(activityId) ?: throw CoordinatorException(ShareErrorKind.INVALID_INPUT)
            if (!original.status.isTerminal) throw CoordinatorException(ShareErrorKind.INVALID_INPUT)
            val payload = synchronized(gate) { retryPayloads[activityId] }
                ?: throw CoordinatorException(ShareErrorKind.INVALID_INPUT)
            ensureCanSubmit(original.deviceId)
            when (payload) {
                is RetryPayload.Text -> submitText(original.deviceId, payload.text, payload.origin, original.id)
                is RetryPayload.Content -> {
                    synchronized(gate) { retryPayloads.remove(activityId, payload) }
                    submitContent(original.deviceId, payload.content, payload.origin, original.id, null)
                }
            }
        }
    }

    /** Stop intake first, drain app-owned SDK completions, then retire event delivery. */
    suspend fun retire() {
        synchronized(gate) {
            if (!acceptingWork) return@synchronized
            acceptingWork = false
        }
        while (true) {
            val jobs = synchronized(gate) { owned.toList() }
            if (jobs.isEmpty()) break
            jobs.joinAll()
        }
        eventDelivery.cancel()
        eventDelivery.join()
        val abandoned = synchronized(gate) {
            val values = recoveries.values.toList()
            recoveries.clear()
            values
        }
        abandoned.forEach { recovery ->
            recovery.cleanup.cancel()
            staging.delete(recovery.staging.transferId)
        }
        val retiredPayloads = synchronized(gate) {
            retryPayloads.values.toList().also { retryPayloads.clear() }
        }
        retiredPayloads.forEach { payload ->
            (payload as? RetryPayload.Content)?.content?.readPermission?.close()
        }
    }

    private fun submitText(
        deviceId: DeviceKey,
        text: String,
        origin: ShareOrigin,
        retryOf: ActivityKey?,
    ): ActivityKey {
        val activity = outgoingActivity(deviceId, ContentKind.TEXT, origin, "Text", "text/plain", text.toByteArray().size.toLong(), retryOf)
        tracker.reserve(activity, ActivityPreview.Text(text))
        synchronized(gate) { retryPayloads[activity.id] = RetryPayload.Text(text, origin) }
        addActive(activity.id)
        val pending = try { session.beginSendText(deviceId, text) } catch (failure: Exception) {
            removeActive(activity.id)
            tracker.fail(activity.id, failure.stableKind(), clock())
            throw CoordinatorException(failure.stableKind())
        }
        startOutgoing(activity.id, pending)
        return activity.id
    }

    private fun submitContent(
        deviceId: DeviceKey,
        content: SelectedContent,
        origin: ShareOrigin,
        retryOf: ActivityKey?,
        ownedStaging: TransferKey?,
    ): ActivityKey {
        val activity = outgoingActivity(deviceId, content.kind, origin, content.displayName, content.mimeType, content.byteCount, retryOf)
        synchronized(gate) {
            retryPayloads[activity.id] = RetryPayload.Content(content, origin)
            ownedStaging?.let { outgoingStaging[activity.id] = it }
        }
        try {
            tracker.reserve(
                activity,
                preview = if (content.kind == ContentKind.IMAGE) {
                    ActivityPreview.Image(content.uri.toString())
                } else null,
            )
        } catch (failure: Exception) {
            synchronized(gate) {
                retryPayloads.remove(activity.id)
                outgoingStaging.remove(activity.id)
            }
            content.readPermission?.close()
            throw failure
        }
        addActive(activity.id)
        val pending = try {
            when (content.kind) {
                ContentKind.IMAGE -> session.beginSendImage(deviceId, content.uri, content.mimeType, content.byteCount, content.displayName)
                ContentKind.FILE -> session.beginSendFile(deviceId, content.uri, content.displayName, content.byteCount, content.mimeType)
                ContentKind.TEXT -> error("Text content uses sendText")
            }
        } catch (failure: Exception) {
            synchronized(gate) {
                if (outgoingStaging.remove(activity.id) != null) retryPayloads.remove(activity.id)
            }
            removeActive(activity.id)
            tracker.fail(activity.id, failure.stableKind(), clock())
            throw CoordinatorException(failure.stableKind())
        }
        startOutgoing(activity.id, pending)
        return activity.id
    }

    private fun startOutgoing(activityId: ActivityKey, pending: PendingSend) {
        tracker.bindOperation(activityId, pending.operationId, clock())
        if (tracker.find(activityId)?.status?.isTerminal == true) {
            finishOutgoingTerminal(activityId)
            return
        }
        launchOwned {
            val terminal = try { pending.completion.await() } catch (failure: Exception) {
                TransferTerminal(null, ShareFailure(failure.stableKind()))
            }
            val transferId = terminal.transferId
            if (transferId != null) {
                tracker.bindTransfer(pending.operationId, transferId, clock())
                tracker.terminal(transferId, terminal.failure, clock())
            } else {
                tracker.fail(activityId, terminal.failure?.kind ?: ShareErrorKind.UNKNOWN, clock())
            }
            finishOutgoingTerminal(activityId)
        }
    }

    private suspend fun onGatewayEvent(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.OfferReceived -> handleOffer(event.offer)
            is GatewayEvent.OutgoingTransferRegistered -> {
                tracker.bindTransfer(event.binding.operationId, event.binding.transferId, clock())
                tracker.findByTransfer(event.binding.transferId)?.takeIf { it.status.isTerminal }?.let {
                    finishOutgoingTerminal(it.id)
                }
            }
            is GatewayEvent.ProgressReceived -> tracker.progress(event.progress.transferId, event.progress.transferredBytes, clock())
            is GatewayEvent.TransferCompleted -> handleTerminalEvent(event.terminal)
            else -> Unit
        }
    }

    private fun handleOffer(offer: IncomingOffer) {
        synchronized(gate) { if (!acceptingWork) return }
        val activityId = tracker.registerIncoming(offer, clock())
        if (tracker.find(activityId)?.status?.isTerminal == true) return
        val state = synchronized(gate) {
            offers.getOrPut(activityId) { OfferState(activityId, offer) }.also {
                offerByTransfer.putIfAbsent(offer.transferId, activityId)
            }
        }
        if (ReceivePolicy.decide(devices.isTrusted(offer.deviceId), offer.kind) == ReceiveDecision.AUTO_ACCEPT) {
            scope.launch { try { accept(state.activityId) } catch (_: CoordinatorException) { } }
        }
    }

    private suspend fun handleTerminalEvent(terminal: TransferTerminal) {
        val transferId = terminal.transferId ?: return
        val incoming = synchronized(gate) { offerByTransfer[transferId]?.let(offers::get) }
        if (incoming == null) {
            tracker.terminal(transferId, terminal.failure, clock())
            tracker.findByTransfer(transferId)?.takeIf {
                it.direction == ShareDirection.OUTGOING && it.status.isTerminal
            }?.let { finishOutgoingTerminal(it.id) }
        } else {
            val accepted = synchronized(gate) {
                incoming.decision == OfferDecision.ACCEPTING && incoming.prepared != null
            }
            if (accepted) {
                incoming.terminal.complete(terminal)
            } else if (terminal.failure != null) {
                incoming.prepared?.let { withContext(ioDispatcher) { staging.delete(it.transferId) } }
                finishOfferFailure(incoming, terminal.failure.kind)
                removeActive(incoming.activityId)
            }
        }
    }

    private suspend fun completeAcceptedIncoming(state: OfferState, terminal: TransferTerminal) {
        if (synchronized(gate) { offers[state.activityId] !== state }) return
        val prepared = state.prepared ?: return
        if (terminal.failure != null) {
            withContext(ioDispatcher) { staging.delete(state.offer.transferId) }
            finishOfferFailure(state, terminal.failure.kind)
            removeActive(state.activityId)
            return
        }
        when (state.offer.kind) {
            ContentKind.TEXT -> completeIncomingText(state, prepared)
            ContentKind.IMAGE, ContentKind.FILE -> completeIncomingContent(state, prepared)
        }
        removeActive(state.activityId)
    }

    private suspend fun completeIncomingText(state: OfferState, prepared: PreparedStaging) {
        try {
            val text = withContext(ioDispatcher) {
                prepared.file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            tracker.setPreview(state.activityId, ActivityPreview.Text(text))
            withContext(ioDispatcher) { staging.delete(state.offer.transferId) }
            tracker.completeIncoming(state.activityId, clock())
            finishOfferState(state)
        } catch (_: Exception) {
            withContext(ioDispatcher) { staging.delete(state.offer.transferId) }
            finishOfferFailure(state, ShareErrorKind.STORAGE_FAILED)
        }
    }

    private suspend fun completeIncomingContent(state: OfferState, prepared: PreparedStaging) {
        val destination = try {
            currentDestination()
        } catch (_: Exception) {
            finishOfferFailure(state, ShareErrorKind.STORAGE_FAILED)
            rememberRecovery(state, prepared)
            return
        }
        try {
            val finalUri = withContext(ioDispatcher) {
                publication.publish(prepared, state.offer.displayName, state.offer.mimeType, destination).also {
                    staging.delete(state.offer.transferId)
                }
            }
            tracker.completeIncoming(state.activityId, clock(), finalUri)
            finishOfferState(state)
        } catch (_: Exception) {
            finishOfferFailure(state, ShareErrorKind.STORAGE_FAILED)
            rememberRecovery(state, prepared)
        }
    }

    private fun rememberRecovery(
        state: OfferState,
        prepared: PreparedStaging,
        expiresAt: Long = clock() + RECOVERY_MILLIS,
    ) {
        val cleanup = scope.launch {
            delay((expiresAt - clock()).coerceAtLeast(0))
            val expired = synchronized(gate) { recoveries.remove(state.activityId) }
            if (expired != null) staging.delete(expired.staging.transferId)
        }
        synchronized(gate) {
            recoveries[state.activityId] = Recovery(
                prepared, state.offer.displayName, state.offer.mimeType,
                expiresAt = expiresAt,
                cleanup = cleanup,
            )
        }
    }

    private suspend fun retryPublication(originalId: ActivityKey, recovery: Recovery): ActivityKey {
        recovery.cleanup.cancel()
        if (clock() >= recovery.expiresAt) {
            withContext(ioDispatcher) { staging.delete(recovery.staging.transferId) }
            throw CoordinatorException(ShareErrorKind.STORAGE_FAILED)
        }
        val retryId = tracker.reserveRetry(originalId, clock())
        try {
            val destination = currentDestination()
            val finalUri = withContext(ioDispatcher) {
                publication.publish(recovery.staging, recovery.displayName, recovery.mimeType, destination).also {
                    staging.delete(recovery.staging.transferId)
                }
            }
            tracker.completeIncoming(retryId, clock(), finalUri)
        } catch (_: Exception) {
            tracker.fail(retryId, ShareErrorKind.STORAGE_FAILED, clock())
            val original = tracker.find(retryId)!!
            val pseudoState = OfferState(retryId, IncomingOffer(
                requestId = -1,
                transferId = recovery.staging.transferId,
                deviceId = original.deviceId,
                kind = original.kind,
                displayName = recovery.displayName,
                mimeType = recovery.mimeType,
                byteCount = original.byteCount,
            ))
            rememberRecovery(pseudoState, recovery.staging, recovery.expiresAt)
        }
        return retryId
    }

    private fun finishOfferFailure(state: OfferState, kind: ShareErrorKind) {
        tracker.fail(state.activityId, kind, clock())
        finishOfferState(state)
    }

    private fun finishOfferState(state: OfferState) = synchronized(gate) {
        state.decision = OfferDecision.DONE
        offers.remove(state.activityId)
        offerByTransfer.remove(state.offer.transferId, state.activityId)
    }

    private fun outgoingActivity(
        deviceId: DeviceKey,
        kind: ContentKind,
        origin: ShareOrigin,
        displayName: String,
        mimeType: String,
        byteCount: Long,
        retryOf: ActivityKey?,
    ): ShareActivity {
        val now = clock()
        return ShareActivity(
            id = idFactory(), deviceId = deviceId, direction = ShareDirection.OUTGOING,
            kind = kind, origin = origin, displayName = displayName, mimeType = mimeType,
            byteCount = byteCount, createdAtMillis = now, updatedAtMillis = now, retryOf = retryOf,
        )
    }

    private fun addActive(activityId: ActivityKey) = synchronized(gate) {
        if (active.add(activityId)) mutableActiveTransferCount.value = active.size
    }

    private fun removeActive(activityId: ActivityKey) = synchronized(gate) {
        if (active.remove(activityId)) mutableActiveTransferCount.value = active.size
    }

    private fun finishOutgoingTerminal(activityId: ActivityKey) {
        val activity = tracker.find(activityId) ?: return
        if (activity.direction != ShareDirection.OUTGOING || !activity.status.isTerminal) return
        var retiredPayload: RetryPayload? = null
        val staged = synchronized(gate) {
            val ownedStaging = outgoingStaging.remove(activityId)
            if (activity.status == ActivityStatus.COMPLETED || ownedStaging != null) {
                retiredPayload = retryPayloads.remove(activityId)
            }
            ownedStaging
        }
        (retiredPayload as? RetryPayload.Content)?.content?.readPermission?.close()
        staged?.let(staging::delete)
        removeActive(activityId)
    }

    private fun launchOwned(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        synchronized(gate) {
            owned += job
        }
        job.invokeOnCompletion { synchronized(gate) { owned -= job } }
        job.start()
        return job
    }

    private suspend fun <T> runOwned(
        onAccepted: () -> Unit = {},
        block: suspend () -> T,
    ): T {
        val outcome = CompletableDeferred<Result<T>>()
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                outcome.complete(Result.success(block()))
            } catch (failure: Exception) {
                outcome.complete(Result.failure(failure))
            }
        }
        try {
            synchronized(gate) {
                ensureAcceptingLocked()
                owned += task
                onAccepted()
            }
        } catch (failure: Exception) {
            task.cancel()
            throw failure
        }
        task.invokeOnCompletion { failure ->
            if (failure != null) outcome.complete(Result.failure(failure))
            synchronized(gate) { owned -= task }
        }
        task.start()
        return outcome.await().getOrThrow()
    }

    private suspend fun currentDestination(): ReceiveDestination =
        settings.settings.first().receiveTreeUri?.let { ReceiveDestination.SafTree(Uri.parse(it)) }
            ?: ReceiveDestination.Downloads

    private fun ensureCanSubmit(deviceId: DeviceKey) {
        ensureAccepting()
        if (!devices.canSend(deviceId)) throw CoordinatorException(ShareErrorKind.NETWORK_LOST)
    }

    private fun ensureAccepting() = synchronized(gate) { ensureAcceptingLocked() }
    private fun ensureAcceptingLocked() {
        if (!acceptingWork) throw CoordinatorException(ShareErrorKind.INTERRUPTED)
    }

    private fun Throwable.stableKind(): ShareErrorKind =
        (this as? GatewayException)?.failure?.kind
            ?: (this as? CoordinatorException)?.errorKind
            ?: when (this) {
                is SecurityException -> ShareErrorKind.PERMISSION_DENIED
                is IllegalArgumentException -> ShareErrorKind.INVALID_INPUT
                else -> ShareErrorKind.UNKNOWN
            }

    private companion object { const val RECOVERY_MILLIS = 600_000L }
}
