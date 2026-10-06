package com.yeshuwahane.fairsplit.auth.repository

import com.yeshuwahane.fairsplit.auth.model.OtpChallenge
import com.yeshuwahane.fairsplit.auth.table.PhoneOtpChallengesTable
import com.yeshuwahane.fairsplit.infrastructure.database.dbQuery
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

interface OtpRepository {
    suspend fun createChallenge(phoneNumber: String, otpHash: String, expiresAt: Instant): OtpChallenge
    suspend fun findActiveChallenge(phoneNumber: String): OtpChallenge?
    suspend fun incrementAttempts(challengeId: UUID): Int
    suspend fun markConsumed(challengeId: UUID)
}

class OtpRepositoryImpl : OtpRepository {
    override suspend fun createChallenge(phoneNumber: String, otpHash: String, expiresAt: Instant): OtpChallenge = dbQuery {
        val newId = UUID.randomUUID()
        val now = Instant.now()
        PhoneOtpChallengesTable.insert {
            it[id] = newId
            it[PhoneOtpChallengesTable.phoneNumber] = phoneNumber
            it[PhoneOtpChallengesTable.otpHash] = otpHash
            it[PhoneOtpChallengesTable.expiresAt] = expiresAt
            it[attempts] = 0
            it[used] = false
            it[createdAt] = now
        }
        OtpChallenge(
            id = newId,
            phoneNumber = phoneNumber,
            otpHash = otpHash,
            expiresAt = expiresAt,
            attempts = 0,
            used = false,
            createdAt = now
        )
    }

    override suspend fun findActiveChallenge(phoneNumber: String): OtpChallenge? = dbQuery {
        val now = Instant.now()
        PhoneOtpChallengesTable.selectAll()
            .where {
                (PhoneOtpChallengesTable.phoneNumber eq phoneNumber) and
                (PhoneOtpChallengesTable.used eq false) and
                (PhoneOtpChallengesTable.expiresAt greater now)
            }
            .orderBy(PhoneOtpChallengesTable.createdAt to SortOrder.DESC)
            .limit(1)
            .map { toOtpChallenge(it) }
            .singleOrNull()
    }

    override suspend fun incrementAttempts(challengeId: UUID): Int = dbQuery {
        val current = PhoneOtpChallengesTable.selectAll()
            .where { PhoneOtpChallengesTable.id eq challengeId }
            .singleOrNull()?.get(PhoneOtpChallengesTable.attempts) ?: 0
        val updated = current + 1
        PhoneOtpChallengesTable.update({ PhoneOtpChallengesTable.id eq challengeId }) {
            it[attempts] = updated
        }
        updated
    }

    override suspend fun markConsumed(challengeId: UUID): Unit = dbQuery {
        PhoneOtpChallengesTable.update({ PhoneOtpChallengesTable.id eq challengeId }) {
            it[used] = true
        }
    }

    private fun toOtpChallenge(row: ResultRow): OtpChallenge = OtpChallenge(
        id = row[PhoneOtpChallengesTable.id],
        phoneNumber = row[PhoneOtpChallengesTable.phoneNumber],
        otpHash = row[PhoneOtpChallengesTable.otpHash],
        expiresAt = row[PhoneOtpChallengesTable.expiresAt],
        attempts = row[PhoneOtpChallengesTable.attempts],
        used = row[PhoneOtpChallengesTable.used],
        createdAt = row[PhoneOtpChallengesTable.createdAt]
    )
}
