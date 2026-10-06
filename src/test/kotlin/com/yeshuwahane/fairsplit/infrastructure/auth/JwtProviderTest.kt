package com.yeshuwahane.fairsplit.infrastructure.auth

import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.config.JwtConfig
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JwtProviderTest {

    private val jwtConfig = JwtConfig(
        secret = "test-secret-key-that-is-at-least-32-bytes-long",
        issuer = "fairsplit-test",
        audience = "fairsplit-api-test",
        accessTtlDays = 30,
        refreshTtlDays = 90
    )

    private val jwtProvider = JwtProvider(jwtConfig)

    @Test
    fun testCreateAndVerifyValidAccessToken() {
        val userId = UUID.randomUUID()
        val token = jwtProvider.createAccessToken(userId)

        val verifiedUserId = jwtProvider.verifyAccessToken(token)
        assertEquals(userId, verifiedUserId)
    }

    @Test
    fun testTamperedTokenFails() {
        val userId = UUID.randomUUID()
        val token = jwtProvider.createAccessToken(userId)
        val tampered = token.dropLast(5) + "abcde"

        assertFailsWith<AppException.Unauthorized> {
            jwtProvider.verifyAccessToken(tampered)
        }
    }

    @Test
    fun testExpiredTokenFails() {
        val expiredConfig = JwtConfig(
            secret = "test-secret-key-that-is-at-least-32-bytes-long",
            issuer = "fairsplit-test",
            audience = "fairsplit-api-test",
            accessTtlDays = -1, // Expired yesterday
            refreshTtlDays = 90
        )
        val expiredProvider = JwtProvider(expiredConfig)
        val userId = UUID.randomUUID()
        val token = expiredProvider.createAccessToken(userId)

        assertFailsWith<AppException.Unauthorized> {
            expiredProvider.verifyAccessToken(token)
        }
    }

    @Test
    fun testWrongSecretFails() {
        val otherProvider = JwtProvider(jwtConfig.copy(secret = "another-secret-key-at-least-32-bytes-long-here"))
        val userId = UUID.randomUUID()
        val token = otherProvider.createAccessToken(userId)

        assertFailsWith<AppException.Unauthorized> {
            jwtProvider.verifyAccessToken(token)
        }
    }
}
