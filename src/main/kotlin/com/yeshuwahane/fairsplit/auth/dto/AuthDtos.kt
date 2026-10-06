package com.yeshuwahane.fairsplit.auth.dto

import com.yeshuwahane.fairsplit.auth.model.AuthResult
import com.yeshuwahane.fairsplit.user.dto.UserDto
import kotlinx.serialization.Serializable

@Serializable
data class RequestOtpRequest(
    val phoneNumber: String? = null,
    val phone: String? = null
) {
    val phoneValue: String
        get() = phoneNumber?.takeIf { it.isNotBlank() }
            ?: phone?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("phone or phoneNumber is required")
}

@Serializable
data class RequestOtpResponse(
    val message: String,
    val devOtp: String? = null
)

@Serializable
data class VerifyOtpRequest(
    val phoneNumber: String? = null,
    val otp: String = "",
    val phone: String? = null
) {
    val phoneValue: String
        get() = phoneNumber?.takeIf { it.isNotBlank() }
            ?: phone?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("phone or phoneNumber is required")
}

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)

@Serializable
data class LogoutRequest(
    val refreshToken: String? = null
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val user: UserDto
) {
    companion object {
        fun fromResult(result: AuthResult): AuthResponse = AuthResponse(
            accessToken = result.tokenPair.accessToken,
            refreshToken = result.tokenPair.refreshToken,
            tokenType = "Bearer",
            expiresIn = result.tokenPair.accessExpiresAt,
            user = UserDto.fromDomain(result.user)
        )
    }
}

@Serializable
data class TokenRefreshResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long
)
