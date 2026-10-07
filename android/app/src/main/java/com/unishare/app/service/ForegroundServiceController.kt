package com.unishare.app.service

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

enum class ServiceReason { BACKGROUND_RECEIVE, ACTIVE_TRANSFER }

interface ForegroundServiceGateway {
    fun start(reason: ServiceReason)
    suspend fun stopWhenIdle()
}

/**
 * Android service boundary. RuntimeController is the policy owner and invokes start only while a
 * visible Activity authorizes it. Notification actions enter through ShareForegroundService.
 */
class ForegroundServiceController internal constructor(
    private val activeTransferCount: StateFlow<Int>,
    private val startService: (ServiceReason) -> Unit,
    private val stopService: () -> Unit,
) : ForegroundServiceGateway {
    constructor(context: Context, activeTransferCount: StateFlow<Int>) : this(
        activeTransferCount = activeTransferCount,
        startService = { reason ->
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, ShareForegroundService::class.java)
                    .setAction(ShareForegroundService.ACTION_START)
                    .putExtra(ShareForegroundService.EXTRA_REASON, reason.name),
            )
        },
        stopService = {
            context.applicationContext.stopService(
                Intent(context.applicationContext, ShareForegroundService::class.java),
            )
        },
    )

    override fun start(reason: ServiceReason) = startService(reason)

    override suspend fun stopWhenIdle() {
        activeTransferCount.first { it == 0 }
        stopService()
    }
}
