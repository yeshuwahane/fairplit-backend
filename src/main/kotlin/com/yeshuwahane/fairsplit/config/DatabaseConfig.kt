package com.yeshuwahane.fairsplit.config

import io.ktor.server.config.*
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

data class DatabaseConfig(
    val url: String = "",
    val user: String = "",
    val password: String = "",
    val maxPoolSize: Int = 10,
    val minIdle: Int = 2,
    val connectionTimeoutMs: Long = 30000L
) {
    val isPostgreSql: Boolean
        get() = isPostgresScheme(url)

    val driverClassName: String
        get() = if (isPostgreSql) "org.postgresql.Driver" else "org.h2.Driver"

    val effectiveJdbcUrl: String
        get() = resolveJdbcUrl(url)

    val effectiveUser: String
        get() = resolveUser(url, user)

    val effectivePassword: String
        get() = resolvePassword(url, password)

    val sanitizedUrl: String
        get() = sanitizeDatabaseUrl(effectiveJdbcUrl)

    companion object {
        private val PG_URI_PATTERN = Pattern.compile(
            "^(?:jdbc:)?postgres(?:ql)?://(?:([^/@:]+)(?::([^/@]+))?@)?([^/?#]+)(?:/([^?#]*))?(?:\\?(.*))?$"
        )

        fun isPostgresScheme(rawUrl: String): Boolean {
            val trimmed = rawUrl.trim()
            return trimmed.startsWith("jdbc:postgresql:") ||
                    trimmed.startsWith("postgresql:") ||
                    trimmed.startsWith("postgres:")
        }

        fun sanitizeDatabaseUrl(rawUrl: String): String {
            if (rawUrl.isBlank()) return ""
            return rawUrl.replace(Regex("://[^/@]+@"), "://[REDACTED]@").substringBefore("?")
        }

        fun resolveJdbcUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) {
                return "jdbc:h2:./data/fairsplit_dev;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE"
            }
            if (trimmed.startsWith("jdbc:h2:")) {
                return trimmed
            }
            if (isPostgresScheme(trimmed)) {
                val matcher = PG_URI_PATTERN.matcher(trimmed)
                if (matcher.matches()) {
                    val hostAndPort = matcher.group(3)
                    val database = matcher.group(4)
                    val query = matcher.group(5)

                    val sb = StringBuilder("jdbc:postgresql://").append(hostAndPort)
                    if (!database.isNullOrEmpty()) {
                        sb.append("/").append(database)
                    } else if (trimmed.contains("$hostAndPort/")) {
                        sb.append("/")
                    }
                    if (!query.isNullOrEmpty()) {
                        sb.append("?").append(query)
                    }
                    return sb.toString()
                }

                if (trimmed.startsWith("jdbc:postgresql:")) {
                    return trimmed
                }
                if (trimmed.startsWith("postgres://")) {
                    return "jdbc:postgresql://" + trimmed.removePrefix("postgres://")
                }
                if (trimmed.startsWith("postgresql://")) {
                    return "jdbc:postgresql://" + trimmed.removePrefix("postgresql://")
                }
            }

            throw IllegalArgumentException("Unsupported database URL scheme: ${sanitizeDatabaseUrl(trimmed)}")
        }

        fun resolveUser(rawUrl: String, explicitUser: String): String {
            if (explicitUser.isNotBlank()) return explicitUser.trim()
            val trimmed = rawUrl.trim()
            if (isPostgresScheme(trimmed)) {
                val matcher = PG_URI_PATTERN.matcher(trimmed)
                if (matcher.matches()) {
                    val uriUser = matcher.group(1)
                    if (!uriUser.isNullOrEmpty()) {
                        return URLDecoder.decode(uriUser, StandardCharsets.UTF_8)
                    }
                }
            }
            return ""
        }

        fun resolvePassword(rawUrl: String, explicitPassword: String): String {
            if (explicitPassword.isNotBlank()) return explicitPassword.trim()
            val trimmed = rawUrl.trim()
            if (isPostgresScheme(trimmed)) {
                val matcher = PG_URI_PATTERN.matcher(trimmed)
                if (matcher.matches()) {
                    val uriPass = matcher.group(2)
                    if (!uriPass.isNullOrEmpty()) {
                        return URLDecoder.decode(uriPass, StandardCharsets.UTF_8)
                    }
                }
            }
            return ""
        }

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
