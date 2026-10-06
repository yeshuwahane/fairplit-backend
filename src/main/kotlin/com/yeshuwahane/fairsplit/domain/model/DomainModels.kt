package com.yeshuwahane.fairsplit.domain.model

import com.yeshuwahane.fairsplit.common.money.Money
import java.time.Instant
import java.util.UUID

enum class EpicMemberRole {
    OWNER,
    ADMIN,
    MEMBER
}

data class Epic(
    val id: UUID,
    val title: String,
    val description: String?,
    val iconUrl: String?,
    val createdBy: UUID,
    val currency: String,
    val inviteCode: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
)

data class EpicMember(
    val id: UUID,
    val epicId: UUID,
    val userId: UUID,
    val role: EpicMemberRole,
    val joinedAt: Instant
)

enum class SplitType {
    EQUAL,
    EXACT,
    PERCENTAGE,
    SHARES
}

data class Expense(
    val id: UUID,
    val epicId: UUID,
    val title: String,
    val amountMinor: Long,
    val currency: String,
    val paidByUserId: UUID,
    val splitType: SplitType,
    val receiptUrl: String?,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
)

data class ExpenseSplit(
    val id: UUID,
    val expenseId: UUID,
    val userId: UUID,
    val amountMinor: Long,
    val percentageBasisPts: Int? = null,
    val shares: Int? = null,
    val createdAt: Instant
)

data class Settlement(
    val id: UUID,
    val epicId: UUID,
    val fromUserId: UUID,
    val toUserId: UUID,
    val amountMinor: Long,
    val currency: String,
    val method: String,
    val notes: String?,
    val status: String = "COMPLETED",
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val updatedBy: UUID? = null,
    val voidedAt: Instant? = null,
    val voidedBy: UUID? = null
)

data class ActivityLog(
    val id: UUID,
    val epicId: UUID,
    val actorId: UUID,
    val actionType: String,
    val description: String,
    val metadata: String?, // JSON string
    val createdAt: Instant
)

data class IdempotencyRecord(
    val id: UUID,
    val userId: UUID,
    val key: String,
    val endpoint: String,
    val requestHash: String,
    val responseStatus: Int,
    val responseBody: String,
    val createdAt: Instant,
    val expiresAt: Instant
)

data class UserBalance(
    val userId: UUID,
    val totalPaidMinor: Long,
    val totalOwedMinor: Long,
    val netBalanceMinor: Long // totalPaidMinor - totalOwedMinor
)

data class PairwiseDebt(
    val debtorId: UUID,   // Who owes
    val creditorId: UUID, // Who is owed
    val amountMinor: Long
)

data class DomainResult<T>(
    val data: T,
    val events: List<com.yeshuwahane.fairsplit.realtime.model.RealtimeEvent> = emptyList()
)

