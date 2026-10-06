package com.yeshuwahane.fairsplit.data.table

import com.yeshuwahane.fairsplit.user.table.UsersTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object EpicsTable : Table("epics") {
    val id = uuid("id")
    val title = varchar("title", 255)
    val description = text("description").nullable()
    val iconUrl = text("icon_url").nullable()
    val createdBy = uuid("created_by").references(UsersTable.id, onDelete = ReferenceOption.RESTRICT)
    val currency = char("currency", 3).default("INR")
    val inviteCode = varchar("invite_code", 32).uniqueIndex()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object EpicMembersTable : Table("epic_members") {
    val id = uuid("id")
    val epicId = uuid("epic_id").references(EpicsTable.id, onDelete = ReferenceOption.RESTRICT)
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.RESTRICT)
    val role = varchar("role", 10).default("MEMBER")
    val joinedAt = timestamp("joined_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(epicId, userId)
    }
}

object ExpensesTable : Table("expenses") {
    val id = uuid("id")
    val epicId = uuid("epic_id").references(EpicsTable.id, onDelete = ReferenceOption.RESTRICT)
    val title = varchar("title", 255)
    val amountMinor = long("amount_minor")
    val currency = char("currency", 3).default("INR")
    val paidByUserId = uuid("paid_by_user_id").references(UsersTable.id, onDelete = ReferenceOption.RESTRICT)
    val splitType = varchar("split_type", 20)
    val receiptUrl = text("receipt_url").nullable()
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object ExpenseSplitsTable : Table("expense_splits") {
    val id = uuid("id")
    val expenseId = uuid("expense_id").references(ExpensesTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.RESTRICT)
    val amountMinor = long("amount_minor")
    val percentageBasisPts = integer("percentage_basis_pts").nullable()
    val shares = integer("shares").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(expenseId, userId)
    }
}

object SettlementsTable : Table("settlements") {
    val id = uuid("id")
    val epicId = uuid("epic_id").references(EpicsTable.id, onDelete = ReferenceOption.RESTRICT)
    val fromUserId = uuid("from_user_id").references(UsersTable.id, onDelete = ReferenceOption.RESTRICT)
    val toUserId = uuid("to_user_id").references(UsersTable.id, onDelete = ReferenceOption.RESTRICT)
    val amountMinor = long("amount_minor")
    val currency = char("currency", 3).default("INR")
    val method = varchar("method", 30).default("CASH")
    val notes = text("notes").nullable()
    val status = varchar("status", 20).default("COMPLETED")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = uuid("updated_by").references(UsersTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val voidedAt = timestamp("voided_at").nullable()
    val voidedBy = uuid("voided_by").references(UsersTable.id, onDelete = ReferenceOption.SET_NULL).nullable()

    override val primaryKey = PrimaryKey(id)
}

object ActivityLogsTable : Table("activity_logs") {
    val id = uuid("id")
    val epicId = uuid("epic_id").references(EpicsTable.id, onDelete = ReferenceOption.RESTRICT)
    val actorId = uuid("actor_id").references(UsersTable.id, onDelete = ReferenceOption.RESTRICT)
    val actionType = varchar("action_type", 50)
    val description = text("description")
    val metadata = text("metadata").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object UserDevicesTable : Table("user_devices") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val fcmToken = varchar("fcm_token", 512).uniqueIndex()
    val platform = varchar("platform", 10)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object UserPreferencesTable : Table("user_preferences") {
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val defaultCurrency = char("default_currency", 3).default("INR")
    val pushEnabled = bool("push_enabled").default(true)
    val theme = varchar("theme", 20).default("SYSTEM")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

object IdempotencyKeysTable : Table("idempotency_keys") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val key = varchar("key", 64)
    val endpoint = varchar("endpoint", 128)
    val requestHash = varchar("request_hash", 64)
    val responseStatus = integer("response_status")
    val responseBody = text("response_body")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, key, endpoint)
    }
}
