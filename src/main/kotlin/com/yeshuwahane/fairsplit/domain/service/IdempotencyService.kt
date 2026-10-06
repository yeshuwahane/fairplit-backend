package com.yeshuwahane.fairsplit.domain.service

import com.yeshuwahane.fairsplit.auth.util.SecurityUtils
import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.common.error.ErrorCode
import com.yeshuwahane.fairsplit.domain.model.IdempotencyRecord
import com.yeshuwahane.fairsplit.domain.repository.IdempotencyRepository
import com.yeshuwahane.fairsplit.infrastructure.database.dbQuery
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class IdempotencyService(
    private val idempotencyRepository: IdempotencyRepository
) {
    suspend fun <T> executeWithIdempotency(
        userId: UUID,
        key: String?,
        endpoint: String,
        requestBody: String,
        serializer: (T) -> String,
        deserializer: (String) -> T,
        operation: suspend () -> T
    ): Pair<T, Boolean> = dbQuery {
        if (key.isNullOrBlank()) {
            return@dbQuery Pair(operation(), false)
        }

        val cleanKey = key.trim()
        val requestHash = SecurityUtils.sha256(requestBody)

        val existing = idempotencyRepository.find(userId, cleanKey, endpoint)
        if (existing != null) {
            if (existing.requestHash != requestHash) {
                throw AppException.Validation(
                    "Idempotency key '$cleanKey' was previously used with a different request payload",
                    ErrorCode.VALIDATION_ERROR
                )
            }
            return@dbQuery Pair(deserializer(existing.responseBody), true)
        }

        val result = operation()
        val serialized = serializer(result)

        try {
            idempotencyRepository.save(
                IdempotencyRecord(
                    id = UUID.randomUUID(),
                    userId = userId,
                    key = cleanKey,
                    endpoint = endpoint,
                    requestHash = requestHash,
                    responseStatus = 200,
                    responseBody = serialized,
                    createdAt = Instant.now(),
                    expiresAt = Instant.now().plus(24, ChronoUnit.HOURS)
                )
            )
        } catch (_: Exception) {
            // If another concurrent request inserted it first, handle silently
        }

        Pair(result, false)
    }
}
