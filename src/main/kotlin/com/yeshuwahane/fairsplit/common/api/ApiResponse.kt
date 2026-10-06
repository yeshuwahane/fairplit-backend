package com.yeshuwahane.fairsplit.common.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(
            success = true,
            data = data,
            error = null
        )

        fun error(code: String, message: String, details: String? = null, correlationId: String? = null): ApiResponse<Unit> = ApiResponse(
            success = false,
            data = null,
            error = ApiError(
                code = code,
                message = message,
                details = details,
                correlationId = correlationId
            )
        )
    }
}
