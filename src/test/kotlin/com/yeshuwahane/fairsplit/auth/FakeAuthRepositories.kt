package com.yeshuwahane.fairsplit.auth

import com.yeshuwahane.fairsplit.auth.model.*
import com.yeshuwahane.fairsplit.auth.repository.*
import com.yeshuwahane.fairsplit.user.model.User
import com.yeshuwahane.fairsplit.user.repository.UserRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FakeUserRepository : UserRepository {
    val users = ConcurrentHashMap<UUID, User>()

    override suspend fun findById(id: UUID): User? = users[id]

    override suspend fun findByPhone(phone: String): User? =
        users.values.firstOrNull { it.phoneNumber == phone }

    override suspend fun findByEmail(email: String): User? =
        users.values.firstOrNull { it.email == email }

    override suspend fun create(
        id: UUID?,
        name: String,
        email: String?,
        phone: String?,
        upiId: String?,
        avatarUrl: String?,
        profileCompleted: Boolean
    ): User {
        val user = User(
            id = id ?: UUID.randomUUID(),
            name = name,
            email = email,
            phoneNumber = phone,
            upiId = upiId,
            avatarUrl = avatarUrl,
            profileCompleted = profileCompleted,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            deletedAt = null
        )
        users[user.id] = user
        return user
    }

    override suspend fun update(
        userId: UUID,
        name: String?,
        email: String?,
        phone: String?,
        upiId: String?,
        avatarUrl: String?
    ): User? {
        val existing = users[userId] ?: return null
        val updated = existing.copy(
            name = name ?: existing.name,
            email = email ?: existing.email,
            phoneNumber = phone ?: existing.phoneNumber,
            upiId = upiId ?: existing.upiId,
            avatarUrl = avatarUrl ?: existing.avatarUrl,
            profileCompleted = true,
            updatedAt = Instant.now()
        )
        users[userId] = updated
        return updated
    }
}

class FakeAuthIdentityRepository : AuthIdentityRepository {
    val identities = mutableListOf<AuthIdentity>()
    private val lock = Any()

    override suspend fun findByProvider(provider: AuthProvider, providerUserId: String): AuthIdentity? = synchronized(lock) {
        identities.firstOrNull { it.provider == provider && it.providerUserId == providerUserId }
    }

    override suspend fun findByUserId(userId: UUID): List<AuthIdentity> = synchronized(lock) {
        identities.filter { it.userId == userId }
    }

    override suspend fun create(userId: UUID, provider: AuthProvider, providerUserId: String): AuthIdentity = synchronized(lock) {
        val identity = AuthIdentity(
            id = UUID.randomUUID(),
            userId = userId,
            provider = provider,
            providerUserId = providerUserId,
            createdAt = Instant.now()
        )
        identities.add(identity)
        identity
    }
}

class FakeOtpRepository : OtpRepository {
    val challenges = mutableListOf<OtpChallenge>()
    private val lock = Any()

    override suspend fun createChallenge(phoneNumber: String, otpHash: String, expiresAt: Instant): OtpChallenge = synchronized(lock) {
        val challenge = OtpChallenge(
            id = UUID.randomUUID(),
            phoneNumber = phoneNumber,
            otpHash = otpHash,
            expiresAt = expiresAt,
            attempts = 0,
            used = false,
            createdAt = Instant.now()
        )
        challenges.add(challenge)
        challenge
    }

    override suspend fun findActiveChallenge(phoneNumber: String): OtpChallenge? = synchronized(lock) {
        val now = Instant.now()
        challenges
            .filter { it.phoneNumber == phoneNumber && !it.used && it.expiresAt.isAfter(now) }
            .maxByOrNull { it.createdAt }
    }

    override suspend fun incrementAttempts(challengeId: UUID): Int = synchronized(lock) {
        val index = challenges.indexOfFirst { it.id == challengeId }
        if (index != -1) {
            val updated = challenges[index].copy(attempts = challenges[index].attempts + 1)
            challenges[index] = updated
            updated.attempts
        } else {
            0
        }
    }

    override suspend fun markConsumed(challengeId: UUID): Unit = synchronized(lock) {
        val index = challenges.indexOfFirst { it.id == challengeId }
        if (index != -1) {
            challenges[index] = challenges[index].copy(used = true)
        }
    }
}

class FakeRefreshSessionRepository : RefreshSessionRepository {
    val sessions = ConcurrentHashMap<String, RefreshSession>()
    private val lock = Any()

    override suspend fun createSession(userId: UUID, tokenHash: String, expiresAt: Instant): RefreshSession {
        val session = RefreshSession(
            id = UUID.randomUUID(),
            userId = userId,
            tokenHash = tokenHash,
            expiresAt = expiresAt,
            createdAt = Instant.now(),
            revokedAt = null
        )
        sessions[tokenHash] = session
        return session
    }

    override suspend fun findActiveByTokenHash(tokenHash: String): RefreshSession? {
        val session = sessions[tokenHash] ?: return null
        if (session.revokedAt != null || session.expiresAt.isBefore(Instant.now())) {
            return null
        }
        return session
    }

    override suspend fun revokeSession(tokenHash: String) {
        val session = sessions[tokenHash] ?: return
        sessions[tokenHash] = session.copy(revokedAt = Instant.now())
    }

    override suspend fun revokeAllForUser(userId: UUID) {
        sessions.values.filter { it.userId == userId }.forEach {
            sessions[it.tokenHash] = it.copy(revokedAt = Instant.now())
        }
    }

    override suspend fun rotateSession(
        oldTokenHash: String,
        newTokenHash: String,
        newExpiresAt: Instant
    ): RefreshSession? = synchronized(lock) {
        val session = sessions[oldTokenHash] ?: return null
        if (session.revokedAt != null || session.expiresAt.isBefore(Instant.now())) {
            return null
        }
        sessions[oldTokenHash] = session.copy(revokedAt = Instant.now())

        val newSession = RefreshSession(
            id = UUID.randomUUID(),
            userId = session.userId,
            tokenHash = newTokenHash,
            expiresAt = newExpiresAt,
            createdAt = Instant.now(),
            revokedAt = null
        )
        sessions[newTokenHash] = newSession
        newSession
    }
}
