package com.yeshuwahane.fairsplit.domain.service

import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.common.error.ErrorCode
import com.yeshuwahane.fairsplit.domain.model.*
import com.yeshuwahane.fairsplit.domain.repository.*
import com.yeshuwahane.fairsplit.infrastructure.database.dbQuery
import java.time.Instant
import java.util.UUID

import com.yeshuwahane.fairsplit.realtime.model.RealtimeEvent
import com.yeshuwahane.fairsplit.realtime.model.RealtimeEventType

class EpicService(
    private val epicRepository: EpicRepository,
    private val activityRepository: ActivityRepository,
    private val expenseRepository: ExpenseRepository,
    private val settlementRepository: SettlementRepository
) {
    suspend fun createEpic(
        userId: UUID,
        title: String,
        description: String?,
        iconUrl: String?,
        currency: String = "INR",
        customInviteCode: String? = null
    ): DomainResult<Epic> = dbQuery {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            throw AppException.Validation("Epic title cannot be blank", ErrorCode.VALIDATION_ERROR)
        }

        val inviteCode = if (!customInviteCode.isNullOrBlank() && epicRepository.findByInviteCode(customInviteCode.trim().uppercase()) == null) {
            customInviteCode.trim().uppercase()
        } else {
            generateInviteCode(cleanTitle)
        }

        val epic = epicRepository.createEpic(
            title = cleanTitle,
            description = description?.trim(),
            iconUrl = iconUrl?.trim(),
            createdBy = userId,
            currency = currency.take(3).uppercase(),
            inviteCode = inviteCode
        )

        // Creator automatically joins as OWNER
        epicRepository.addMember(epic.id, userId, EpicMemberRole.OWNER)

        val activityId = UUID.randomUUID()
        // Record activity
        activityRepository.createActivity(
            ActivityLog(
                id = activityId,
                epicId = epic.id,
                actorId = userId,
                actionType = "EPIC_CREATED",
                description = "Created group '$cleanTitle'",
                metadata = null,
                createdAt = Instant.now()
            )
        )

        DomainResult(
            data = epic,
            events = listOf(
                RealtimeEvent(
                    type = RealtimeEventType.ACTIVITY_CREATED,
                    epicId = epic.id.toString(),
                    entityId = activityId.toString(),
                    actorId = userId.toString()
                )
            )
        )
    }

    suspend fun getEpic(userId: UUID, epicId: UUID): Epic {
        requireMembership(epicId, userId)
        return epicRepository.findById(epicId)
            ?: throw AppException.NotFound("Epic not found", ErrorCode.NOT_FOUND)
    }

    suspend fun getEpicByInviteCode(inviteCode: String): Epic {
        return epicRepository.findByInviteCode(inviteCode)
            ?: throw AppException.NotFound("Epic with invite code '$inviteCode' not found", ErrorCode.NOT_FOUND)
    }

    suspend fun listMyEpics(userId: UUID): List<Epic> {
        return epicRepository.findEpicsByUserId(userId)
    }

    suspend fun joinEpic(userId: UUID, inviteCode: String): DomainResult<Epic> = dbQuery {
        val epic = epicRepository.findByInviteCode(inviteCode)
            ?: throw AppException.NotFound("Invalid invite code '$inviteCode'", ErrorCode.NOT_FOUND)

        val existing = epicRepository.findMember(epic.id, userId)
        if (existing != null) {
            return@dbQuery DomainResult(data = epic, events = emptyList())
        }

        epicRepository.addMember(epic.id, userId, EpicMemberRole.MEMBER)

        val activityId = UUID.randomUUID()
        activityRepository.createActivity(
            ActivityLog(
                id = activityId,
                epicId = epic.id,
                actorId = userId,
                actionType = "MEMBER_JOINED",
                description = "Joined group",
                metadata = null,
                createdAt = Instant.now()
            )
        )

        DomainResult(
            data = epic,
            events = listOf(
                RealtimeEvent(
                    type = RealtimeEventType.MEMBER_JOINED,
                    epicId = epic.id.toString(),
                    entityId = userId.toString(),
                    actorId = userId.toString()
                ),
                RealtimeEvent(
                    type = RealtimeEventType.ACTIVITY_CREATED,
                    epicId = epic.id.toString(),
                    entityId = activityId.toString(),
                    actorId = userId.toString()
                )
            )
        )
    }

    suspend fun removeMember(callerId: UUID, epicId: UUID, targetUserId: UUID): DomainResult<Boolean> = dbQuery {
        val callerMember = epicRepository.findMember(epicId, callerId)
            ?: throw AppException.Forbidden("You are not a member of this Epic", ErrorCode.FORBIDDEN)

        val targetMember = epicRepository.findMember(epicId, targetUserId)
            ?: throw AppException.NotFound("Target user is not a member of this Epic", ErrorCode.NOT_FOUND)

        // Only owner/admin or the user leaving themselves
        val isSelf = callerId == targetUserId
        val isPrivileged = callerMember.role == EpicMemberRole.OWNER || callerMember.role == EpicMemberRole.ADMIN

        if (!isSelf && !isPrivileged) {
            throw AppException.Forbidden("Only group admins can remove other members", ErrorCode.FORBIDDEN)
        }

        // Cannot remove owner
        if (targetMember.role == EpicMemberRole.OWNER) {
            throw AppException.Validation("Cannot remove group owner", ErrorCode.VALIDATION_ERROR)
        }

        // Check if member has unsettled bilateral debt with any group member
        val expenses = expenseRepository.findByEpicId(epicId, limit = 1000)
        val allSplits = expenseRepository.getSplitsForEpic(epicId).groupBy { it.expenseId }
        val settlements = settlementRepository.findByEpicId(epicId, limit = 1000)
        val pairwiseDebts = BalanceCalculator.calculatePairwiseDebts(expenses, allSplits, settlements)
        val hasDebts = pairwiseDebts.any { it.debtorId == targetUserId || it.creditorId == targetUserId }

        if (hasDebts) {
            throw AppException.Validation(
                "Cannot remove member with unsettled bilateral debts in this Epic",
                ErrorCode.VALIDATION_ERROR
            )
        }

        val removed = epicRepository.removeMember(epicId, targetUserId)
        val events = mutableListOf<RealtimeEvent>()
        if (removed) {
            val activityId = UUID.randomUUID()
            activityRepository.createActivity(
                ActivityLog(
                    id = activityId,
                    epicId = epicId,
                    actorId = callerId,
                    actionType = "MEMBER_REMOVED",
                    description = if (isSelf) "Left group" else "Removed member",
                    metadata = null,
                    createdAt = Instant.now()
                )
            )
            events.add(
                RealtimeEvent(
                    type = RealtimeEventType.MEMBER_REMOVED,
                    epicId = epicId.toString(),
                    entityId = targetUserId.toString(),
                    actorId = callerId.toString()
                )
            )
            events.add(
                RealtimeEvent(
                    type = RealtimeEventType.ACTIVITY_CREATED,
                    epicId = epicId.toString(),
                    entityId = activityId.toString(),
                    actorId = callerId.toString()
                )
            )
        }
        DomainResult(data = removed, events = events)
    }

    suspend fun updateEpic(callerId: UUID, epicId: UUID, title: String?, description: String?, iconUrl: String?): DomainResult<Epic> = dbQuery {
        val member = epicRepository.findMember(epicId, callerId)
            ?: throw AppException.Forbidden("You are not a member of this Epic", ErrorCode.FORBIDDEN)
        if (member.role != EpicMemberRole.OWNER && member.role != EpicMemberRole.ADMIN) {
            throw AppException.Forbidden("Only group admins can update group details", ErrorCode.FORBIDDEN)
        }
        val updated = epicRepository.updateEpic(epicId, title, description, iconUrl)
            ?: throw AppException.NotFound("Epic not found", ErrorCode.NOT_FOUND)

        val activityId = UUID.randomUUID()
        activityRepository.createActivity(
            ActivityLog(
                id = activityId,
                epicId = epicId,
                actorId = callerId,
                actionType = "EPIC_UPDATED",
                description = "Updated group details",
                metadata = null,
                createdAt = Instant.now()
            )
        )

        DomainResult(
            data = updated,
            events = listOf(
                RealtimeEvent(
                    type = RealtimeEventType.EPIC_UPDATED,
                    epicId = epicId.toString(),
                    entityId = epicId.toString(),
                    actorId = callerId.toString()
                ),
                RealtimeEvent(
                    type = RealtimeEventType.ACTIVITY_CREATED,
                    epicId = epicId.toString(),
                    entityId = activityId.toString(),
                    actorId = callerId.toString()
                )
            )
        )
    }

    suspend fun deleteEpic(callerId: UUID, epicId: UUID): DomainResult<Boolean> = dbQuery {
        val member = epicRepository.findMember(epicId, callerId)
            ?: throw AppException.Forbidden("You are not a member of this Epic", ErrorCode.FORBIDDEN)
        if (member.role != EpicMemberRole.OWNER) {
            throw AppException.Forbidden("Only the group owner can delete the Epic", ErrorCode.FORBIDDEN)
        }
        val deleted = epicRepository.deleteEpic(epicId)
        val events = if (deleted) {
            listOf(
                RealtimeEvent(
                    type = RealtimeEventType.EPIC_DELETED,
                    epicId = epicId.toString(),
                    entityId = epicId.toString(),
                    actorId = callerId.toString()
                )
            )
        } else emptyList()

        DomainResult(data = deleted, events = events)
    }

    suspend fun getMembers(callerId: UUID, epicId: UUID): List<EpicMember> {
        requireMembership(epicId, callerId)
        return epicRepository.getMembers(epicId)
    }

    suspend fun requireMembership(epicId: UUID, userId: UUID) {
        val member = epicRepository.findMember(epicId, userId)
        if (member == null) {
            throw AppException.Forbidden("You are not a member of this Epic", ErrorCode.FORBIDDEN)
        }
    }

    private suspend fun generateInviteCode(title: String): String {
        val prefix = title.filter { it.isLetter() }.take(4).uppercase().ifEmpty { "EPIC" }
        for (attempt in 1..5) {
            val randomSuffix = UUID.randomUUID().toString().take(4).uppercase()
            val candidate = "FS-$prefix-$randomSuffix"
            if (epicRepository.findByInviteCode(candidate) == null) {
                return candidate
            }
        }
        return "FS-$prefix-${UUID.randomUUID().toString().take(6).uppercase()}"
    }
}

class ExpenseService(
    private val expenseRepository: ExpenseRepository,
    private val epicRepository: EpicRepository,
    private val settlementRepository: SettlementRepository,
    private val activityRepository: ActivityRepository
) {
    data class SplitDetail(
        val userId: UUID,
        val exactAmountMinor: Long? = null,
        val percentageBasisPts: Int? = null,
        val shares: Int? = null
    )

    suspend fun createExpense(
        callerId: UUID,
        epicId: UUID,
        title: String,
        amountMinor: Long,
        currency: String = "INR",
        paidByUserId: UUID,
        splitType: SplitType,
        participantIds: List<UUID>,
        splitDetails: List<SplitDetail> = emptyList(),
        receiptUrl: String? = null,
        notes: String? = null
    ): DomainResult<Expense> = dbQuery {
        if (amountMinor <= 0) {
            throw AppException.Validation("Expense amount must be greater than zero", ErrorCode.VALIDATION_ERROR)
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            throw AppException.Validation("Expense title cannot be blank", ErrorCode.VALIDATION_ERROR)
        }

        // Verify caller, payer, and all participants belong to epic
        val allMembers = epicRepository.getMembers(epicId).map { it.userId }.toSet()
        if (callerId !in allMembers) throw AppException.Forbidden("Caller is not a member of this Epic", ErrorCode.FORBIDDEN)
        if (paidByUserId !in allMembers) throw AppException.Validation("Payer is not a member of this Epic", ErrorCode.VALIDATION_ERROR)

        val uniqueParticipants = participantIds.distinct()
        if (uniqueParticipants.isEmpty()) {
            throw AppException.Validation("At least one participant is required", ErrorCode.VALIDATION_ERROR)
        }
        for (pId in uniqueParticipants) {
            if (pId !in allMembers) {
                throw AppException.Validation("Participant $pId is not a member of this Epic", ErrorCode.VALIDATION_ERROR)
            }
        }

        // Server-side authoritative split calculation
        val expenseId = UUID.randomUUID()
        val now = Instant.now()
        val splits = calculateSplits(
            expenseId = expenseId,
            totalAmountMinor = amountMinor,
            splitType = splitType,
            participants = uniqueParticipants,
            splitDetails = splitDetails,
            now = now
        )

        // Validate split total equals expense total exactly
        val totalSplitAmount = splits.sumOf { it.amountMinor }
        if (totalSplitAmount != amountMinor) {
            throw AppException.Validation(
                "Sum of split amounts ($totalSplitAmount) does not equal expense total ($amountMinor)",
                ErrorCode.VALIDATION_ERROR
            )
        }

        val expense = Expense(
            id = expenseId,
            epicId = epicId,
            title = cleanTitle,
            amountMinor = amountMinor,
            currency = currency.take(3).uppercase(),
            paidByUserId = paidByUserId,
            splitType = splitType,
            receiptUrl = receiptUrl,
            notes = notes,
            createdAt = now,
            updatedAt = now,
            deletedAt = null
        )

        val saved = expenseRepository.createExpenseWithSplits(expense, splits)

        val activityId = UUID.randomUUID()
        activityRepository.createActivity(
            ActivityLog(
                id = activityId,
                epicId = epicId,
                actorId = callerId,
                actionType = "EXPENSE_ADDED",
                description = "Added expense '$cleanTitle' for ₹${amountMinor / 100.0}",
                metadata = null,
                createdAt = now
            )
        )

        DomainResult(
            data = saved,
            events = listOf(
                RealtimeEvent(
                    type = RealtimeEventType.EXPENSE_CREATED,
                    epicId = epicId.toString(),
                    entityId = saved.id.toString(),
                    actorId = callerId.toString()
                ),
                RealtimeEvent(
                    type = RealtimeEventType.BALANCE_UPDATED,
                    epicId = epicId.toString(),
                    entityId = saved.id.toString(),
                    actorId = callerId.toString()
                ),
                RealtimeEvent(
                    type = RealtimeEventType.ACTIVITY_CREATED,
                    epicId = epicId.toString(),
                    entityId = activityId.toString(),
                    actorId = callerId.toString()
                )
            )
        )
    }

    suspend fun getExpenses(callerId: UUID, epicId: UUID, limit: Int = 100, offset: Long = 0): List<Expense> {
        val member = epicRepository.findMember(epicId, callerId)
            ?: throw AppException.Forbidden("You are not a member of this Epic", ErrorCode.FORBIDDEN)
        return expenseRepository.findByEpicId(epicId, limit, offset)
    }

    suspend fun deleteExpense(callerId: UUID, epicId: UUID, expenseId: UUID): DomainResult<Boolean> = dbQuery {
        val member = epicRepository.findMember(epicId, callerId)
            ?: throw AppException.Forbidden("You are not a member of this Epic", ErrorCode.FORBIDDEN)

        val expense = expenseRepository.findById(expenseId)
            ?: throw AppException.NotFound("Expense not found", ErrorCode.NOT_FOUND)

        if (expense.epicId != epicId) {
            throw AppException.NotFound("Expense does not belong to this Epic", ErrorCode.NOT_FOUND)
        }

        // Only the person who paid the expense OR group owner/admin can delete it
        val isPayer = expense.paidByUserId == callerId
        val isAdminOrOwner = member.role == EpicMemberRole.OWNER || member.role == EpicMemberRole.ADMIN
        if (!isPayer && !isAdminOrOwner) {
            throw AppException.Forbidden("Only the payer or group admin can delete this expense", ErrorCode.FORBIDDEN)
        }

        // SAFE RULE: If epic already contains settlements, do not allow editing/deleting old expenses
        val settlementCount = settlementRepository.countSettlementsInEpic(epicId)
        if (settlementCount > 0) {
            throw AppException.Validation(
                "Expense modification or deletion is not allowed after settlements have occurred in this Epic",
                ErrorCode.VALIDATION_ERROR
            )
        }

        val deleted = expenseRepository.deleteExpense(expenseId)
        val events = mutableListOf<RealtimeEvent>()
        if (deleted) {
            val activityId = UUID.randomUUID()
            activityRepository.createActivity(
                ActivityLog(
                    id = activityId,
                    epicId = epicId,
                    actorId = callerId,
                    actionType = "EXPENSE_DELETED",
                    description = "Deleted expense '${expense.title}'",
                    metadata = null,
                    createdAt = Instant.now()
                )
            )
            events.add(
                RealtimeEvent(
                    type = RealtimeEventType.EXPENSE_DELETED,
                    epicId = epicId.toString(),
                    entityId = expenseId.toString(),
                    actorId = callerId.toString()
                )
            )
            events.add(
                RealtimeEvent(
                    type = RealtimeEventType.BALANCE_UPDATED,
                    epicId = epicId.toString(),
                    entityId = expenseId.toString(),
                    actorId = callerId.toString()
                )
            )
            events.add(
                RealtimeEvent(
                    type = RealtimeEventType.ACTIVITY_CREATED,
                    epicId = epicId.toString(),
                    entityId = activityId.toString(),
                    actorId = callerId.toString()
                )
            )
        }
        DomainResult(data = deleted, events = events)
    }

    private fun calculateSplits(
        expenseId: UUID,
        totalAmountMinor: Long,
        splitType: SplitType,
        participants: List<UUID>,
        splitDetails: List<SplitDetail>,
        now: Instant
    ): List<ExpenseSplit> {
        return when (splitType) {
            SplitType.EQUAL -> {
                val n = participants.size.toLong()
                val base = totalAmountMinor / n
                val remainder = (totalAmountMinor % n).toInt()

                participants.mapIndexed { index, userId ->
                    // Distribute remainder cent-by-cent deterministically to the first participants
                    val splitAmount = base + if (index < remainder) 1L else 0L
                    ExpenseSplit(
                        id = UUID.randomUUID(),
                        expenseId = expenseId,
                        userId = userId,
                        amountMinor = splitAmount,
                        percentageBasisPts = null,
                        shares = null,
                        createdAt = now
                    )
                }
            }

            SplitType.EXACT -> {
                val detailMap = splitDetails.associateBy { it.userId }
                participants.map { userId ->
                    val amount = detailMap[userId]?.exactAmountMinor
                        ?: throw AppException.Validation("Missing exact amount for participant $userId", ErrorCode.VALIDATION_ERROR)
                    if (amount < 0) throw AppException.Validation("Split amount cannot be negative", ErrorCode.VALIDATION_ERROR)
                    ExpenseSplit(
                        id = UUID.randomUUID(),
                        expenseId = expenseId,
                        userId = userId,
                        amountMinor = amount,
                        percentageBasisPts = null,
                        shares = null,
                        createdAt = now
                    )
                }
            }

            SplitType.PERCENTAGE -> {
                val detailMap = splitDetails.associateBy { it.userId }
                val totalBasisPts = participants.sumOf { detailMap[it]?.percentageBasisPts ?: 0 }
                if (totalBasisPts != 10000) { // 100.00%
                    throw AppException.Validation("Percentage basis points must sum to 10000 (100%), got $totalBasisPts", ErrorCode.VALIDATION_ERROR)
                }

                var distributed = 0L
                val splits = participants.mapIndexed { index, userId ->
                    val basisPts = detailMap[userId]?.percentageBasisPts ?: 0
                    val amount = if (index == participants.size - 1) {
                        totalAmountMinor - distributed // absorb rounding discrepancy into last participant
                    } else {
                        (totalAmountMinor * basisPts) / 10000
                    }
                    distributed += amount
                    ExpenseSplit(
                        id = UUID.randomUUID(),
                        expenseId = expenseId,
                        userId = userId,
                        amountMinor = amount,
                        percentageBasisPts = basisPts,
                        shares = null,
                        createdAt = now
                    )
                }
                splits
            }

            SplitType.SHARES -> {
                val detailMap = splitDetails.associateBy { it.userId }
                val totalShares = participants.sumOf { detailMap[it]?.shares ?: 1 }
                if (totalShares <= 0) {
                    throw AppException.Validation("Total shares must be positive", ErrorCode.VALIDATION_ERROR)
                }

                var distributed = 0L
                val splits = participants.mapIndexed { index, userId ->
                    val shares = detailMap[userId]?.shares ?: 1
                    val amount = if (index == participants.size - 1) {
                        totalAmountMinor - distributed
                    } else {
                        (totalAmountMinor * shares) / totalShares
                    }
                    distributed += amount
                    ExpenseSplit(
                        id = UUID.randomUUID(),
                        expenseId = expenseId,
                        userId = userId,
                        amountMinor = amount,
                        percentageBasisPts = null,
                        shares = shares,
                        createdAt = now
                    )
                }
                splits
            }
        }
    }
}

class SettlementService(
    private val settlementRepository: SettlementRepository,
    private val epicRepository: EpicRepository,
    private val expenseRepository: ExpenseRepository,
    private val activityRepository: ActivityRepository
) {
    suspend fun createSettlement(
        callerId: UUID,
        epicId: UUID,
        fromUserId: UUID,
        toUserId: UUID,
        amountMinor: Long,
        currency: String = "INR",
        method: String = "CASH",
        notes: String? = null
    ): DomainResult<Settlement> = dbQuery {
        if (amountMinor <= 0) {
            throw AppException.Validation("Settlement amount must be positive", ErrorCode.VALIDATION_ERROR)
        }
        if (fromUserId == toUserId) {
            throw AppException.Validation("Payer and receiver cannot be the same user", ErrorCode.VALIDATION_ERROR)
        }

        val members = epicRepository.getMembers(epicId).map { it.userId }.toSet()
        if (callerId !in members) throw AppException.Forbidden("Caller is not a member of this Epic", ErrorCode.FORBIDDEN)
        if (fromUserId !in members) throw AppException.Validation("Payer is not a member of this Epic", ErrorCode.VALIDATION_ERROR)
        if (toUserId !in members) throw AppException.Validation("Receiver is not a member of this Epic", ErrorCode.VALIDATION_ERROR)

        // Strict bilateral debt validation:
        val expenses = expenseRepository.findByEpicId(epicId, limit = 1000)
        val allSplits = expenseRepository.getSplitsForEpic(epicId).groupBy { it.expenseId }
        val pastSettlements = settlementRepository.findByEpicId(epicId, limit = 1000)

        val bilateralDebt = BalanceCalculator.getBilateralDebt(
            fromUserId = fromUserId,
            toUserId = toUserId,
            expenses = expenses,
            splitsByExpenseId = allSplits,
            settlements = pastSettlements
        )

        if (amountMinor > bilateralDebt) {
            throw AppException.Validation(
                "Settlement amount (₹${amountMinor / 100.0}) exceeds outstanding bilateral debt (₹${bilateralDebt / 100.0})",
                ErrorCode.VALIDATION_ERROR
            )
        }

        val now = Instant.now()
        val settlement = Settlement(
            id = UUID.randomUUID(),
            epicId = epicId,
            fromUserId = fromUserId,
            toUserId = toUserId,
            amountMinor = amountMinor,
            currency = currency.take(3).uppercase(),
            method = method,
            notes = notes,
            createdAt = now
        )

        val saved = settlementRepository.createSettlement(settlement)

        val activityId = UUID.randomUUID()
        activityRepository.createActivity(
            ActivityLog(
                id = activityId,
                epicId = epicId,
                actorId = callerId,
                actionType = "SETTLEMENT_CREATED",
                description = "Settled debt of ₹${amountMinor / 100.0}",
                metadata = null,
                createdAt = now
            )
        )

        DomainResult(
            data = saved,
            events = listOf(
                RealtimeEvent(
                    type = RealtimeEventType.SETTLEMENT_CREATED,
                    epicId = epicId.toString(),
                    entityId = saved.id.toString(),
                    actorId = callerId.toString()
                ),
                RealtimeEvent(
                    type = RealtimeEventType.BALANCE_UPDATED,
                    epicId = epicId.toString(),
                    entityId = saved.id.toString(),
                    actorId = callerId.toString()
                ),
                RealtimeEvent(
                    type = RealtimeEventType.ACTIVITY_CREATED,
                    epicId = epicId.toString(),
                    entityId = activityId.toString(),
                    actorId = callerId.toString()
                )
            )
        )
    }

    suspend fun updateSettlementAmount(
        callerId: UUID,
        epicId: UUID,
        settlementId: UUID,
        newAmountMinor: Long,
        notes: String? = null
    ): DomainResult<Settlement> = dbQuery {
        if (newAmountMinor <= 0) {
            throw AppException.Validation("Settlement amount must be positive. To delete a settlement, use void.", ErrorCode.VALIDATION_ERROR)
        }

        val members = epicRepository.getMembers(epicId).map { it.userId }.toSet()
        if (callerId !in members) throw AppException.Forbidden("Caller is not a member of this Epic", ErrorCode.FORBIDDEN)

        val existing = settlementRepository.findById(settlementId)
            ?: throw AppException.NotFound("Settlement not found", ErrorCode.NOT_FOUND)

        if (existing.epicId != epicId) {
            throw AppException.Validation("Settlement does not belong to this Epic", ErrorCode.VALIDATION_ERROR)
        }

        if (existing.status.equals("VOIDED", ignoreCase = true)) {
            throw AppException.Validation("Cannot update a voided settlement", ErrorCode.VALIDATION_ERROR)
        }

        // Caller must be payer, receiver, or epic owner
        val callerMember = epicRepository.findMember(epicId, callerId)
        val isParticipant = callerId == existing.fromUserId || callerId == existing.toUserId
        val isOwner = callerMember?.role == EpicMemberRole.OWNER
        if (!isParticipant && !isOwner) {
            throw AppException.Forbidden("Only participants or Epic owner can update this settlement", ErrorCode.FORBIDDEN)
        }

        // Validate that new amount does not exceed effective outstanding bilateral debt before this settlement
        val expenses = expenseRepository.findByEpicId(epicId, limit = 1000)
        val allSplits = expenseRepository.getSplitsForEpic(epicId).groupBy { it.expenseId }
        val pastSettlements = settlementRepository.findByEpicId(epicId, limit = 1000)

        val currentOutstanding = BalanceCalculator.getBilateralDebt(
            fromUserId = existing.fromUserId,
            toUserId = existing.toUserId,
            expenses = expenses,
            splitsByExpenseId = allSplits,
            settlements = pastSettlements
        )
        val effectiveOutstandingBeforeThis = currentOutstanding + existing.amountMinor

        if (newAmountMinor > effectiveOutstandingBeforeThis) {
            throw AppException.Validation(
                "New settlement amount (₹${newAmountMinor / 100.0}) exceeds outstanding bilateral debt (₹${effectiveOutstandingBeforeThis / 100.0})",
                ErrorCode.VALIDATION_ERROR
            )
        }

        val now = Instant.now()
        val updated = existing.copy(
            amountMinor = newAmountMinor,
            notes = notes ?: existing.notes,
            updatedAt = now,
            updatedBy = callerId
        )

        val saved = settlementRepository.updateSettlement(updated)
            ?: throw AppException.NotFound("Failed to update settlement", ErrorCode.NOT_FOUND)

        val activityId = UUID.randomUUID()
        activityRepository.createActivity(
            ActivityLog(
                id = activityId,
                epicId = epicId,
                actorId = callerId,
                actionType = "SETTLEMENT_UPDATED",
                description = "Updated settlement amount to ₹${newAmountMinor / 100.0}",
                metadata = null,
                createdAt = now
            )
        )

        DomainResult(
            data = saved,
            events = listOf(
                RealtimeEvent(
                    type = RealtimeEventType.SETTLEMENT_CREATED,
                    epicId = epicId.toString(),
                    entityId = saved.id.toString(),
                    actorId = callerId.toString()
                ),
                RealtimeEvent(
                    type = RealtimeEventType.BALANCE_UPDATED,
                    epicId = epicId.toString(),
                    entityId = saved.id.toString(),
                    actorId = callerId.toString()
                ),
                RealtimeEvent(
                    type = RealtimeEventType.ACTIVITY_CREATED,
                    epicId = epicId.toString(),
                    entityId = activityId.toString(),
                    actorId = callerId.toString()
                )
            )
        )
    }

    suspend fun voidSettlement(
        callerId: UUID,
        epicId: UUID,
        settlementId: UUID
    ): DomainResult<Settlement> = dbQuery {
        val members = epicRepository.getMembers(epicId).map { it.userId }.toSet()
        if (callerId !in members) throw AppException.Forbidden("Caller is not a member of this Epic", ErrorCode.FORBIDDEN)

        val existing = settlementRepository.findById(settlementId)
            ?: throw AppException.NotFound("Settlement not found", ErrorCode.NOT_FOUND)

        if (existing.epicId != epicId) {
            throw AppException.Validation("Settlement does not belong to this Epic", ErrorCode.VALIDATION_ERROR)
        }

        if (existing.status.equals("VOIDED", ignoreCase = true)) {
            // Already voided
            return@dbQuery DomainResult(data = existing, events = emptyList())
        }

        val callerMember = epicRepository.findMember(epicId, callerId)
        val isParticipant = callerId == existing.fromUserId || callerId == existing.toUserId
        val isOwner = callerMember?.role == EpicMemberRole.OWNER
        if (!isParticipant && !isOwner) {
            throw AppException.Forbidden("Only participants or Epic owner can void this settlement", ErrorCode.FORBIDDEN)
        }

        val now = Instant.now()
        val voided = existing.copy(
            status = "VOIDED",
            voidedAt = now,
            voidedBy = callerId
        )

        val saved = settlementRepository.updateSettlement(voided)
            ?: throw AppException.NotFound("Failed to void settlement", ErrorCode.NOT_FOUND)

        val activityId = UUID.randomUUID()
        activityRepository.createActivity(
            ActivityLog(
                id = activityId,
                epicId = epicId,
                actorId = callerId,
                actionType = "SETTLEMENT_DELETED",
                description = "Voided settlement of ₹${existing.amountMinor / 100.0}",
                metadata = null,
                createdAt = now
            )
        )

        DomainResult(
            data = saved,
            events = listOf(
                RealtimeEvent(
                    type = RealtimeEventType.SETTLEMENT_CREATED,
                    epicId = epicId.toString(),
                    entityId = saved.id.toString(),
                    actorId = callerId.toString()
                ),
                RealtimeEvent(
                    type = RealtimeEventType.BALANCE_UPDATED,
                    epicId = epicId.toString(),
                    entityId = saved.id.toString(),
                    actorId = callerId.toString()
                ),
                RealtimeEvent(
                    type = RealtimeEventType.ACTIVITY_CREATED,
                    epicId = epicId.toString(),
                    entityId = activityId.toString(),
                    actorId = callerId.toString()
                )
            )
        )
    }

    suspend fun getSettlements(callerId: UUID, epicId: UUID, limit: Int = 100, offset: Long = 0): List<Settlement> {
        val member = epicRepository.findMember(epicId, callerId)
            ?: throw AppException.Forbidden("You are not a member of this Epic", ErrorCode.FORBIDDEN)
        return settlementRepository.findByEpicId(epicId, limit, offset)
    }
}
