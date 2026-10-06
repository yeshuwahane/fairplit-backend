package com.yeshuwahane.fairsplit.common.error

import com.yeshuwahane.fairsplit.common.api.ApiResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory
import java.util.UUID

fun Application.configureStatusPages() {
    val logger = LoggerFactory.getLogger("com.yeshuwahane.fairsplit.StatusPages")

    install(StatusPages) {
        exception<AppException> { call, cause ->
            val correlationId = call.request.headers["X-Correlation-Id"] ?: UUID.randomUUID().toString()
            logger.warn("AppException [{}]: {} (code: {}, status: {})", correlationId, cause.message, cause.errorCode, cause.httpStatus)
            call.respond(
                cause.httpStatus,
                ApiResponse.error(
                    code = cause.errorCode.name,
                    message = cause.message,
                    details = cause.details,
                    correlationId = correlationId
                )
            )
        }

        exception<Throwable> { call, cause ->
            val correlationId = call.request.headers["X-Correlation-Id"] ?: UUID.randomUUID().toString()
            logger.error("Unhandled Exception [{}]: {}", correlationId, cause.message, cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse.error(
                    code = ErrorCode.INTERNAL_ERROR.name,
                    message = "An unexpected error occurred. Please try again later.",
                    details = null,
                    correlationId = correlationId
                )
            )
        }
    }
}
