package com.yeshuwahane.fairsplit.common.validation

import com.yeshuwahane.fairsplit.common.error.AppException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidatorsTest {

    @Test
    fun testNormalizePhoneValid() {
        assertEquals("+919876543210", Validators.normalizePhone("+919876543210"))
        assertEquals("+919876543210", Validators.normalizePhone("919876543210"))
        assertEquals("+919876543210", Validators.normalizePhone("+91 9876-543210"))
    }

    @Test
    fun testNormalizePhoneInvalid() {
        assertFailsWith<AppException.Validation> {
            Validators.normalizePhone("invalid-phone")
        }
        assertFailsWith<AppException.Validation> {
            Validators.normalizePhone("")
        }
        assertFailsWith<AppException.Validation> {
            Validators.normalizePhone("+0123456789") // Country code cannot start with 0
        }
    }

    @Test
    fun testRequireNotBlank() {
        assertEquals("valid", Validators.requireNotBlank("  valid  ", "field"))
        assertFailsWith<AppException.Validation> {
            Validators.requireNotBlank("", "field")
        }
        assertFailsWith<AppException.Validation> {
            Validators.requireNotBlank(null, "field")
        }
    }

    @Test
    fun testRequireValidOtp() {
        assertEquals("123456", Validators.requireValidOtp("123456"))
        assertFailsWith<AppException.Validation> {
            Validators.requireValidOtp("12345") // 5 digits
        }
        assertFailsWith<AppException.Validation> {
            Validators.requireValidOtp("1234567") // 7 digits
        }
        assertFailsWith<AppException.Validation> {
            Validators.requireValidOtp("12345a") // non-digit
        }
    }

    @Test
    fun testRequireValidUuid() {
        val validUuid = java.util.UUID.randomUUID().toString()
        val parsed = Validators.requireValidUuid(validUuid)
        assertEquals(validUuid, parsed.toString())

        assertFailsWith<AppException.Validation> {
            Validators.requireValidUuid("not-a-uuid")
        }
    }
}
