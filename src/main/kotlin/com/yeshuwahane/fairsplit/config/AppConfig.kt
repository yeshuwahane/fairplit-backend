package com.yeshuwahane.fairsplit.config

import io.ktor.server.config.*

data class AppConfig(
    val environment: String = "dev",
    val port: Int = 8080
) {
    companion object {
        fun fromConfig(config: ApplicationConfig): AppConfig {
            val env = config.propertyOrNull("app.environment")?.getString() ?: "dev"
            val port = config.propertyOrNull("ktor.deployment.port")?.getString()?.toIntOrNull() ?: 8080
            return AppConfig(
                environment = env,
                port = port
            )
        }
    }
}
