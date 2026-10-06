package com.yeshuwahane.fairsplit.common.error

import io.ktor.http.HttpStatusCode

sealed class AppException(
    val errorCode: ErrorCode,
    val httpStatus: HttpStatusCode,
    override val message: String,
    val details: String? = null,
    override val cause: Throwable? = null
) : RuntimeException(message, cause) {

    class Unauthorized(
        message: String = "Authentication required",
        code: ErrorCode = ErrorCode.UNAUTHORIZED,
        details: String? = null,
        cause: Throwable? = null
    ) : AppException(code, HttpStatusCode.Unauthorized, message, details, cause)

    class Forbidden(
        message: String = "Access denied",
        code: ErrorCode = ErrorCode.FORBIDDEN,
        details: String? = null,
        cause: Throwable? = null
    ) : AppException(code, HttpStatusCode.Forbidden, message, details, cause)

    class NotFound(
        message: String = "Resource not found",
        code: ErrorCode = ErrorCode.NOT_FOUND,
        details: String? = null,
        cause: Throwable? = null
    ) : AppException(code, HttpStatusCode.NotFound, message, details, cause)

    class Conflict(
        message: String = "Resource conflict",
        code: ErrorCode = ErrorCode.CONFLICT,
        details: String? = null,
        cause: Throwable? = null
    ) : AppException(code, HttpStatusCode.Conflict, message, details, cause)

    class Validation(
        message: String,
        code: ErrorCode = ErrorCode.VALIDATION_ERROR,
        details: String? = null,
        cause: Throwable? = null
    ) : AppException(code, HttpStatusCode.BadRequest, message, details, cause)

    class BusinessRule(
        message: String,
        code: ErrorCode,
        details: String? = null,
        cause: Throwable? = null
    ) : AppException(code, HttpStatusCode.BadRequest, message, details, cause)

    class RateLimited(
        message: String = "Too many requests",
        code: ErrorCode = ErrorCode.RATE_LIMITED,
        details: String? = null,
        cause: Throwable? = null
    ) : AppException(code, HttpStatusCode.TooManyRequests, message, details, cause)

    class Internal(
        message: String = "Internal server error",
        cause: Throwable? = null
    ) : AppException(ErrorCode.INTERNAL_ERROR, HttpStatusCode.InternalServerError, message, null, cause)
}
