package com.yeshuwahane.fairsplit.auth.table

import com.yeshuwahane.fairsplit.user.table.UsersTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object AuthIdentitiesTable : Table("auth_identities") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 20)
    val providerUserId = varchar("provider_user_id", 255)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(provider, providerUserId)
    }
}

object PhoneOtpChallengesTable : Table("phone_otp_challenges") {
    val id = uuid("id")
    val phoneNumber = varchar("phone_number", 20)
    val otpHash = varchar("otp_hash", 64)
    val expiresAt = timestamp("expires_at")
    val attempts = integer("attempts").default(0)
    val used = bool("used").default(false)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object RefreshSessionsTable : Table("refresh_sessions") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
