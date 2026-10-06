package com.yeshuwahane.fairsplit.auth.repository

import com.yeshuwahane.fairsplit.auth.model.RefreshSession
import com.yeshuwahane.fairsplit.auth.table.RefreshSessionsTable
import com.yeshuwahane.fairsplit.infrastructure.database.dbQuery
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

interface RefreshSessionRepository {
    suspend fun createSession(userId: UUID, tokenHash: String, expiresAt: Instant): RefreshSession
    suspend fun findActiveByTokenHash(tokenHash: String): RefreshSession?
    suspend fun revokeSession(tokenHash: String)
    suspend fun revokeAllForUser(userId: UUID)
    suspend fun rotateSession(oldTokenHash: String, newTokenHash: String, newExpiresAt: Instant): RefreshSession?
}

class RefreshSessionRepositoryImpl : RefreshSessionRepository {
    override suspend fun createSession(userId: UUID, tokenHash: String, expiresAt: Instant): RefreshSession = dbQuery {
        val newId = UUID.randomUUID()
        val now = Instant.now()
        RefreshSessionsTable.insert {
            it[id] = newId
            it[RefreshSessionsTable.userId] = userId
            it[RefreshSessionsTable.tokenHash] = tokenHash
            it[RefreshSessionsTable.expiresAt] = expiresAt
            it[createdAt] = now
            it[revokedAt] = null
        }
        RefreshSession(
            id = newId,
            userId = userId,
            tokenHash = tokenHash,
            expiresAt = expiresAt,
            createdAt = now,
            revokedAt = null
        )
    }

    override suspend fun findActiveByTokenHash(tokenHash: String): RefreshSession? = dbQuery {
        val now = Instant.now()
        RefreshSessionsTable.selectAll()
            .where {
                (RefreshSessionsTable.tokenHash eq tokenHash) and
                (RefreshSessionsTable.revokedAt.isNull()) and
                (RefreshSessionsTable.expiresAt greater now)
            }
            .map { toRefreshSession(it) }
            .singleOrNull()
    }

    override suspend fun revokeSession(tokenHash: String): Unit = dbQuery {
        val now = Instant.now()
        RefreshSessionsTable.update({ (RefreshSessionsTable.tokenHash eq tokenHash) and RefreshSessionsTable.revokedAt.isNull() }) {
            it[revokedAt] = now
        }
    }

    override suspend fun revokeAllForUser(userId: UUID): Unit = dbQuery {
        val now = Instant.now()
        RefreshSessionsTable.update({ (RefreshSessionsTable.userId eq userId) and RefreshSessionsTable.revokedAt.isNull() }) {
            it[revokedAt] = now
        }
    }

    override suspend fun rotateSession(
        oldTokenHash: String,
        newTokenHash: String,
        newExpiresAt: Instant
    ): RefreshSession? = dbQuery {
        val now = Instant.now()

        // Atomically revoke old active session
        val rowsUpdated = RefreshSessionsTable.update({
            (RefreshSessionsTable.tokenHash eq oldTokenHash) and
            (RefreshSessionsTable.revokedAt.isNull()) and
            (RefreshSessionsTable.expiresAt greater now)
        }) {
            it[revokedAt] = now
        }

        if (rowsUpdated == 0) {
            return@dbQuery null
        }

        val oldSession = RefreshSessionsTable.selectAll()
            .where { RefreshSessionsTable.tokenHash eq oldTokenHash }
            .single()

        val userId = oldSession[RefreshSessionsTable.userId]
        val newId = UUID.randomUUID()

        RefreshSessionsTable.insert {
            it[id] = newId
            it[RefreshSessionsTable.userId] = userId
            it[RefreshSessionsTable.tokenHash] = newTokenHash
            it[RefreshSessionsTable.expiresAt] = newExpiresAt
            it[createdAt] = now
            it[revokedAt] = null
        }

        RefreshSession(
            id = newId,
            userId = userId,
            tokenHash = newTokenHash,
            expiresAt = newExpiresAt,
            createdAt = now,
            revokedAt = null
        )
    }

    private fun toRefreshSession(row: ResultRow): RefreshSession = RefreshSession(
        id = row[RefreshSessionsTable.id],
        userId = row[RefreshSessionsTable.userId],
        tokenHash = row[RefreshSessionsTable.tokenHash],
        expiresAt = row[RefreshSessionsTable.expiresAt],
        createdAt = row[RefreshSessionsTable.createdAt],
        revokedAt = row[RefreshSessionsTable.revokedAt]
    )
}
