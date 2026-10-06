package com.yeshuwahane.fairsplit.auth.model

import com.yeshuwahane.fairsplit.user.model.User
import java.time.Instant
import java.util.UUID

enum class AuthProvider {
    PHONE,
    GOOGLE
}

data class AuthIdentity(
    val id: UUID,
    val userId: UUID,
    val provider: AuthProvider,
    val providerUserId: String,
    val createdAt: Instant
)

data class OtpChallenge(
    val id: UUID,
    val phoneNumber: String,
    val otpHash: String,
    val expiresAt: Instant,
    val attempts: Int,
    val used: Boolean,
    val createdAt: Instant
)

data class RefreshSession(
    val id: UUID,
    val userId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    val revokedAt: Instant?
)

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long
)

data class AuthResult(
    val user: User,
    val tokenPair: TokenPair
)
