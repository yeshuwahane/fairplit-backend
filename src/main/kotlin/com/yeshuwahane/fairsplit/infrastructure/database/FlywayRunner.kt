package com.yeshuwahane.fairsplit.infrastructure.database

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

class FlywayRunner(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(FlywayRunner::class.java)

    fun migrate(): Int {
        logger.info("Starting Flyway database migration...")
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()

        val result = flyway.migrate()
        logger.info("Flyway migration completed successfully. Applied {} migrations.", result.migrationsExecuted)
        return result.migrationsExecuted
    }
}
