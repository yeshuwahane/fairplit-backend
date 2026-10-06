package com.yeshuwahane.fairsplit.infrastructure.database

import org.flywaydb.core.Flyway
import org.junit.Assume
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest {

    @Test
    fun testAllMigrationsOnPostgreSQL() {
        val dockerAvailable = try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (_: Throwable) {
            false
        }

        Assume.assumeTrue("Docker is not available in the current environment to run Testcontainers", dockerAvailable)

        val postgres = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("fairsplit_test")
            withUsername("test")
            withPassword("test")
            start()
        }

        try {
            val flyway = Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()

            val result = flyway.migrate()
            assertTrue(result.success, "Flyway migrations should succeed")
            assertEquals(8, result.migrationsExecuted, "Expected 8 migrations to be executed")

            // Verify tables exist
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                val meta = conn.metaData
                val expectedTables = listOf(
                    "users", "auth_identities", "phone_otp_challenges", "refresh_sessions",
                    "epics", "epic_members", "expenses", "expense_splits",
                    "user_devices", "user_preferences", "settlements", "activity_logs",
                    "idempotency_keys"
                )
                for (table in expectedTables) {
                    val rs = meta.getTables(null, "public", table, null)
                    assertTrue(rs.next(), "Table $table should exist")
                }
            }
        } finally {
            postgres.stop()
        }
    }
}
