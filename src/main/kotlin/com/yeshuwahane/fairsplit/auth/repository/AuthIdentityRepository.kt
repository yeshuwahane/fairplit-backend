package com.yeshuwahane.fairsplit.auth.repository

import com.yeshuwahane.fairsplit.auth.model.AuthIdentity
import com.yeshuwahane.fairsplit.auth.model.AuthProvider
import com.yeshuwahane.fairsplit.auth.table.AuthIdentitiesTable
import com.yeshuwahane.fairsplit.infrastructure.database.dbQuery
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant
import java.util.UUID

interface AuthIdentityRepository {
    suspend fun findByProvider(provider: AuthProvider, providerUserId: String): AuthIdentity?
    suspend fun findByUserId(userId: UUID): List<AuthIdentity>
    suspend fun create(userId: UUID, provider: AuthProvider, providerUserId: String): AuthIdentity
}

class AuthIdentityRepositoryImpl : AuthIdentityRepository {
    override suspend fun findByProvider(provider: AuthProvider, providerUserId: String): AuthIdentity? = dbQuery {
        AuthIdentitiesTable.selectAll()
            .where { (AuthIdentitiesTable.provider eq provider.name) and (AuthIdentitiesTable.providerUserId eq providerUserId) }
            .map { toAuthIdentity(it) }
            .singleOrNull()
    }

    override suspend fun findByUserId(userId: UUID): List<AuthIdentity> = dbQuery {
        AuthIdentitiesTable.selectAll()
            .where { AuthIdentitiesTable.userId eq userId }
            .map { toAuthIdentity(it) }
    }

    override suspend fun create(userId: UUID, provider: AuthProvider, providerUserId: String): AuthIdentity = dbQuery {
        val newId = UUID.randomUUID()
        val now = Instant.now()
        AuthIdentitiesTable.insert {
            it[id] = newId
            it[AuthIdentitiesTable.userId] = userId
            it[AuthIdentitiesTable.provider] = provider.name
            it[AuthIdentitiesTable.providerUserId] = providerUserId
            it[createdAt] = now
        }
        AuthIdentity(
            id = newId,
            userId = userId,
            provider = provider,
            providerUserId = providerUserId,
            createdAt = now
        )
    }

    private fun toAuthIdentity(row: ResultRow): AuthIdentity = AuthIdentity(
        id = row[AuthIdentitiesTable.id],
        userId = row[AuthIdentitiesTable.userId],
        provider = AuthProvider.valueOf(row[AuthIdentitiesTable.provider]),
        providerUserId = row[AuthIdentitiesTable.providerUserId],
        createdAt = row[AuthIdentitiesTable.createdAt]
    )
}
