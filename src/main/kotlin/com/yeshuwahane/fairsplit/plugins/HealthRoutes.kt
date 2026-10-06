package com.yeshuwahane.fairsplit.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: String
)

fun Application.configureHealthRoutes() {
    routing {
        get("/health") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    timestamp = Instant.now().toString()
                )
            )
        }
    }
}
