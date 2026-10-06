package com.yeshuwahane.fairsplit.common.validation

import com.yeshuwahane.fairsplit.common.error.AppException
import java.util.UUID

object Validators {
    private val E164_REGEX = Regex("^\\+[1-9]\\d{1,14}\$")

    fun requireNotBlank(value: String?, fieldName: String): String {
        if (value.isNullOrBlank()) {
            throw AppException.Validation("$fieldName must not be blank")
        }
        return value.trim()
    }

    fun normalizePhone(rawPhone: String): String {
        val trimmed = rawPhone.trim().replace(" ", "").replace("-", "")
        val formatted = when {
            trimmed.startsWith("+") -> trimmed
            trimmed.length == 10 -> "+91$trimmed"
            else -> "+$trimmed"
        }
        if (!E164_REGEX.matches(formatted)) {
            throw AppException.Validation("Invalid phone number format: $rawPhone. Must be E.164 (e.g. +919876543210)")
        }
        return formatted
    }

    fun requireValidUuid(rawUuid: String, fieldName: String = "ID"): UUID {
        return try {
            UUID.fromString(rawUuid.trim())
        } catch (_: Exception) {
            throw AppException.Validation("Invalid UUID format for $fieldName: $rawUuid")
        }
    }

    fun requireValidOtp(otp: String): String {
        val trimmed = otp.trim()
        if (trimmed.length != 6 || !trimmed.all { it.isDigit() }) {
            throw AppException.Validation("OTP must be exactly 6 numeric digits")
        }
        return trimmed
    }
}
