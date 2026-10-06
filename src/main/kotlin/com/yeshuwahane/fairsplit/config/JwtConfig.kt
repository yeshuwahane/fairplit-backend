package com.yeshuwahane.fairsplit.config

import io.ktor.server.config.*

data class JwtConfig(
    val secret: String = "change-me-in-production-long-secret-key-32-chars-min",
    val issuer: String = "fairsplit",
    val audience: String = "fairsplit-api",
    val accessTtlDays: Long = 30,
    val refreshTtlDays: Long = 90
) {
    companion object {
        fun fromConfig(config: ApplicationConfig): JwtConfig {
            val secret = config.propertyOrNull("jwt.secret")?.getString() ?: "change-me-in-production-long-secret-key-32-chars-min"
            val issuer = config.propertyOrNull("jwt.issuer")?.getString() ?: "fairsplit"
            val audience = config.propertyOrNull("jwt.audience")?.getString() ?: "fairsplit-api"
            val accessTtl = config.propertyOrNull("jwt.accessTtlDays")?.getString()?.toLongOrNull() ?: 30L
            val refreshTtl = config.propertyOrNull("jwt.refreshTtlDays")?.getString()?.toLongOrNull() ?: 90L
            return JwtConfig(
                secret = secret,
                issuer = issuer,
                audience = audience,
                accessTtlDays = accessTtl,
                refreshTtlDays = refreshTtl
            )
        }
    }
}
