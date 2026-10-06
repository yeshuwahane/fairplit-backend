package com.yeshuwahane.fairsplit.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseConfigTest {

    @Test
    fun testPostgresqlUriWithCredentialsNormalization() {
        val rawUrl = "postgresql://neondb_owner:npg_secret123@ep-cool-fog.neon.tech/neondb?sslmode=require&channel_binding=require"
        val config = DatabaseConfig(url = rawUrl)

        assertTrue(config.isPostgreSql)
        assertEquals("org.postgresql.Driver", config.driverClassName)
        assertEquals("jdbc:postgresql://ep-cool-fog.neon.tech/neondb?sslmode=require&channel_binding=require", config.effectiveJdbcUrl)
        assertEquals("neondb_owner", config.effectiveUser)
        assertEquals("npg_secret123", config.effectivePassword)
        assertEquals("jdbc:postgresql://ep-cool-fog.neon.tech/neondb", config.sanitizedUrl)
        assertFalse(config.sanitizedUrl.contains("npg_secret123"))
    }

    @Test
    fun testPostgresUriWithSpecialCharactersInPassword() {
        val rawUrl = "postgres://usr_admin:p%40ss%3Aword%21@db.host.internal:5432/fairsplit_prod?sslmode=require"
        val config = DatabaseConfig(url = rawUrl)

        assertTrue(config.isPostgreSql)
        assertEquals("org.postgresql.Driver", config.driverClassName)
        assertEquals("jdbc:postgresql://db.host.internal:5432/fairsplit_prod?sslmode=require", config.effectiveJdbcUrl)
        assertEquals("usr_admin", config.effectiveUser)
        assertEquals("p@ss:word!", config.effectivePassword)
    }

    @Test
    fun testAlreadyJdbcPostgresqlUrlUnchanged() {
        val rawUrl = "jdbc:postgresql://ep-cool-fog.neon.tech/neondb?sslmode=require"
        val config = DatabaseConfig(url = rawUrl, user = "myuser", password = "mypassword")

        assertTrue(config.isPostgreSql)
        assertEquals("org.postgresql.Driver", config.driverClassName)
        assertEquals("jdbc:postgresql://ep-cool-fog.neon.tech/neondb?sslmode=require", config.effectiveJdbcUrl)
        assertEquals("myuser", config.effectiveUser)
        assertEquals("mypassword", config.effectivePassword)
    }

    @Test
    fun testPostgresqlMultiHostClusterNormalization() {
        val rawUrl = "postgresql://neondb_owner:secret@host1:5432,host2:5432/neondb?sslmode=require"
        val config = DatabaseConfig(url = rawUrl)

        assertTrue(config.isPostgreSql)
        assertEquals("org.postgresql.Driver", config.driverClassName)
        assertEquals("jdbc:postgresql://host1:5432,host2:5432/neondb?sslmode=require", config.effectiveJdbcUrl)
        assertEquals("neondb_owner", config.effectiveUser)
        assertEquals("secret", config.effectivePassword)
    }

    @Test
    fun testDefaultFallbackToH2WhenEmpty() {
        val config = DatabaseConfig(url = "")

        assertFalse(config.isPostgreSql)
        assertEquals("org.h2.Driver", config.driverClassName)
        assertTrue(config.effectiveJdbcUrl.startsWith("jdbc:h2:./data/fairsplit_dev"))
        assertEquals("", config.effectiveUser)
        assertEquals("", config.effectivePassword)
    }

    @Test
    fun testExplicitH2InMemoryConfiguration() {
        val rawUrl = "jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        val config = DatabaseConfig(url = rawUrl)

        assertFalse(config.isPostgreSql)
        assertEquals("org.h2.Driver", config.driverClassName)
        assertEquals(rawUrl, config.effectiveJdbcUrl)
    }

    @Test
    fun testUnsupportedDatabaseSchemeFails() {
        val rawUrl = "mysql://root:pass@localhost:3306/db"
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig(url = rawUrl).effectiveJdbcUrl
        }
    }

    @Test
    fun testExplicitUserAndPasswordOverrideUriUserinfo() {
        val rawUrl = "postgresql://default_user:default_pass@localhost:5432/db"
        val config = DatabaseConfig(url = rawUrl, user = "override_user", password = "override_pass")

        assertEquals("override_user", config.effectiveUser)
        assertEquals("override_pass", config.effectivePassword)
        assertEquals("jdbc:postgresql://localhost:5432/db", config.effectiveJdbcUrl)
    }

    @Test
    fun testSanitizeUrlNeverExposesCredentialsOrQueryParams() {
        val rawUrl = "postgresql://secret_user:super_secret_token@myhost.tech:5432/prod_db?secretParam=hidden"
        val sanitized = DatabaseConfig.sanitizeDatabaseUrl(rawUrl)

        assertFalse(sanitized.contains("secret_user"))
        assertFalse(sanitized.contains("super_secret_token"))
        assertFalse(sanitized.contains("hidden"))
        assertEquals("postgresql://[REDACTED]@myhost.tech:5432/prod_db", sanitized)
    }
}
