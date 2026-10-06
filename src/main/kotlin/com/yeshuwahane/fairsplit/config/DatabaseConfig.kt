package com.yeshuwahane.fairsplit.config

import io.ktor.server.config.*

data class DatabaseConfig(
    val url: String = "",
    val user: String = "",
    val password: String = "",
    val maxPoolSize: Int = 10,
    val minIdle: Int = 2,
    val connectionTimeoutMs: Long = 30000L
) {
    companion object {
        fun fromConfig(config: ApplicationConfig): DatabaseConfig {
            val url = config.propertyOrNull("database.url")?.getString() ?: ""
            val user = config.propertyOrNull("database.user")?.getString() ?: ""
            val password = config.propertyOrNull("database.password")?.getString() ?: ""
            val poolSize = config.propertyOrNull("database.maxPoolSize")?.getString()?.toIntOrNull() ?: 10
            val minIdle = config.propertyOrNull("database.minIdle")?.getString()?.toIntOrNull() ?: 2
            val timeout = config.propertyOrNull("database.connectionTimeoutMs")?.getString()?.toLongOrNull() ?: 30000L
            return DatabaseConfig(
                url = url,
                user = user,
                password = password,
                maxPoolSize = poolSize,
                minIdle = minIdle,
                connectionTimeoutMs = timeout
            )
        }
    }
}
