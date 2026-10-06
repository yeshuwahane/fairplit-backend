package com.yeshuwahane.fairsplit.auth.service

import com.yeshuwahane.fairsplit.auth.model.TokenPair
import com.yeshuwahane.fairsplit.auth.repository.RefreshSessionRepository
import com.yeshuwahane.fairsplit.auth.util.SecurityUtils
import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.common.error.ErrorCode
import com.yeshuwahane.fairsplit.config.JwtConfig
import com.yeshuwahane.fairsplit.infrastructure.auth.JwtProvider
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class TokenService(
    private val jwtProvider: JwtProvider,
    private val refreshSessionRepository: RefreshSessionRepository,
    private val jwtConfig: JwtConfig
) {
    suspend fun issueTokenPair(userId: UUID): TokenPair {
        val accessToken = jwtProvider.createAccessToken(userId)
        val rawRefreshToken = SecurityUtils.generateSecureRandomToken()
        val tokenHash = SecurityUtils.sha256(rawRefreshToken)

        val now = Instant.now()
        val accessExpiry = now.plus(jwtConfig.accessTtlDays, ChronoUnit.DAYS).toEpochMilli()
        val refreshExpiryInstant = now.plus(jwtConfig.refreshTtlDays, ChronoUnit.DAYS)

        refreshSessionRepository.createSession(
            userId = userId,
            tokenHash = tokenHash,
            expiresAt = refreshExpiryInstant
        )

        return TokenPair(
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            accessExpiresAt = accessExpiry,
            refreshExpiresAt = refreshExpiryInstant.toEpochMilli()
        )
    }

    suspend fun refresh(rawRefreshToken: String): TokenPair {
        if (rawRefreshToken.isBlank()) {
            throw AppException.Unauthorized("Refresh token must not be blank", ErrorCode.INVALID_TOKEN)
        }

        val oldTokenHash = SecurityUtils.sha256(rawRefreshToken)
        val newRawRefreshToken = SecurityUtils.generateSecureRandomToken()
        val newTokenHash = SecurityUtils.sha256(newRawRefreshToken)

        val now = Instant.now()
        val newRefreshExpiryInstant = now.plus(jwtConfig.refreshTtlDays, ChronoUnit.DAYS)

        // Concurrency-safe atomic rotation
        val newSession = refreshSessionRepository.rotateSession(
            oldTokenHash = oldTokenHash,
            newTokenHash = newTokenHash,
            newExpiresAt = newRefreshExpiryInstant
        ) ?: throw AppException.Unauthorized("Invalid, expired, or already-rotated refresh token", ErrorCode.REFRESH_TOKEN_EXPIRED)

        val accessToken = jwtProvider.createAccessToken(newSession.userId)
        val accessExpiry = now.plus(jwtConfig.accessTtlDays, ChronoUnit.DAYS).toEpochMilli()

        return TokenPair(
            accessToken = accessToken,
            refreshToken = newRawRefreshToken,
            accessExpiresAt = accessExpiry,
            refreshExpiresAt = newRefreshExpiryInstant.toEpochMilli()
        )
    }

    suspend fun revoke(rawRefreshToken: String) {
        if (rawRefreshToken.isNotBlank()) {
            val tokenHash = SecurityUtils.sha256(rawRefreshToken)
            refreshSessionRepository.revokeSession(tokenHash)
        }
    }

    suspend fun revokeAllForUser(userId: UUID) {
        refreshSessionRepository.revokeAllForUser(userId)
    }
}
