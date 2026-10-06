package com.yeshuwahane.fairsplit.auth

import com.yeshuwahane.fairsplit.auth.model.AuthProvider
import com.yeshuwahane.fairsplit.auth.service.PhoneAuthService
import com.yeshuwahane.fairsplit.auth.service.TokenService
import com.yeshuwahane.fairsplit.auth.util.SecurityUtils
import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.config.AppConfig
import com.yeshuwahane.fairsplit.config.JwtConfig
import com.yeshuwahane.fairsplit.infrastructure.auth.JwtProvider
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

class PhoneAuthServiceTest {

    private val jwtConfig = JwtConfig(
        secret = "secret-key-that-is-at-least-32-bytes-long-for-testing",
        issuer = "fairsplit-test",
        audience = "fairsplit-api-test",
        accessTtlDays = 30,
        refreshTtlDays = 90
    )
    private val appConfig = AppConfig(environment = "dev", port = 8080)
    private lateinit var userRepository: FakeUserRepository
    private lateinit var authIdentityRepository: FakeAuthIdentityRepository
    private lateinit var otpRepository: FakeOtpRepository
    private lateinit var refreshSessionRepository: FakeRefreshSessionRepository
    private lateinit var tokenService: TokenService
    private lateinit var phoneAuthService: PhoneAuthService

    @BeforeTest
    fun setUp() {
        userRepository = FakeUserRepository()
        authIdentityRepository = FakeAuthIdentityRepository()
        otpRepository = FakeOtpRepository()
        refreshSessionRepository = FakeRefreshSessionRepository()
        tokenService = TokenService(JwtProvider(jwtConfig), refreshSessionRepository, jwtConfig)
        phoneAuthService = PhoneAuthService(
            otpRepository = otpRepository,
            authIdentityRepository = authIdentityRepository,
            userRepository = userRepository,
            tokenService = tokenService,
            appConfig = appConfig
        )
    }

    @Test
    fun testRequestOtpGeneratesDevOtpAndStoresHashed() {
        runBlocking {
            val phone = "+919876543210"
            val otp = phoneAuthService.requestOtp(phone)

            assertEquals("123456", otp)

            val challenge = otpRepository.findActiveChallenge(phone)
            assertNotNull(challenge)
            assertEquals(SecurityUtils.sha256("123456"), challenge.otpHash)
            assertFalse(challenge.used)
            assertEquals(0, challenge.attempts)
        }
    }

    @Test
    fun testFirstTimeSignupCreatesUserAndIdentity() {
        runBlocking {
            val phone = "+919876543210"
            phoneAuthService.requestOtp(phone)

            val result = phoneAuthService.verifyOtp(phone, "123456")
            assertNotNull(result.user)
            assertEquals(phone, result.user.phoneNumber)
            assertNotNull(result.tokenPair.accessToken)
            assertNotNull(result.tokenPair.refreshToken)

            // Verify identity exists
            val identity = authIdentityRepository.findByProvider(AuthProvider.PHONE, phone)
            assertNotNull(identity)
            assertEquals(result.user.id, identity.userId)

            // Verify user in repository
            val storedUser = userRepository.findById(result.user.id)
            assertNotNull(storedUser)
        }
    }

    @Test
    fun testExistingUserLoginReusesUserWithoutDuplicating() {
        runBlocking {
            val phone = "+919876543210"

            // First login
            phoneAuthService.requestOtp(phone)
            val firstResult = phoneAuthService.verifyOtp(phone, "123456")

            // Second login with same phone
            phoneAuthService.requestOtp(phone)
            val secondResult = phoneAuthService.verifyOtp(phone, "123456")

            assertEquals(firstResult.user.id, secondResult.user.id, "Existing user must be reused")
            assertEquals(1, userRepository.users.size, "Only 1 user should exist in repository")
            assertEquals(1, authIdentityRepository.identities.size, "Only 1 identity should exist")
        }
    }

    @Test
    fun testIncorrectOtpIncrementsAttemptsAndFails() {
        runBlocking {
            val phone = "+919876543210"
            phoneAuthService.requestOtp(phone)

            assertFailsWith<AppException.Validation> {
                phoneAuthService.verifyOtp(phone, "999999")
            }

            val challenge = otpRepository.findActiveChallenge(phone)
            assertNotNull(challenge)
            assertEquals(1, challenge.attempts)
        }
    }

    @Test
    fun testMaxAttemptsFails() {
        runBlocking {
            val phone = "+919876543210"
            phoneAuthService.requestOtp(phone)

            // Make 5 incorrect attempts
            repeat(5) {
                try {
                    phoneAuthService.verifyOtp(phone, "00000$it")
                } catch (_: AppException.Validation) {}
            }

            // 6th attempt should be blocked by rate limit / max attempts
            assertFailsWith<AppException.RateLimited> {
                phoneAuthService.verifyOtp(phone, "123456")
            }
        }
    }

    @Test
    fun testExpiredOtpFails() {
        runBlocking {
            val phone = "+919876543210"
            // Insert expired challenge manually
            otpRepository.createChallenge(
                phoneNumber = phone,
                otpHash = SecurityUtils.sha256("123456"),
                expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES)
            )

            assertFailsWith<AppException.Validation> {
                phoneAuthService.verifyOtp(phone, "123456")
            }
        }
    }

    @Test
    fun testConsumedOtpCannotBeReused() {
        runBlocking {
            val phone = "+919876543210"
            phoneAuthService.requestOtp(phone)

            // First verification consumes challenge
            val result = phoneAuthService.verifyOtp(phone, "123456")
            assertNotNull(result)

            // Second verification with same challenge must fail
            assertFailsWith<AppException.Validation> {
                phoneAuthService.verifyOtp(phone, "123456")
            }
        }
    }
}
