package com.unishare.app.service

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ServiceReason { BACKGROUND_RECEIVE, ACTIVE_TRANSFER }

interface ForegroundServiceGateway {
    suspend fun start(reason: ServiceReason)
    suspend fun stopWhenIdle()
}

/**
 * Android service boundary. RuntimeController is the policy owner and invokes start only while a
 * visible Activity authorizes it. Notification actions enter through ShareForegroundService.
 */
class ForegroundServiceController internal constructor(
    private val activeTransferCount: StateFlow<Int>,
    private val startService: (ServiceReason, Long) -> Unit,
    private val stopService: () -> Unit,
) : ForegroundServiceGateway {
    private val lifecycle = Mutex()
    private val promotionGate = Any()
    private var nextRequestId = 0L
    private var pendingPromotion: Pair<Long, CompletableDeferred<Unit>>? = null

    constructor(context: Context, activeTransferCount: StateFlow<Int>) : this(
        activeTransferCount = activeTransferCount,
        startService = { reason, requestId ->
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, ShareForegroundService::class.java)
                    .setAction(ShareForegroundService.ACTION_START)
                    .putExtra(ShareForegroundService.EXTRA_REASON, reason.name)
                    .putExtra(ShareForegroundService.EXTRA_START_REQUEST_ID, requestId),
            )
        },
        stopService = {
            context.applicationContext.stopService(
                Intent(context.applicationContext, ShareForegroundService::class.java),
            )
        },
    )

    override suspend fun start(reason: ServiceReason) {
        lifecycle.withLock {
            val request = synchronized(promotionGate) {
                (++nextRequestId) to CompletableDeferred<Unit>()
            }
            synchronized(promotionGate) { pendingPromotion = request }
            try {
                startService(reason, request.first)
                withContext(NonCancellable) { request.second.await() }
            } finally {
                synchronized(promotionGate) {
                    if (pendingPromotion?.first == request.first) pendingPromotion = null
                }
            }
        }
    }

    internal fun onForegroundStarted(requestId: Long) {
        val completion = synchronized(promotionGate) {
            pendingPromotion?.takeIf { it.first == requestId }?.second
        }
        completion?.complete(Unit)
    }

    override suspend fun stopWhenIdle() {
        while (true) {
            activeTransferCount.first { it == 0 }
            val stopped = lifecycle.withLock {
                if (activeTransferCount.value != 0) false else {
                    stopService()
                    true
                }
            }
            if (stopped) return
        }
    }
}
