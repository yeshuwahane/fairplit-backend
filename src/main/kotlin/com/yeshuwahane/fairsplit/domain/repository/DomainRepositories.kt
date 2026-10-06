package com.yeshuwahane.fairsplit.domain.repository

import com.yeshuwahane.fairsplit.domain.model.*
import java.util.UUID

interface EpicRepository {
    suspend fun createEpic(
        title: String,
        description: String?,
        iconUrl: String?,
        createdBy: UUID,
        currency: String,
        inviteCode: String
    ): Epic

    suspend fun findById(id: UUID): Epic?
    suspend fun findByInviteCode(inviteCode: String): Epic?
    suspend fun findEpicsByUserId(userId: UUID): List<Epic>
    suspend fun findAll(limit: Int = 100): List<Epic>
    suspend fun updateEpic(id: UUID, title: String?, description: String?, iconUrl: String?): Epic?
    suspend fun deleteEpic(id: UUID): Boolean

    // Members
    suspend fun addMember(epicId: UUID, userId: UUID, role: EpicMemberRole): EpicMember
    suspend fun findMember(epicId: UUID, userId: UUID): EpicMember?
    suspend fun getMembers(epicId: UUID): List<EpicMember>
    suspend fun removeMember(epicId: UUID, userId: UUID): Boolean
}

interface ExpenseRepository {
    suspend fun createExpenseWithSplits(
        expense: Expense,
        splits: List<ExpenseSplit>
    ): Expense

    suspend fun findById(id: UUID): Expense?
    suspend fun findByEpicId(epicId: UUID, limit: Int = 100, offset: Long = 0): List<Expense>
    suspend fun getSplitsForExpense(expenseId: UUID): List<ExpenseSplit>
    suspend fun getSplitsForEpic(epicId: UUID): List<ExpenseSplit>
    suspend fun updateExpenseWithSplits(
        expense: Expense,
        splits: List<ExpenseSplit>
    ): Expense?
    suspend fun deleteExpense(id: UUID): Boolean
    suspend fun countExpensesForUserInEpic(epicId: UUID, userId: UUID): Int
}

interface SettlementRepository {
    suspend fun createSettlement(settlement: Settlement): Settlement
    suspend fun findById(id: UUID): Settlement?
    suspend fun findByEpicId(epicId: UUID, limit: Int = 100, offset: Long = 0): List<Settlement>
    suspend fun updateSettlement(settlement: Settlement): Settlement?
    suspend fun countSettlementsInEpic(epicId: UUID): Int
    suspend fun countSettlementsForUserInEpic(epicId: UUID, userId: UUID): Int
}

interface ActivityRepository {
    suspend fun createActivity(activity: ActivityLog): ActivityLog
    suspend fun findByEpicId(epicId: UUID, limit: Int = 50, offset: Long = 0): List<ActivityLog>
}

interface IdempotencyRepository {
    suspend fun find(userId: UUID, key: String, endpoint: String): IdempotencyRecord?
    suspend fun save(record: IdempotencyRecord)
}
