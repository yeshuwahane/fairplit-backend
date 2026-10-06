package com.yeshuwahane.fairsplit.user.table

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object UsersTable : Table("users") {
    val id = uuid("id")
    val name = varchar("name", 100)
    val email = varchar("email", 254).nullable()
    val phoneNumber = varchar("phone_number", 20).nullable()
    val upiId = varchar("upi_id", 100).nullable()
    val avatarUrl = text("avatar_url").nullable()
    val profileCompleted = bool("profile_completed").default(false)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
