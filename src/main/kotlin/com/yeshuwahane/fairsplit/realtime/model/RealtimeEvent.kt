package com.yeshuwahane.fairsplit.realtime.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
enum class RealtimeEventType {
    EPIC_UPDATED,
    EPIC_DELETED,
    MEMBER_JOINED,
    MEMBER_REMOVED,
    EXPENSE_CREATED,
    EXPENSE_UPDATED,
    EXPENSE_DELETED,
    SETTLEMENT_CREATED,
    BALANCE_UPDATED,
    ACTIVITY_CREATED
}

@Serializable
data class RealtimeEvent(
    val type: RealtimeEventType,
    val epicId: String,
    val entityId: String? = null,
    val actorId: String? = null,
    val timestamp: String = Instant.now().toString()
)
