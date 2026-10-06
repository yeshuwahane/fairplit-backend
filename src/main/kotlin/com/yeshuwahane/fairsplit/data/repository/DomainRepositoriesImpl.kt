package com.yeshuwahane.fairsplit.data.repository

import com.yeshuwahane.fairsplit.data.table.*
import com.yeshuwahane.fairsplit.domain.model.*
import com.yeshuwahane.fairsplit.domain.repository.*
import com.yeshuwahane.fairsplit.infrastructure.database.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

class EpicRepositoryImpl : EpicRepository {

    override suspend fun createEpic(
        title: String,
        description: String?,
        iconUrl: String?,
        createdBy: UUID,
        currency: String,
        inviteCode: String
    ): Epic = dbQuery {
        val newId = UUID.randomUUID()
        val now = Instant.now()
        EpicsTable.insert {
            it[id] = newId
            it[EpicsTable.title] = title
            it[EpicsTable.description] = description
            it[EpicsTable.iconUrl] = iconUrl
            it[EpicsTable.createdBy] = createdBy
            it[EpicsTable.currency] = currency
            it[EpicsTable.inviteCode] = inviteCode
            it[createdAt] = now
            it[updatedAt] = now
            it[deletedAt] = null
        }
        Epic(
            id = newId,
            title = title,
            description = description,
            iconUrl = iconUrl,
            createdBy = createdBy,
            currency = currency,
            inviteCode = inviteCode,
            createdAt = now,
            updatedAt = now,
            deletedAt = null
        )
    }

    override suspend fun findById(id: UUID): Epic? = dbQuery {
        EpicsTable.selectAll()
            .where { (EpicsTable.id eq id) and EpicsTable.deletedAt.isNull() }
            .map { toEpic(it) }
            .singleOrNull()
    }

    override suspend fun findByInviteCode(inviteCode: String): Epic? = dbQuery {
        val clean = inviteCode.trim().uppercase()
        EpicsTable.selectAll()
            .where { (EpicsTable.inviteCode.upperCase() eq clean) and EpicsTable.deletedAt.isNull() }
            .map { toEpic(it) }
            .singleOrNull()
    }

    override suspend fun findEpicsByUserId(userId: UUID): List<Epic> = dbQuery {
        (EpicsTable innerJoin EpicMembersTable)
            .selectAll()
            .where { (EpicMembersTable.userId eq userId) and EpicsTable.deletedAt.isNull() }
            .orderBy(EpicsTable.createdAt to SortOrder.DESC)
            .map { toEpic(it) }
    }

    override suspend fun findAll(limit: Int): List<Epic> = dbQuery {
        EpicsTable.selectAll()
            .where { EpicsTable.deletedAt.isNull() }
            .orderBy(EpicsTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { toEpic(it) }
    }

    override suspend fun updateEpic(
        id: UUID,
        title: String?,
        description: String?,
        iconUrl: String?
    ): Epic? = dbQuery {
        val now = Instant.now()
        val count = EpicsTable.update({ (EpicsTable.id eq id) and EpicsTable.deletedAt.isNull() }) {
            if (title != null) it[EpicsTable.title] = title
            if (description != null) it[EpicsTable.description] = description
            if (iconUrl != null) it[EpicsTable.iconUrl] = iconUrl
            it[updatedAt] = now
        }
        if (count > 0) findById(id) else null
    }

    override suspend fun deleteEpic(id: UUID): Boolean = dbQuery {
        val now = Instant.now()
        val count = EpicsTable.update({ (EpicsTable.id eq id) and EpicsTable.deletedAt.isNull() }) {
            it[deletedAt] = now
        }
        count > 0
    }

    override suspend fun addMember(epicId: UUID, userId: UUID, role: EpicMemberRole): EpicMember = dbQuery {
        val newId = UUID.randomUUID()
        val now = Instant.now()
        EpicMembersTable.insert {
            it[id] = newId
            it[EpicMembersTable.epicId] = epicId
            it[EpicMembersTable.userId] = userId
            it[EpicMembersTable.role] = role.name
            it[joinedAt] = now
        }
        EpicMember(
            id = newId,
            epicId = epicId,
            userId = userId,
            role = role,
            joinedAt = now
        )
    }

    override suspend fun findMember(epicId: UUID, userId: UUID): EpicMember? = dbQuery {
        EpicMembersTable.selectAll()
            .where { (EpicMembersTable.epicId eq epicId) and (EpicMembersTable.userId eq userId) }
            .map { toMember(it) }
            .singleOrNull()
    }

    override suspend fun getMembers(epicId: UUID): List<EpicMember> = dbQuery {
        EpicMembersTable.selectAll()
            .where { EpicMembersTable.epicId eq epicId }
            .orderBy(EpicMembersTable.joinedAt to SortOrder.ASC)
            .map { toMember(it) }
    }

    override suspend fun removeMember(epicId: UUID, userId: UUID): Boolean = dbQuery {
        val count = EpicMembersTable.deleteWhere {
            (EpicMembersTable.epicId eq epicId) and (EpicMembersTable.userId eq userId)
        }
        count > 0
    }

    private fun toEpic(row: ResultRow): Epic = Epic(
        id = row[EpicsTable.id],
        title = row[EpicsTable.title],
        description = row[EpicsTable.description],
        iconUrl = row[EpicsTable.iconUrl],
        createdBy = row[EpicsTable.createdBy],
        currency = row[EpicsTable.currency],
        inviteCode = row[EpicsTable.inviteCode],
        createdAt = row[EpicsTable.createdAt],
        updatedAt = row[EpicsTable.updatedAt],
        deletedAt = row[EpicsTable.deletedAt]
    )

    private fun toMember(row: ResultRow): EpicMember = EpicMember(
        id = row[EpicMembersTable.id],
        epicId = row[EpicMembersTable.epicId],
        userId = row[EpicMembersTable.userId],
        role = try {
            EpicMemberRole.valueOf(row[EpicMembersTable.role])
        } catch (_: Exception) {
            EpicMemberRole.MEMBER
        },
        joinedAt = row[EpicMembersTable.joinedAt]
    )
}

class ExpenseRepositoryImpl : ExpenseRepository {

    override suspend fun createExpenseWithSplits(
        expense: Expense,
        splits: List<ExpenseSplit>
    ): Expense = dbQuery {
        ExpensesTable.insert {
            it[id] = expense.id
            it[epicId] = expense.epicId
            it[title] = expense.title
            it[amountMinor] = expense.amountMinor
            it[currency] = expense.currency
            it[paidByUserId] = expense.paidByUserId
            it[splitType] = expense.splitType.name
            it[receiptUrl] = expense.receiptUrl
            it[notes] = expense.notes
            it[createdAt] = expense.createdAt
            it[updatedAt] = expense.updatedAt
            it[deletedAt] = null
        }

        splits.forEach { split ->
            ExpenseSplitsTable.insert {
                it[id] = split.id
                it[expenseId] = expense.id
                it[userId] = split.userId
                it[amountMinor] = split.amountMinor
                it[percentageBasisPts] = split.percentageBasisPts
                it[shares] = split.shares
                it[createdAt] = split.createdAt
            }
        }

        expense
    }

    override suspend fun findById(id: UUID): Expense? = dbQuery {
        ExpensesTable.selectAll()
            .where { (ExpensesTable.id eq id) and ExpensesTable.deletedAt.isNull() }
            .map { toExpense(it) }
            .singleOrNull()
    }

    override suspend fun findByEpicId(epicId: UUID, limit: Int, offset: Long): List<Expense> = dbQuery {
        ExpensesTable.selectAll()
            .where { (ExpensesTable.epicId eq epicId) and ExpensesTable.deletedAt.isNull() }
            .orderBy(ExpensesTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset)
            .map { toExpense(it) }
    }

    override suspend fun getSplitsForExpense(expenseId: UUID): List<ExpenseSplit> = dbQuery {
        ExpenseSplitsTable.selectAll()
            .where { ExpenseSplitsTable.expenseId eq expenseId }
            .map { toSplit(it) }
    }

    override suspend fun getSplitsForEpic(epicId: UUID): List<ExpenseSplit> = dbQuery {
        (ExpenseSplitsTable innerJoin ExpensesTable)
            .selectAll()
            .where { (ExpensesTable.epicId eq epicId) and ExpensesTable.deletedAt.isNull() }
            .map { toSplit(it) }
    }

    override suspend fun updateExpenseWithSplits(
        expense: Expense,
        splits: List<ExpenseSplit>
    ): Expense? = dbQuery {
        val now = Instant.now()
        val count = ExpensesTable.update({ (ExpensesTable.id eq expense.id) and ExpensesTable.deletedAt.isNull() }) {
            it[title] = expense.title
            it[amountMinor] = expense.amountMinor
            it[currency] = expense.currency
            it[paidByUserId] = expense.paidByUserId
            it[splitType] = expense.splitType.name
            it[receiptUrl] = expense.receiptUrl
            it[notes] = expense.notes
            it[updatedAt] = now
        }

        if (count == 0) return@dbQuery null

        // Replace splits
        ExpenseSplitsTable.deleteWhere { expenseId eq expense.id }
        splits.forEach { split ->
            ExpenseSplitsTable.insert {
                it[id] = split.id
                it[expenseId] = expense.id
                it[userId] = split.userId
                it[amountMinor] = split.amountMinor
                it[percentageBasisPts] = split.percentageBasisPts
                it[shares] = split.shares
                it[createdAt] = split.createdAt
            }
        }

        expense.copy(updatedAt = now)
    }

    override suspend fun deleteExpense(id: UUID): Boolean = dbQuery {
        val now = Instant.now()
        val count = ExpensesTable.update({ (ExpensesTable.id eq id) and ExpensesTable.deletedAt.isNull() }) {
            it[deletedAt] = now
        }
        count > 0
    }

    override suspend fun countExpensesForUserInEpic(epicId: UUID, userId: UUID): Int = dbQuery {
        val paidCount = ExpensesTable.selectAll()
            .where { (ExpensesTable.epicId eq epicId) and (ExpensesTable.paidByUserId eq userId) and ExpensesTable.deletedAt.isNull() }
            .count()
            .toInt()

        val splitCount = (ExpenseSplitsTable innerJoin ExpensesTable)
            .selectAll()
            .where { (ExpensesTable.epicId eq epicId) and (ExpenseSplitsTable.userId eq userId) and ExpensesTable.deletedAt.isNull() }
            .count()
            .toInt()

        paidCount + splitCount
    }

    private fun toExpense(row: ResultRow): Expense = Expense(
        id = row[ExpensesTable.id],
        epicId = row[ExpensesTable.epicId],
        title = row[ExpensesTable.title],
        amountMinor = row[ExpensesTable.amountMinor],
        currency = row[ExpensesTable.currency],
        paidByUserId = row[ExpensesTable.paidByUserId],
        splitType = try {
            SplitType.valueOf(row[ExpensesTable.splitType])
        } catch (_: Exception) {
            SplitType.EQUAL
        },
        receiptUrl = row[ExpensesTable.receiptUrl],
        notes = row[ExpensesTable.notes],
        createdAt = row[ExpensesTable.createdAt],
        updatedAt = row[ExpensesTable.updatedAt],
        deletedAt = row[ExpensesTable.deletedAt]
    )

    private fun toSplit(row: ResultRow): ExpenseSplit = ExpenseSplit(
        id = row[ExpenseSplitsTable.id],
        expenseId = row[ExpenseSplitsTable.expenseId],
        userId = row[ExpenseSplitsTable.userId],
        amountMinor = row[ExpenseSplitsTable.amountMinor],
        percentageBasisPts = row[ExpenseSplitsTable.percentageBasisPts],
        shares = row[ExpenseSplitsTable.shares],
        createdAt = row[ExpenseSplitsTable.createdAt]
    )
}

class SettlementRepositoryImpl : SettlementRepository {

    override suspend fun createSettlement(settlement: Settlement): Settlement = dbQuery {
        SettlementsTable.insert {
            it[id] = settlement.id
            it[epicId] = settlement.epicId
            it[fromUserId] = settlement.fromUserId
            it[toUserId] = settlement.toUserId
            it[amountMinor] = settlement.amountMinor
            it[currency] = settlement.currency
            it[method] = settlement.method
            it[notes] = settlement.notes
            it[status] = settlement.status
            it[createdAt] = settlement.createdAt
            it[updatedAt] = settlement.updatedAt
            it[updatedBy] = settlement.updatedBy
            it[voidedAt] = settlement.voidedAt
            it[voidedBy] = settlement.voidedBy
        }
        settlement
    }

    override suspend fun updateSettlement(settlement: Settlement): Settlement? = dbQuery {
        val count = SettlementsTable.update({ SettlementsTable.id eq settlement.id }) {
            it[amountMinor] = settlement.amountMinor
            it[currency] = settlement.currency
            it[method] = settlement.method
            it[notes] = settlement.notes
            it[status] = settlement.status
            it[updatedAt] = settlement.updatedAt
            it[updatedBy] = settlement.updatedBy
            it[voidedAt] = settlement.voidedAt
            it[voidedBy] = settlement.voidedBy
        }
        if (count > 0) settlement else null
    }

    override suspend fun findById(id: UUID): Settlement? = dbQuery {
        SettlementsTable.selectAll()
            .where { SettlementsTable.id eq id }
            .map { toSettlement(it) }
            .singleOrNull()
    }

    override suspend fun findByEpicId(epicId: UUID, limit: Int, offset: Long): List<Settlement> = dbQuery {
        SettlementsTable.selectAll()
            .where { SettlementsTable.epicId eq epicId }
            .orderBy(SettlementsTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset)
            .map { toSettlement(it) }
    }

    override suspend fun countSettlementsInEpic(epicId: UUID): Int = dbQuery {
        SettlementsTable.selectAll()
            .where { SettlementsTable.epicId eq epicId }
            .count()
            .toInt()
    }

    override suspend fun countSettlementsForUserInEpic(epicId: UUID, userId: UUID): Int = dbQuery {
        SettlementsTable.selectAll()
            .where { (SettlementsTable.epicId eq epicId) and ((SettlementsTable.fromUserId eq userId) or (SettlementsTable.toUserId eq userId)) }
            .count()
            .toInt()
    }

    private fun toSettlement(row: ResultRow): Settlement = Settlement(
        id = row[SettlementsTable.id],
        epicId = row[SettlementsTable.epicId],
        fromUserId = row[SettlementsTable.fromUserId],
        toUserId = row[SettlementsTable.toUserId],
        amountMinor = row[SettlementsTable.amountMinor],
        currency = row[SettlementsTable.currency],
        method = row[SettlementsTable.method],
        notes = row[SettlementsTable.notes],
        status = row[SettlementsTable.status],
        createdAt = row[SettlementsTable.createdAt],
        updatedAt = row[SettlementsTable.updatedAt],
        updatedBy = row[SettlementsTable.updatedBy],
        voidedAt = row[SettlementsTable.voidedAt],
        voidedBy = row[SettlementsTable.voidedBy]
    )
}

class ActivityRepositoryImpl : ActivityRepository {

    override suspend fun createActivity(activity: ActivityLog): ActivityLog = dbQuery {
        ActivityLogsTable.insert {
            it[id] = activity.id
            it[epicId] = activity.epicId
            it[actorId] = activity.actorId
            it[actionType] = activity.actionType
            it[description] = activity.description
            it[metadata] = activity.metadata
            it[createdAt] = activity.createdAt
        }
        activity
    }

    override suspend fun findByEpicId(epicId: UUID, limit: Int, offset: Long): List<ActivityLog> = dbQuery {
        ActivityLogsTable.selectAll()
            .where { ActivityLogsTable.epicId eq epicId }
            .orderBy(ActivityLogsTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset)
            .map { toActivity(it) }
    }

    private fun toActivity(row: ResultRow): ActivityLog = ActivityLog(
        id = row[ActivityLogsTable.id],
        epicId = row[ActivityLogsTable.epicId],
        actorId = row[ActivityLogsTable.actorId],
        actionType = row[ActivityLogsTable.actionType],
        description = row[ActivityLogsTable.description],
        metadata = row[ActivityLogsTable.metadata],
        createdAt = row[ActivityLogsTable.createdAt]
    )
}

class IdempotencyRepositoryImpl : IdempotencyRepository {

    override suspend fun find(userId: UUID, key: String, endpoint: String): IdempotencyRecord? = dbQuery {
        val now = Instant.now()
        IdempotencyKeysTable.selectAll()
            .where {
                (IdempotencyKeysTable.userId eq userId) and
                (IdempotencyKeysTable.key eq key) and
                (IdempotencyKeysTable.endpoint eq endpoint) and
                (IdempotencyKeysTable.expiresAt greater now)
            }
            .map {
                IdempotencyRecord(
                    id = it[IdempotencyKeysTable.id],
                    userId = it[IdempotencyKeysTable.userId],
                    key = it[IdempotencyKeysTable.key],
                    endpoint = it[IdempotencyKeysTable.endpoint],
                    requestHash = it[IdempotencyKeysTable.requestHash],
                    responseStatus = it[IdempotencyKeysTable.responseStatus],
                    responseBody = it[IdempotencyKeysTable.responseBody],
                    createdAt = it[IdempotencyKeysTable.createdAt],
                    expiresAt = it[IdempotencyKeysTable.expiresAt]
                )
            }
            .singleOrNull()
    }

    override suspend fun save(record: IdempotencyRecord): Unit = dbQuery {
        IdempotencyKeysTable.insert {
            it[id] = record.id
            it[userId] = record.userId
            it[key] = record.key
            it[endpoint] = record.endpoint
            it[requestHash] = record.requestHash
            it[responseStatus] = record.responseStatus
            it[responseBody] = record.responseBody
            it[createdAt] = record.createdAt
            it[expiresAt] = record.expiresAt
        }
    }
}
