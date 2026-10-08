package com.unishare.app.data.history

import com.unishare.app.domain.*

data class HistoryQuery(
    val search: String = "",
    val device: DeviceKey? = null,
    val direction: ShareDirection? = null,
    val kind: ContentKind? = null,
    val status: ActivityStatus? = null,
)

internal fun HistoryQuery.filter(rows: List<ShareActivity>): List<ShareActivity> = rows
    .asSequence().filter {
        (device == null || it.deviceId == device) && (direction == null || it.direction == direction) &&
            (kind == null || it.kind == kind) && (status == null || it.status == status)
    }.map { it.copy(displayName = sanitizedDisplayName(it.displayName)) }
    .filter { it.displayName.contains(search.trim(), ignoreCase = true) }.toList()
