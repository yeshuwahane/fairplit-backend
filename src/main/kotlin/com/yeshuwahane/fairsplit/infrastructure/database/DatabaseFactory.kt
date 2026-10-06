package com.yeshuwahane.fairsplit.infrastructure.database

import com.yeshuwahane.fairsplit.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.io.Closeable
import javax.sql.DataSource

class DatabaseFactory(private val config: DatabaseConfig) : Closeable {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private var dataSource: HikariDataSource? = null
    var database: Database? = null
        private set

    fun init(): DatabaseFactory {
        val isPostgres = config.isPostgreSql
        val effectiveUrl = config.effectiveJdbcUrl
        val driver = config.driverClassName
        val effectiveUser = config.effectiveUser
        val effectivePassword = config.effectivePassword

        if (isPostgres) {
            logger.info("Initializing production PostgreSQL database at {}", config.sanitizedUrl)
        } else {
            logger.info("No PostgreSQL DATABASE_URL configured. Falling back to local embedded H2 database (PostgreSQL compatibility mode).")
            logger.info("Connecting to embedded database at {}", config.sanitizedUrl)
        }

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = effectiveUrl
            if (effectiveUser.isNotBlank()) username = effectiveUser
            if (effectivePassword.isNotBlank()) password = effectivePassword
            maximumPoolSize = config.maxPoolSize
            minimumIdle = config.minIdle
            connectionTimeout = config.connectionTimeoutMs
            driverClassName = driver
            isAutoCommit = false
            transactionIsolation = if (isPostgres) "TRANSACTION_REPEATABLE_READ" else "TRANSACTION_READ_COMMITTED"
            validate()
        }

        val ds = HikariDataSource(hikariConfig)
        dataSource = ds

        if (isPostgres) {
            FlywayRunner(ds).migrate()
        }

        database = Database.connect(ds)

        if (!isPostgres) {
            org.jetbrains.exposed.sql.transactions.transaction(database!!) {
                org.jetbrains.exposed.sql.SchemaUtils.create(
                    com.yeshuwahane.fairsplit.user.table.UsersTable,
                    com.yeshuwahane.fairsplit.auth.table.AuthIdentitiesTable,
                    com.yeshuwahane.fairsplit.auth.table.PhoneOtpChallengesTable,
                    com.yeshuwahane.fairsplit.auth.table.RefreshSessionsTable,
                    com.yeshuwahane.fairsplit.data.table.EpicsTable,
                    com.yeshuwahane.fairsplit.data.table.EpicMembersTable,
                    com.yeshuwahane.fairsplit.data.table.ExpensesTable,
                    com.yeshuwahane.fairsplit.data.table.ExpenseSplitsTable,
                    com.yeshuwahane.fairsplit.data.table.SettlementsTable,
                    com.yeshuwahane.fairsplit.data.table.ActivityLogsTable,
                    com.yeshuwahane.fairsplit.data.table.UserDevicesTable,
                    com.yeshuwahane.fairsplit.data.table.UserPreferencesTable,
                    com.yeshuwahane.fairsplit.data.table.IdempotencyKeysTable
                )
            }
        }

        logger.info("Database and Exposed connection pool initialized successfully using {}.", driver)
        return this
    }

    fun getDataSource(): DataSource = dataSource ?: error("DataSource not initialized")

    override fun close() {
        dataSource?.close()
        logger.info("Database connection pool closed.")
    }
}
