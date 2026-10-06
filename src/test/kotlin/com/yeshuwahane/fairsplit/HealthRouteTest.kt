package com.yeshuwahane.fairsplit

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HealthRouteTest {

    @Test
    fun testHealthEndpointReturnsOkAndTimestamp() = testApplication {
        application {
            module()
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals("ok", json["status"]?.jsonPrimitive?.content)
        val timestamp = json["timestamp"]?.jsonPrimitive?.content
        assertNotNull(timestamp)
        assertTrue(timestamp.isNotBlank())
    }

    @Test
    fun testApplicationConfLoadsModuleConfig() = testApplication {
        environment {
            config = ApplicationConfig("application.conf")
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals("ok", json["status"]?.jsonPrimitive?.content)
        val timestamp = json["timestamp"]?.jsonPrimitive?.content
        assertNotNull(timestamp)
        assertTrue(timestamp.isNotBlank())
    }
}
