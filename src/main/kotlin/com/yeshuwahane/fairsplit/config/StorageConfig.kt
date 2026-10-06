package com.yeshuwahane.fairsplit.config

import io.ktor.server.config.*

data class StorageConfig(
    val uploadDir: String = "uploads"
) {
    companion object {
        fun fromConfig(config: ApplicationConfig): StorageConfig {
            val uploadDir = config.propertyOrNull("storage.uploadDir")?.getString() ?: "uploads"
            return StorageConfig(
                uploadDir = uploadDir
            )
        }
    }
}
