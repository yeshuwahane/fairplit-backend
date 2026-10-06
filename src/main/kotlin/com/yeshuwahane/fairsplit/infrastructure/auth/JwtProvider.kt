package com.yeshuwahane.fairsplit.infrastructure.auth

import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.common.error.ErrorCode
import com.yeshuwahane.fairsplit.config.JwtConfig
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

class JwtProvider(private val config: JwtConfig) {
    private val key: SecretKey by lazy {
        val secretBytes = config.secret.toByteArray(StandardCharsets.UTF_8)
        val validBytes = if (secretBytes.size < 32) {
            // Pad to 32 bytes minimum for HMAC-SHA256
            secretBytes.copyOf(32)
        } else {
            secretBytes
        }
        Keys.hmacShaKeyFor(validBytes)
    }

    fun createAccessToken(userId: UUID): String {
        val now = Instant.now()
        val expiry = now.plus(config.accessTtlDays, ChronoUnit.DAYS)

        return Jwts.builder()
            .subject(userId.toString())
            .issuer(config.issuer)
            .audience().add(config.audience).and()
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .signWith(key)
            .compact()
    }

    fun verifyAccessToken(token: String): UUID {
        try {
            val claims: Claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(config.issuer)
                .build()
                .parseSignedClaims(token)
                .payload

            val subject = claims.subject ?: throw AppException.Unauthorized("JWT subject is missing", ErrorCode.INVALID_TOKEN)
            return UUID.fromString(subject)
        } catch (e: ExpiredJwtException) {
            throw AppException.Unauthorized("Access token has expired", ErrorCode.TOKEN_EXPIRED, cause = e)
        } catch (e: JwtException) {
            throw AppException.Unauthorized("Invalid access token: ${e.message}", ErrorCode.INVALID_TOKEN, cause = e)
        } catch (e: IllegalArgumentException) {
            throw AppException.Unauthorized("Invalid user UUID in token", ErrorCode.INVALID_TOKEN, cause = e)
        }
    }
}
