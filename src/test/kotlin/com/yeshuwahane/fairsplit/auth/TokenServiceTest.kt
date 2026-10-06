package com.yeshuwahane.fairsplit.auth

import com.yeshuwahane.fairsplit.auth.service.TokenService
import com.yeshuwahane.fairsplit.auth.util.SecurityUtils
import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.config.JwtConfig
import com.yeshuwahane.fairsplit.infrastructure.auth.JwtProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.*

class TokenServiceTest {

    private val jwtConfig = JwtConfig(
        secret = "secret-key-that-is-at-least-32-bytes-long-for-testing",
        issuer = "fairsplit-test",
        audience = "fairsplit-api-test",
        accessTtlDays = 30,
        refreshTtlDays = 90
    )
    private val jwtProvider = JwtProvider(jwtConfig)
    private lateinit var refreshSessionRepository: FakeRefreshSessionRepository
    private lateinit var tokenService: TokenService

    @BeforeTest
    fun setUp() {
        refreshSessionRepository = FakeRefreshSessionRepository()
        tokenService = TokenService(jwtProvider, refreshSessionRepository, jwtConfig)
    }

    @Test
    fun testIssueTokenPair() {
        runBlocking {
            val userId = UUID.randomUUID()
            val pair = tokenService.issueTokenPair(userId)

            assertNotNull(pair.accessToken)
            assertNotNull(pair.refreshToken)
            assertEquals(userId, jwtProvider.verifyAccessToken(pair.accessToken))

            // Verify hash stored in DB, not raw token
            val tokenHash = SecurityUtils.sha256(pair.refreshToken)
            assertNotNull(refreshSessionRepository.findActiveByTokenHash(tokenHash))
        }
    }

    @Test
    fun testRefreshValidToken() {
        runBlocking {
            val userId = UUID.randomUUID()
            val initialPair = tokenService.issueTokenPair(userId)

            val newPair = tokenService.refresh(initialPair.refreshToken)
            assertNotNull(newPair.accessToken)
            assertNotNull(newPair.refreshToken)
            assertNotEquals(initialPair.refreshToken, newPair.refreshToken)
            assertEquals(userId, jwtProvider.verifyAccessToken(newPair.accessToken))

            // Old token must be invalidated
            val oldHash = SecurityUtils.sha256(initialPair.refreshToken)
            assertNull(refreshSessionRepository.findActiveByTokenHash(oldHash))

            // New token must be active
            val newHash = SecurityUtils.sha256(newPair.refreshToken)
            assertNotNull(refreshSessionRepository.findActiveByTokenHash(newHash))
        }
    }

    @Test
    fun testReuseOfOldRefreshTokenFails() {
        runBlocking {
            val userId = UUID.randomUUID()
            val initialPair = tokenService.issueTokenPair(userId)

            // First rotation succeeds
            val newPair = tokenService.refresh(initialPair.refreshToken)
            assertNotNull(newPair)

            // Reusing the old refresh token fails
            assertFailsWith<AppException.Unauthorized> {
                tokenService.refresh(initialPair.refreshToken)
            }
        }
    }

    @Test
    fun testRefreshWithExpiredTokenFails() {
        runBlocking {
            val userId = UUID.randomUUID()
            val rawToken = "expired-raw-token"
            val tokenHash = SecurityUtils.sha256(rawToken)

            // Create expired session in repo
            refreshSessionRepository.createSession(
                userId = userId,
                tokenHash = tokenHash,
                expiresAt = Instant.now().minus(1, ChronoUnit.DAYS)
            )

            assertFailsWith<AppException.Unauthorized> {
                tokenService.refresh(rawToken)
            }
        }
    }

    @Test
    fun testRefreshWithRevokedTokenFails() {
        runBlocking {
            val userId = UUID.randomUUID()
            val pair = tokenService.issueTokenPair(userId)

            // Revoke token
            tokenService.revoke(pair.refreshToken)

            assertFailsWith<AppException.Unauthorized> {
                tokenService.refresh(pair.refreshToken)
            }
        }
    }

    @Test
    fun testLogoutRevokesSession() {
        runBlocking {
            val userId = UUID.randomUUID()
            val pair = tokenService.issueTokenPair(userId)

            tokenService.revoke(pair.refreshToken)

            val tokenHash = SecurityUtils.sha256(pair.refreshToken)
            assertNull(refreshSessionRepository.findActiveByTokenHash(tokenHash))

            assertFailsWith<AppException.Unauthorized> {
                tokenService.refresh(pair.refreshToken)
            }
        }
    }

    @Test
    fun testConcurrentRefreshOnlyOneSucceeds() {
        runBlocking {
            val userId = UUID.randomUUID()
            val pair = tokenService.issueTokenPair(userId)

            // Run 5 concurrent refresh attempts with the exact same refresh token
            val attempts = (1..5).map {
                async(Dispatchers.IO) {
                    try {
                        tokenService.refresh(pair.refreshToken)
                        true
                    } catch (e: AppException.Unauthorized) {
                        false
                    }
                }
            }.awaitAll()

            // Exactly one attempt must succeed, all others must fail
            val successCount = attempts.count { it }
            val failureCount = attempts.count { !it }
            assertEquals(1, successCount, "Only one concurrent refresh attempt should succeed")
            assertEquals(4, failureCount, "All other concurrent attempts must be rejected")
        }
    }
}
