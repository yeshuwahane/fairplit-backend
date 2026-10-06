package com.yeshuwahane.fairsplit.auth.service

import com.yeshuwahane.fairsplit.auth.model.AuthProvider
import com.yeshuwahane.fairsplit.auth.model.AuthResult
import com.yeshuwahane.fairsplit.auth.repository.AuthIdentityRepository
import com.yeshuwahane.fairsplit.auth.repository.OtpRepository
import com.yeshuwahane.fairsplit.auth.util.SecurityUtils
import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.common.error.ErrorCode
import com.yeshuwahane.fairsplit.common.logging.SafeLogger
import com.yeshuwahane.fairsplit.common.validation.Validators
import com.yeshuwahane.fairsplit.config.AppConfig
import com.yeshuwahane.fairsplit.user.model.User
import com.yeshuwahane.fairsplit.user.repository.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

class PhoneAuthService(
    private val otpRepository: OtpRepository,
    private val authIdentityRepository: AuthIdentityRepository,
    private val userRepository: UserRepository,
    private val tokenService: TokenService,
    private val appConfig: AppConfig
) {
    private val logger = SafeLogger.getLogger(PhoneAuthService::class.java)

    suspend fun requestOtp(phoneNumber: String): String {
        val normalized = Validators.normalizePhone(phoneNumber)

        // Development OTP is fixed to 123456
        val otp = if (appConfig.environment == "prod") {
            (100000..999999).random().toString()
        } else {
            "123456"
        }

        val otpHash = SecurityUtils.sha256(otp)
        val expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES)

        otpRepository.createChallenge(
            phoneNumber = normalized,
            otpHash = otpHash,
            expiresAt = expiresAt
        )

        if (appConfig.environment != "prod") {
            logger.info("Generated development OTP for {}: {}", normalized, otp)
        } else {
            logger.info("Generated OTP challenge for {}", normalized)
        }

        return otp
    }

    suspend fun verifyOtp(phoneNumber: String, otp: String): AuthResult {
        val normalized = Validators.normalizePhone(phoneNumber)
        val cleanOtp = Validators.requireValidOtp(otp)

        val challenge = otpRepository.findActiveChallenge(normalized)
            ?: throw AppException.Validation(
                "No active OTP challenge found or OTP has expired for $normalized",
                ErrorCode.OTP_NOT_FOUND
            )

        if (challenge.attempts >= 5) {
            throw AppException.RateLimited(
                "Maximum verification attempts exceeded. Please request a new OTP.",
                ErrorCode.OTP_MAX_ATTEMPTS
            )
        }

        val expectedHash = SecurityUtils.sha256(cleanOtp)
        if (expectedHash != challenge.otpHash) {
            val remainingAttempts = 5 - otpRepository.incrementAttempts(challenge.id)
            throw AppException.Validation(
                "Invalid OTP. Attempts remaining: $remainingAttempts",
                ErrorCode.INVALID_OTP
            )
        }

        // Mark OTP challenge as used
        otpRepository.markConsumed(challenge.id)

        // Find or create User and AuthIdentity
        val existingIdentity = authIdentityRepository.findByProvider(AuthProvider.PHONE, normalized)
        val user: User = if (existingIdentity != null) {
            userRepository.findById(existingIdentity.userId)
                ?: throw AppException.NotFound("User associated with phone identity not found", ErrorCode.USER_NOT_FOUND)
        } else {
            // First-time signup via phone
            val newUser = userRepository.create(
                name = "",
                phone = normalized,
                profileCompleted = false
            )
            authIdentityRepository.create(
                userId = newUser.id,
                provider = AuthProvider.PHONE,
                providerUserId = normalized
            )
            newUser
        }

        val tokenPair = tokenService.issueTokenPair(user.id)
        return AuthResult(user, tokenPair)
    }
}
