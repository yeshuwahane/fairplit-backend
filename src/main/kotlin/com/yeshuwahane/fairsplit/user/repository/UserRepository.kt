package com.yeshuwahane.fairsplit.user.repository

import com.yeshuwahane.fairsplit.infrastructure.database.dbQuery
import com.yeshuwahane.fairsplit.user.model.User
import com.yeshuwahane.fairsplit.user.table.UsersTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

interface UserRepository {
    suspend fun findById(id: UUID): User?
    suspend fun findByPhone(phone: String): User?
    suspend fun findByEmail(email: String): User?
    suspend fun create(
        id: UUID? = null,
        name: String,
        email: String? = null,
        phone: String? = null,
        upiId: String? = null,
        avatarUrl: String? = null,
        profileCompleted: Boolean = false
    ): User
    suspend fun update(
        userId: UUID,
        name: String? = null,
        email: String? = null,
        phone: String? = null,
        upiId: String? = null,
        avatarUrl: String? = null
    ): User?
}

class UserRepositoryImpl : UserRepository {
    override suspend fun findById(id: UUID): User? = dbQuery {
        UsersTable.selectAll()
            .where { (UsersTable.id eq id) and UsersTable.deletedAt.isNull() }
            .map { toUser(it) }
            .singleOrNull()
    }

    override suspend fun findByPhone(phone: String): User? = dbQuery {
        UsersTable.selectAll()
            .where { (UsersTable.phoneNumber eq phone) and UsersTable.deletedAt.isNull() }
            .map { toUser(it) }
            .singleOrNull()
    }

    override suspend fun findByEmail(email: String): User? = dbQuery {
        UsersTable.selectAll()
            .where { (UsersTable.email eq email) and UsersTable.deletedAt.isNull() }
            .map { toUser(it) }
            .singleOrNull()
    }

    override suspend fun create(
        id: UUID?,
        name: String,
        email: String?,
        phone: String?,
        upiId: String?,
        avatarUrl: String?,
        profileCompleted: Boolean
    ): User = dbQuery {
        val newId = id ?: UUID.randomUUID()
        val now = Instant.now()
        UsersTable.insert {
            it[UsersTable.id] = newId
            it[UsersTable.name] = name
            it[UsersTable.email] = email
            it[phoneNumber] = phone
            it[UsersTable.upiId] = upiId
            it[UsersTable.avatarUrl] = avatarUrl
            it[UsersTable.profileCompleted] = profileCompleted
            it[createdAt] = now
            it[updatedAt] = now
            it[deletedAt] = null
        }
        User(
            id = newId,
            name = name,
            email = email,
            phoneNumber = phone,
            upiId = upiId,
            avatarUrl = avatarUrl,
            profileCompleted = profileCompleted,
            createdAt = now,
            updatedAt = now,
            deletedAt = null
        )
    }

    override suspend fun update(
        userId: UUID,
        name: String?,
        email: String?,
        phone: String?,
        upiId: String?,
        avatarUrl: String?
    ): User? = dbQuery {
        val now = Instant.now()
        val count = UsersTable.update({ (UsersTable.id eq userId) and UsersTable.deletedAt.isNull() }) {
            if (name != null) it[UsersTable.name] = name
            if (email != null) it[UsersTable.email] = email
            if (phone != null) it[phoneNumber] = phone
            if (upiId != null) it[UsersTable.upiId] = upiId
            if (avatarUrl != null) it[UsersTable.avatarUrl] = avatarUrl
            it[profileCompleted] = true
            it[updatedAt] = now
        }
        if (count > 0) {
            UsersTable.selectAll()
                .where { (UsersTable.id eq userId) and UsersTable.deletedAt.isNull() }
                .map { toUser(it) }
                .singleOrNull()
        } else null
    }

    private fun toUser(row: ResultRow): User = User(
        id = row[UsersTable.id],
        name = row[UsersTable.name],
        email = row[UsersTable.email],
        phoneNumber = row[UsersTable.phoneNumber],
        upiId = row[UsersTable.upiId],
        avatarUrl = row[UsersTable.avatarUrl],
        profileCompleted = row[UsersTable.profileCompleted],
        createdAt = row[UsersTable.createdAt],
        updatedAt = row[UsersTable.updatedAt],
        deletedAt = row[UsersTable.deletedAt]
    )
}
